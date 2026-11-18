package com.crate.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-backed crash/error log for debugging. Android's logcat is gone once
 * the cable unplugs, so: uncaught exceptions from any thread (installed as
 * the default handler, chaining to the previous one) and caught engine
 * errors (boot/kill/native) are appended with timestamps to
 * `filesDir/logs/crate.log`, capped at [MAX_BYTES] by dropping the oldest
 * half. Settings exposes Share (newest tail as text) and Clear.
 *
 * Native segfaults can't be caught in-process and won't appear here.
 */
object CrashLog {
    private const val MAX_BYTES = 512L * 1024L
    private const val TAIL_CHARS = 131072
    private val lock = Any()

    fun install(context: Context) {
        val app = context.applicationContext
        try {
            val pi = app.packageManager.getPackageInfo(app.packageName, 0)
            log(app, "app", "crate ${pi.versionName} started")
        } catch (_: Exception) {
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                log(app, "crash",
                    "uncaught on ${thread.name}: $throwable", throwable)
            } catch (_: Exception) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                throw throwable
            }
        }
    }

    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = buildString {
            append(time).append(' ').append(tag).append(": ").appendLine(message)
            if (throwable != null) {
                append(throwable.stackTraceToString())
                if (!throwable.stackTraceToString().endsWith("\n")) appendLine()
            }
        }
        synchronized(lock) {
            try {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_BYTES) {
                    // Drop the oldest half; keep the file bounded and recent.
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange(bytes.size / 2, bytes.size))
                }
                file.appendText(entry)
            } catch (_: Exception) {
            }
        }
    }

    fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, "logs/crate.log")

    fun tail(context: Context): String {
        return try {
            val text = logFile(context).takeIf { it.exists() }?.readText().orEmpty()
            if (text.length > TAIL_CHARS) text.takeLast(TAIL_CHARS) else text
        } catch (_: Exception) {
            ""
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                logFile(context).delete()
            } catch (_: Exception) {
            }
        }
    }
}
