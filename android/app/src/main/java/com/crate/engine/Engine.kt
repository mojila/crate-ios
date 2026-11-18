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

        /**
         * Process-wide VM owner. The native registry holds one machine per
         * process, so every activity instance must share one Engine: a fresh
         * Engine per activity would see isLive=false while the old machine
         * still exists, and create() would fail with "one machine per
         * process: destroy the old one first".
         */
        val shared: Engine by lazy { Engine() }
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

    // Frame decode runs here, not on the engine GPU thread: onFrame used to
    // run a ~1M-pixel Kotlin loop + Bitmap alloc synchronously on the GPU
    // callback thread, so a flood of cursor-move frames stalled the display
    // pipeline and the mouse visibly lagged. Now onFrame only enqueues the
    // newest buffer; the decoder drops anything superseded.
    private data class PendingFrame(
        val width: Int,
        val height: Int,
        val stride: Int,
        val pixels: ByteArray,
        val dx: Int,
        val dy: Int,
        val dw: Int,
        val dh: Int,
    )

    private val pendingFrame = java.util.concurrent.atomic.AtomicReference<PendingFrame?>()
    private val frameDecode = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "frame-decode").apply { isDaemon = true }
    }
    // Reused across same-size frames: no per-frame alloc; only the dirty
    // rect is decoded and uploaded (cursor moves touch KBs, not MBs).
    private var argbBuf = IntArray(0)
    private var frameBmp: Bitmap? = null

    // Input dedupe: hover floods the UI thread with moves; identical
    // repeats skip the JNI round-trip (buttons included in the key).
    private var lastPX = -1
    private var lastPY = -1
    private var lastPB = -1

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

    fun sendPointer(x: Int, y: Int, buttons: Int) {
        if (x == lastPX && y == lastPY && buttons == lastPB) return
        lastPX = x
        lastPY = y
        lastPB = buttons
        nativeSendPointer(id, x, y, buttons)
    }

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
        // torn frame (zero size or short buffer) and drop it. Only the
        // newest buffer is kept — the decoder takes-and-clears, so a backlog
        // of cursor-move frames collapses instead of queueing.
        if (width <= 0 || height <= 0 || stride < width * 4) return
        if (pixels.size < stride * (height - 1) + width * 4) return
        pendingFrame.set(PendingFrame(width, height, stride, pixels,
            dirtyX, dirtyY, dirtyWidth, dirtyHeight))
        frameDecode.execute {
            val f = pendingFrame.getAndSet(null) ?: return@execute
            decodeAndPublish(f)
        }
    }

    /** Decodes (dirty rect only) on the decode thread; uploads on main. */
    private fun decodeAndPublish(f: PendingFrame) {
        val w = f.width
        val h = f.height
        var bmp = frameBmp
        if (argbBuf.size != w * h || bmp == null || bmp.width != w || bmp.height != h) {
            argbBuf = IntArray(w * h)
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            frameBmp = bmp
            decodeRect(f, 0, 0, w, h)
            main.post {
                bmp.setPixels(argbBuf, 0, w, 0, 0, w, h)
                frameWidth = w
                frameHeight = h
                frameImage = bmp.asImageBitmap()
                framesSeen++
            }
            return
        }
        // Clamp the dirty rect; fall back to full on nonsense.
        val dx = f.dx.coerceIn(0, w - 1)
        val dy = f.dy.coerceIn(0, h - 1)
        val dw = f.dw.coerceAtLeast(0).coerceAtMost(w - dx)
        val dh = f.dh.coerceAtLeast(0).coerceAtMost(h - dy)
        if (dw <= 0 || dh <= 0) {
            decodeRect(f, 0, 0, w, h)
            main.post {
                bmp.setPixels(argbBuf, 0, w, 0, 0, w, h)
                frameWidth = w
                frameHeight = h
                frameImage = bmp.asImageBitmap()
                framesSeen++
            }
            return
        }
        decodeRect(f, dx, dy, dw, dh)
        main.post {
            bmp.setPixels(argbBuf, dy * w + dx, w, dx, dy, dw, dh)
            frameWidth = w
            frameHeight = h
            frameImage = bmp.asImageBitmap()
            framesSeen++
        }
    }

    /** R,G,B,unused bytes → opaque ARGB ints, over [dx, dx+dw) × [dy, dy+dh). */
    private fun decodeRect(f: PendingFrame, dx: Int, dy: Int, dw: Int, dh: Int) {
        val px = f.pixels
        val stride = f.stride
        val w = f.width
        for (y in dy until dy + dh) {
            val row = y * stride
            var i = y * w + dx
            for (x in dx until dx + dw) {
                val o = row + x * 4
                if (o + 3 >= px.size) break
                val r = px[o].toInt() and 0xff
                val g = px[o + 1].toInt() and 0xff
                val b = px[o + 2].toInt() and 0xff
                argbBuf[i++] = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
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
