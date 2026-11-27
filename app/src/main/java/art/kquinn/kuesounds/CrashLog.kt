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
}
