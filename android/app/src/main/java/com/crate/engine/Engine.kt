package com.crate.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * One crate virtual machine, owned by the UI. Wraps the JNI surface in
 * rust-bridge (nativeCreate/nativeStart/...): create on a background thread
 * (machine_create is slow), then drive from anywhere — engine threads call
 * back on their own threads and are marshalled to the main thread here.
 *
 * State/reason integers are crate_state/crate_stop_reason from crate.h:
 * 0 CREATED, 1 RUNNING, 2 PAUSED, 3 STOPPED; 0 NONE, 1 POWER_OFF, 2 RESET,
 * 3 KILLED, 4 ERROR.
 */
class Engine {
    companion object {
        @JvmStatic
        external fun nativeApiVersion(): Int

        @JvmStatic
        external fun nativeLastError(): String
    }

    var id: Long = 0
        private set

    var stateText by mutableStateOf("idle")
        private set

    var consoleText by mutableStateOf("")
        private set

    var framesSeen by mutableIntStateOf(0)
        private set

    /**
     * Latest guest frame as an [ImageBitmap], or null before the first
     * frame. crate hands pixels as R,G,B,unused per pixel; decoded to
     * ARGB_8888 here (opaque). Decoded off the main thread, published on it.
     */
    var frameImage by mutableStateOf<ImageBitmap?>(null)
        private set

    var frameWidth by mutableIntStateOf(0)
        private set

    var frameHeight by mutableIntStateOf(0)
        private set

    val isLive: Boolean
        get() = id != 0L

    private val main = Handler(Looper.getMainLooper())
    private val buffer = StringBuilder()

    @Volatile
    private var destroyed = false

    fun apiVersion(): Int = nativeApiVersion()

    /** Creates (but does not start) the machine. Returns the machine id. */
    fun create(
        kernel: String,
        initrd: String,
        cmdline: String,
        cpus: Int = 4,
        memoryMiB: Long = 1024,
        withDisplay: Boolean = false,
        disk: String = "",
        seed: String = "",
    ): Long {
        val newId = nativeCreate(kernel, initrd, cmdline, cpus, memoryMiB, withDisplay, disk, seed)
        if (newId == 0L) throw EngineException(nativeLastError())
        id = newId
        destroyed = false
        postState("created")
        return newId
    }

    fun start() {
        val status = nativeStart(id)
        if (status != 0) throw EngineException(nativeLastError())
    }

    /** Queues bytes for the serial console. Returns bytes accepted. */
    fun write(data: ByteArray): Int = nativeConsoleWrite(id, data)

    fun writeText(text: String) {
        write(text.toByteArray())
    }

    fun setDisplayActive(active: Boolean) = nativeSetDisplayActive(id, active)

    fun sendKey(code: Int, down: Boolean) = nativeSendKey(id, code, down)

    fun sendPointer(x: Int, y: Int, buttons: Int) = nativeSendPointer(id, x, y, buttons)

    fun sendScroll(dx: Int, dy: Int) = nativeSendScroll(id, dx, dy)

    fun kill() = nativeKill(id)

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val doomed = id
        id = 0
        if (doomed != 0L) nativeDestroy(doomed)
        main.post {
            stateText = "idle"
            frameImage = null
            frameWidth = 0
            frameHeight = 0
        }
    }

    private fun postState(text: String) {
        main.post { stateText = text }
    }

    private fun appendOutput(data: ByteArray) {
        main.post {
            buffer.append(String(data, Charsets.UTF_8))
            if (buffer.length > 262144) {
                buffer.delete(0, buffer.length - 262144)
            }
            consoleText = buffer.toString()
        }
    }

    // Called from Rust on engine threads; do the minimum and get off them.

    @Suppress("unused")
    private fun onConsoleOutput(data: ByteArray) = appendOutput(data)

    @Suppress("unused")
    private fun onStateChanged(state: Int, reason: Int) {
        val text = when (state) {
            0 -> "created"
            1 -> "running"
            2 -> "paused"
            3 -> "stopped (${stopReason(reason)})"
            else -> "state($state)"
        }
        postState(text)
    }

    @Suppress("unused")
    private fun onFrame(
        width: Int,
        height: Int,
        stride: Int,
        pixels: ByteArray,
        dirtyX: Int,
        dirtyY: Int,
        dirtyWidth: Int,
        dirtyHeight: Int,
    ) {
        // Stride is bytes per row; pixels are R,G,B,unused. Guard against a
        // torn frame (zero size or short buffer) and drop it.
        if (width <= 0 || height <= 0 || stride < width * 4) return
        if (pixels.size < stride * (height - 1) + width * 4) return
        val argb = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val o = row + x * 4
                val r = pixels[o].toInt() and 0xff
                val g = pixels[o + 1].toInt() and 0xff
                val b = pixels[o + 2].toInt() and 0xff
                argb[i++] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, width, 0, 0, width, height)
        val image = bmp.asImageBitmap()
        main.post {
            frameWidth = width
            frameHeight = height
            frameImage = image
            framesSeen++
        }
    }

    private fun stopReason(reason: Int): String = when (reason) {
        0 -> "none"
        1 -> "power-off"
        2 -> "reset"
        3 -> "killed"
        4 -> "error: ${nativeLastError()}"
        else -> "reason($reason)"
    }

    private external fun nativeCreate(
        kernel: String,
        initrd: String,
        cmdline: String,
        cpus: Int,
        memoryMiB: Long,
        withDisplay: Boolean,
        disk: String,
        seed: String,
    ): Long

    private external fun nativeStart(id: Long): Int

    private external fun nativeConsoleWrite(id: Long, data: ByteArray): Int

    private external fun nativeSetDisplayActive(id: Long, active: Boolean)

    private external fun nativeSendKey(id: Long, code: Int, down: Boolean)

    private external fun nativeSendPointer(id: Long, x: Int, y: Int, buttons: Int)

    private external fun nativeSendScroll(id: Long, dx: Int, dy: Int)

    private external fun nativeKill(id: Long)

    private external fun nativeDestroy(id: Long)
}

class EngineException(message: String) : RuntimeException(message)
