package art.kquinn.kuetv

import android.app.Application
import android.os.Build
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KueTV's app as a whole. It is the VPN engine's own start-up (which sets up its notices, and the link
 * from the VPN, running in a process of its own, to KueTV's screens), plus one thing of KueTV's: if any
 * part of the app stops with an error, in either process, what went wrong is written into the Report
 * before Android closes it, so it can be sent and the cause found.
 */
class KueApp : ICSOpenVPNApplication() {
    override fun onCreate() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        super.onCreate()
        // Straight after an update KueTV handed over: open it before anything else (Kevin, 25 November 2026).
        runCatching {
            if (Report.file == null) Report.keepIn(java.io.File(filesDir, "report.txt"))
            // Only KueTV's own process (the VPN runs in a second one, which starts this too).
            val main = android.os.Build.VERSION.SDK_INT < 28 || android.app.Application.getProcessName() == packageName
            if (main) Reopen.onStart(this)
        }
        KueDns.load(this)
    }

    private var lastMemoryNote = 0L

    /**
     * Android saying the TV is short of memory. Android can close KueTV for this without any error of KueTV's, so
     * nothing else would be written: this line is what shows it happened.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastMemoryNote < 30_000) return
        lastMemoryNote = now
        val words = when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "KueTV is next to be closed"
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "KueTV is in the list to be closed"
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "KueTV is behind other apps"
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "very short while KueTV is in front"
            else -> "short while KueTV is in front"
        }
        runCatching { Report.note("Memory", "the TV is short of memory ($words, level $level); " + Memory.line(this)) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Report.note("Memory", "the TV is out of memory; " + Memory.line(this)) }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            .lines().filter { it.isNotBlank() }.take(40)
        val process = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else "?"
        val stamp = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK).format(Date())
        // Straight to the file: in the VPN's own process the Report has not been started.
        val lines = listOf("$stamp  Crash: KueTV stopped (process $process, thread ${thread.name})") +
            trace.map { "$stamp  Crash: " + Report.clean(it) }
        File(filesDir, "report.txt").appendText(lines.joinToString("") { it + "\n" })
    }
}

/** The TV's memory in a line: how much is free, of how much, and how much KueTV itself is using. */
object Memory {
    fun line(context: android.content.Context): String {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val mine = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val native = android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576
        return "free " + info.availMem / 1_048_576 + " MB of " + info.totalMem / 1_048_576 + " MB" +
            (if (info.lowMemory) " (Android counts this as low)" else "") +
            "; KueTV using " + mine + " MB, plus " + native + " MB for video and sound, allowed " + rt.maxMemory() / 1_048_576 + " MB"
    }
}
