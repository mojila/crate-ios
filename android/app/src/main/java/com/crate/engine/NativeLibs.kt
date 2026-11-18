package com.crate.engine

import android.content.Context
import java.io.File

/**
 * Loads the engine's native chain. libcrate_jni.so ships in jniLibs and
 * resolves its DT_NEEDED entries (libslirp.so.0, libglib-2.0.so.0) from
 * already-loaded libraries — but versioned-soname files are not packaged as
 * jniLibs, so the versioned glib/slirp binaries ship under assets/native and
 * are loaded here by absolute path, glib first. Call once, on a background
 * thread, before touching [Engine].
 */
object NativeLibs {
    private val assets = listOf(
        "libglib-2.0.so.0.9000.1",
        "libslirp.so.0.4.0",
    )

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val dir = File(context.filesDir, "native").apply { mkdirs() }
        val manager = context.assets
        // Stamp, not byte-compare: a versioned marker written by
        // Scripts/android-build-native.sh alongside the shipped .so files.
        // (Byte-comparing 2.3 MB every launch was also slow; and a stale copy
        // once survived it on-device, so the marker is the single source.)
        val shippedStamp = try {
            manager.open("native/Native.version").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
        val stampFile = File(dir, "Native.version")
        val currentStamp = try {
            if (stampFile.exists()) stampFile.readText() else null
        } catch (_: Exception) {
            null
        }
        val copy = shippedStamp == null || shippedStamp != currentStamp
        for (name in assets) {
            val dest = File(dir, name)
            if (copy || !dest.exists()) {
                // The shipped copy is read-only: delete first, recreating
                // beats chmod racing the loader (a rewrite over 444
                // fails EACCES and aborts the whole load).
                dest.delete()
                manager.open("native/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // Writable files trip a loader warning (fatal on future Android);
            // these are our own bytes, so lock them down.
            dest.setReadOnly()
            System.load(dest.absolutePath)
        }
        if (copy && shippedStamp != null) {
            try {
                stampFile.writeText(shippedStamp)
            } catch (_: Exception) {
            }
        }
        System.loadLibrary("crate_jni")
        loaded = true
    }
}
