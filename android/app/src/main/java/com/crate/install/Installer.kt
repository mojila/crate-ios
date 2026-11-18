package com.crate.install

import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Installs a catalog image as a new VM: downloads its kernel, initrd and
 * disk parts, checks each against the catalog's SHA-256, unpacks the disk as
 * parts arrive (Rust: sparse + hashed), grows it, and writes the owner's
 * first-boot settings (seed.img). Mirrors iOS `Installer`.
 *
 * VMs live in internal storage (`filesDir/VMs/<uuid>/`), which supports
 * sparse files — external/emulated storage may not.
 */
class Installer(private val context: Context) {
    companion object {
        @JvmStatic external fun nativeLastError(): String
        @JvmStatic external fun nativeBegin(diskPath: String): Long
        @JvmStatic external fun nativePart(session: Long, partPath: String): Long
        @JvmStatic external fun nativeBytes(session: Long): Long
        @JvmStatic external fun nativeFinish(session: Long): String?
        @JvmStatic external fun nativeExtend(diskPath: String, size: Long): Int
        @JvmStatic external fun nativeSha256(path: String): String?
        @JvmStatic external fun nativeSeed(
            seedPath: String,
            user: String,
            password: String,
            hostname: String,
            timezone: String,
            vendorTemplate: String,
            instance: String,
        ): Int

        fun vmsRoot(context: Context): File = File(context.filesDir, "VMs")

        /** Removes the VM's whole folder (disk, seed, kernel, machine.json). */
        fun deleteMachine(context: Context, id: String): Boolean = try {
            File(vmsRoot(context), id).deleteRecursively()
        } catch (_: Exception) {
            false
        }

        fun saveMachine(context: Context, machine: Machine) {
            machine.write(File(File(vmsRoot(context), machine.id), "machine.json"))
        }

        /**
         * Deletes install leftovers: VM dirs that never got a machine.json
         * (a cancelled/failed install). Never call while an install or
         * reinstall runs — its dir has no machine.json until it finishes.
         */
        fun cleanStrays(context: Context) {
            vmsRoot(context).listFiles()
                ?.filter { it.isDirectory && !File(it, "machine.json").exists() }
                ?.forEach { try { it.deleteRecursively() } catch (_: Exception) { } }
        }

        /**
         * Grows the disk to [newDiskGB] when larger than [currentDiskGB].
         * Shrinking is not supported (the guest filesystem can't be safely
         * shrunk in place), so a smaller size is a no-op. Returns an error
         * message or null on success. The guest must run `resize2fs /dev/vda`
         * itself afterwards — cloud-init only grows the filesystem on a
         * first boot.
         */
        fun growDisk(diskPath: String, currentDiskGB: Int, newDiskGB: Int): String? {
            if (newDiskGB <= currentDiskGB) return null
            return if (nativeExtend(diskPath, newDiskGB.toLong() shl 30) != 0) {
                nativeLastError()
            } else {
                null
            }
        }

        /**
         * Rewrites seed.img with a new account + hostname and a fresh
         * instance-id. Takes effect only on a cloud-init first boot — the
         * images switch cloud-init off after theirs, so pair this with the
         * guest clean command (see CLEAN_CMD) or a fresh disk. Returns an
         * error message or null on success.
         */
        fun reseed(
            seedPath: String,
            user: String,
            password: String,
            hostname: String,
            vendorTemplate: String,
        ): String? {
            val tz = try {
                java.util.TimeZone.getDefault().id
            } catch (_: Exception) {
                "UTC"
            }
            val instance = UUID.randomUUID().toString()
            return if (nativeSeed(seedPath, user, password, hostname, tz,
                    vendorTemplate, instance) != 0) {
                nativeLastError()
            } else {
                null
            }
        }

        fun machines(context: Context): List<Machine> =
            vmsRoot(context).listFiles()
                ?.mapNotNull { dir ->
                    val f = File(dir, "machine.json")
                    if (f.exists()) Machine.read(f) else null
                }
                ?.sortedByDescending { it.installedAt }
                ?: emptyList()
    }

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val done: Long, val total: Long) : Phase
        data class Unpacking(val done: Long, val total: Long) : Phase
        data object Finishing : Phase
        data class Done(val machine: Machine) : Phase
        data class Failed(val message: String) : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    var busy by mutableStateOf(false)
        private set

    fun reset() {
        if (!busy) phase = Phase.Idle
    }

    fun cancel() {
        // Cooperative: the worker checks Thread interruption between files.
        worker?.interrupt()
    }

    @Volatile
    private var worker: Thread? = null

    fun install(
        image: CatalogImage,
        memoryGB: Int,
        diskGB: Int,
        user: String,
        password: String,
        hostname: String,
    ) {
        if (busy) return
        busy = true
        phase = Phase.Downloading(0, image.downloadBytes)
        // The download runs while crate is open: hold a wake lock (the screen
        // timer is held too while the install screen shows progress).
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crate:install")
        wake.acquire(30 * 60 * 1000L)
        val w = Thread({
            try {
                val machine = run(image, memoryGB, diskGB, user, password, hostname)
                phase = Phase.Done(machine)
            } catch (e: InterruptedException) {
                phase = Phase.Idle
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: e.toString())
            } finally {
                busy = false
                worker = null
                try {
                    wake.release()
                } catch (_: Exception) {
                }
            }
        }, "vm-install")
        worker = w
        w.start()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    /**
     * Erases a VM back to a fresh disk and installs it again with new
     * settings, in the same folder (same Home entry). A re-seed alone can't
     * switch users: the images turn cloud-init off after the first boot
     * (cloud-init.disabled), so only a fresh disk boots into setup again.
     * The old files are kept as a backup until the new install verifies,
     * then removed; on failure the backup is restored and the VM boots as
     * before. Needs the catalog image (kernel/disk URLs) — pass the entry
     * matching [machine]'s imageId.
     */
    fun reinstall(
        machine: Machine,
        image: CatalogImage,
        memoryGB: Int,
        diskGB: Int,
        user: String,
        password: String,
        hostname: String,
    ) {
        if (busy) return
        busy = true
        phase = Phase.Downloading(0, image.downloadBytes)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crate:install")
        wake.acquire(30 * 60 * 1000L)
        val w = Thread({
            val dir = File(vmsRoot(context), machine.id)
            val bak = File(vmsRoot(context), machine.id + ".bak")
            try {
                try {
                    bak.deleteRecursively()
                } catch (_: Exception) {
                }
                bak.mkdirs()
                for (name in listOf("vmlinuz", "initrd.img", "disk.img", "seed.img", "machine.json")) {
                    val f = File(dir, name)
                    if (f.exists() && !f.renameTo(File(bak, name))) {
                        throw RuntimeException("can't back up $name")
                    }
                }
                try {
                    val done = runInto(dir, machine.id, image, memoryGB, diskGB,
                        user, password, hostname)
                    try {
                        bak.deleteRecursively()
                    } catch (_: Exception) {
                    }
                    phase = Phase.Done(done)
                } catch (e: Exception) {
                    // Put the old VM back so it still boots.
                    for (name in listOf("vmlinuz", "initrd.img", "disk.img", "seed.img", "machine.json")) {
                        try {
                            File(dir, name).delete()
                        } catch (_: Exception) {
                        }
                        try {
                            File(bak, name).renameTo(File(dir, name))
                        } catch (_: Exception) {
                        }
                    }
                    throw e
                }
            } catch (e: InterruptedException) {
                phase = Phase.Idle
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: e.toString())
            } finally {
                busy = false
                worker = null
                try {
                    wake.release()
                } catch (_: Exception) {
                }
            }
        }, "vm-reinstall")
        worker = w
        w.start()
    }

    private var downloaded: Long = 0

    private fun fetch(file: CatalogFile, to: File, progressTotal: Long) {
        val tmp = File(to.parent, to.name + ".download")
        try {
            downloadTo(file, tmp, progressTotal)
            if (to.exists()) to.delete()
            if (!tmp.renameTo(to)) throw RuntimeException("can't move ${to.name} into place")
            downloaded += file.size
            phase = Phase.Downloading(downloaded, progressTotal)
        } finally {
            tmp.delete()
        }
    }

    /** Downloads + SHA-256-verifies exactly into `tmp`. */
    private fun downloadTo(file: CatalogFile, tmp: File, progressTotal: Long) {
        checkInterrupted()
        tmp.parentFile?.mkdirs()
        var conn: HttpURLConnection? = null
        try {
            conn = URL(file.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            if (conn.responseCode != 200) throw RuntimeException("download stopped (HTTP ${conn.responseCode})")
            val before = downloaded
            var got: Long = 0
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        checkInterrupted()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        phase = Phase.Downloading(before + got, progressTotal)
                    }
                }
            }
            val hex = nativeSha256(tmp.absolutePath)
                ?: throw RuntimeException("hash failed: ${nativeLastError()}")
            if (!hex.equals(file.sha256, ignoreCase = true)) {
                throw RuntimeException("${file.url.substringAfterLast('/')} didn't download correctly. Try again.")
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun run(
        image: CatalogImage,
        memoryGB: Int,
        diskGB: Int,
        user: String,
        password: String,
        hostname: String,
    ): Machine {
        val id = UUID.randomUUID()
        val dir = File(vmsRoot(context), id.toString())
        try {
            return runInto(dir, id.toString(), image, memoryGB, diskGB,
                user, password, hostname)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Downloads + unpacks + seeds into [dir]; the caller owns cleanup. */
    private fun runInto(
        dir: File,
        id: String,
        image: CatalogImage,
        memoryGB: Int,
        diskGB: Int,
        user: String,
        password: String,
        hostname: String,
    ): Machine {
        dir.mkdirs()
        val kernel = File(dir, "vmlinuz")
        val initrd = File(dir, "initrd.img")
        val disk = File(dir, "disk.img")
        val seed = File(dir, "seed.img")
        val total = image.downloadBytes
        downloaded = 0
        try {
            fetch(image.kernel, kernel, total)
            fetch(image.initrd, initrd, total)

            if (image.disk.compression != "xz") {
                throw RuntimeException("This image is packed in a way crate can't unpack (${image.disk.compression}).")
            }
            val session = nativeBegin(disk.absolutePath)
            if (session == 0L) throw RuntimeException(nativeLastError())
            val tmpPart = File(dir, "part.download")
            try {
                for (part in image.disk.parts) {
                    downloadTo(part, tmpPart, total)
                    val written = nativePart(session, tmpPart.absolutePath)
                    if (written < 0) throw RuntimeException(nativeLastError())
                    downloaded += part.size
                    phase = Phase.Unpacking(written, image.disk.size)
                    tmpPart.delete()
                    checkInterrupted()
                }
            } finally {
                tmpPart.delete()
            }
            phase = Phase.Finishing
            val done = nativeFinish(session)
                ?: throw RuntimeException(nativeLastError())
            val (bytes, digest) = done.split(' ')
            if (bytes.toLong() != image.disk.size || !digest.equals(image.disk.sha256, ignoreCase = true)) {
                throw RuntimeException("the disk didn't download correctly. Try again.")
            }
            if (nativeExtend(disk.absolutePath, diskGB.toLong() shl 30) != 0) {
                throw RuntimeException(nativeLastError())
            }
            val tz = java.util.TimeZone.getDefault().id
            if (nativeSeed(seed.absolutePath, user, password, hostname, tz,
                    image.vendorData ?: "", id.toString()) != 0) {
                throw RuntimeException(nativeLastError())
            }
            val machine = Machine(
                id = id.toString(),
                imageId = image.id,
                name = image.name,
                version = image.version,
                desktop = image.desktop,
                cmdline = image.cmdline,
                cpus = 4,
                memoryGB = memoryGB,
                diskGB = diskGB,
                installedAt = System.currentTimeMillis(),
            )
            machine.write(File(dir, "machine.json"))
            return machine
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            // Caller owns cleanup: fresh installs delete the dir, reinstalls
            // restore the backup.
            throw e
        }
    }
}
