package art.kquinn.kuesounds

import android.content.Context
import java.io.File

/**
 * When KueSounds closes on its own (Kevin, 27 November 2026: "all crashed"), what went wrong is written down, and put in
 * the report the next time it opens, so the cause can be found rather than guessed.
 */
object CrashLog {
    @Volatile private var installed = false

    fun install(context: Context) {
        val app = context.applicationContext
        val file = File(app.filesDir, "last-crash.txt")
        if (file.exists()) {
            runCatching {
                val text = file.readText()
                Scanner.note("KueSounds closed unexpectedly last time: " + text.lines().take(18).joinToString(" | "))
                file.renameTo(File(app.filesDir, "last-crash-seen.txt"))
            }
        }
        // Read away from the screen's thread: Android's record can be large.
        Thread { freezes(app) }.apply { isDaemon = true }.start()
        if (installed) return
        installed = true
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = java.io.StringWriter().also { e.printStackTrace(java.io.PrintWriter(it)) }.toString()
                file.writeText(
                    java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK).format(java.util.Date()) + " · KueSounds " + BuildConfig.VERSION_NAME +
                        " · " + thread.name + "\n" + trace.lines().take(40).joinToString("\n"),
                )
            }
            before?.uncaughtException(thread, e)
        }
    }

    /**
     * A freeze ("KueSounds isn't responding", Kevin 28 November 2026) isn't a crash, so nothing above sees it. Android
     * keeps a record of it with what the app was doing at that moment; each one not seen before goes into the report:
     * the screen's own thread (where a freeze happens), the part that names KueSounds' own code.
     */
    private fun freezes(app: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = app.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val seen = prefs.getLong("anrSeen", 0L)
            val am = app.getSystemService(android.app.ActivityManager::class.java)
            val anrs = am.getHistoricalProcessExitReasons(app.packageName, 0, 16)
                .filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR && it.timestamp > seen }
                .sortedBy { it.timestamp }
            if (anrs.isEmpty()) return
            for (x in anrs.takeLast(3)) {
                val text = runCatching { x.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val main = mainThread(text)
                Scanner.note(
                    "KueSounds froze at " + java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.UK).format(java.util.Date(x.timestamp)) +
                        " (" + x.description.orEmpty() + "): " + main.ifBlank { "no record of what it was doing" },
                )
            }
            prefs.edit().putLong("anrSeen", anrs.last().timestamp).apply()
        }
    }

    /** From Android's record of a freeze: the main thread's lines, KueSounds' own first, at most 30. */
    fun mainThread(trace: String): String {
        val start = trace.indexOf("\"main\"")
        if (start < 0) return ""
        val end = trace.indexOf("\n\n", start).let { if (it < 0) trace.length else it }
        val lines = trace.substring(start, end).lines().map { it.trim() }
        val first = lines.take(3)
        val frames = lines.filter { it.startsWith("at ") || it.startsWith("- waiting") || it.startsWith("- locked") || it.startsWith("- blocked") }
        val ours = frames.indexOfFirst { "art.kquinn" in it }
        val keep = if (ours >= 0) frames.subList(maxOf(0, ours - 6), minOf(frames.size, ours + 14)) else frames.take(20)
        return (first + keep).joinToString(" | ").take(4000)
    }
}
