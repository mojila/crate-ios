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
        for (name in assets) {
            val dest = File(dir, name)
            var copy = true
            if (dest.exists()) {
                manager.open("native/$name").use { input ->
                    dest.inputStream().use { current ->
                        copy = !current.readBytes().contentEquals(input.readBytes())
                    }
                }
            }
            if (copy) {
                manager.open("native/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // Writable files trip a loader warning (fatal on future Android);
            // these are our own bytes, so lock them down.
            dest.setReadOnly()
            System.load(dest.absolutePath)
        }
        System.loadLibrary("crate_jni")
        loaded = true
    }
}
