package com.example

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone, synchronous crash logger — deliberately independent of Room/
 * the app's own repository/DB layer.
 *
 * WHY THIS EXISTS:
 * The app's normal logging goes through V2RayRepository -> Room, which
 * commits asynchronously (via coroutines). If the process dies from an
 * unhandled exception or a native SIGSEGV, that in-flight async commit
 * never lands — so the DB shows nothing except "clean disconnect -> start
 * again", with no trace of the actual crash. This class writes a plain
 * text file with plain java.io, synchronously, on the thread that's
 * already crashing, so the write completes (or clearly fails) before the
 * process actually dies.
 *
 * INSTALL: call CrashLogger.install(this) once, from Application.onCreate().
 */
object CrashLogger {

    private const val LOG_FILE_NAME = "crash_log.txt"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024 // 512 KB cap, oldest content trimmed

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashSynchronously(appContext, thread, throwable)
            } catch (loggingFailure: Throwable) {
                // Never let the logger itself mask the original crash or
                // throw past this handler.
            } finally {
                // Preserve default behavior (e.g. Play Vitals / system crash
                // dialog / process death) by chaining to whatever handler
                // was there before us.
                previousHandler?.uncaughtException(thread, throwable)
                    ?: run {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        kotlin.system.exitProcess(10)
                    }
            }
        }
    }

    private fun writeCrashSynchronously(context: Context, thread: Thread, throwable: Throwable) {
        val logFile = File(context.filesDir, LOG_FILE_NAME)

        val stackTraceWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stackTraceWriter))

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        val entry = buildString {
            appendLine("========================================")
            appendLine("TIME: $timestamp")
            appendLine("THREAD: ${thread.name}")
            appendLine("DEVICE: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("EXCEPTION: ${throwable.javaClass.name}: ${throwable.message}")
            appendLine("STACKTRACE:")
            appendLine(stackTraceWriter.toString())
        }

        // Append, but cap total file size so it can't grow unbounded across
        // many crash-loop iterations. If it's already too big, keep only the
        // tail (most recent) portion before appending the new entry.
        val existing = if (logFile.exists()) logFile.readText() else ""
        val trimmedExisting = if (existing.length > MAX_LOG_SIZE_BYTES) {
            existing.takeLast(MAX_LOG_SIZE_BYTES)
        } else {
            existing
        }

        logFile.writeText(trimmedExisting + entry)
    }

    /**
     * Reads the crash log back, e.g. to show in a Settings screen or to
     * attach to a support email via Intent.ACTION_SEND. Returns null if no
     * crash has been logged yet.
     */
    fun readLog(context: Context): String? {
        val logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
        return if (logFile.exists()) logFile.readText() else null
    }

    /** Clears the crash log, e.g. after the user has reviewed/sent it. */
    fun clearLog(context: Context) {
        val logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
        if (logFile.exists()) {
            logFile.delete()
        }
    }
}
