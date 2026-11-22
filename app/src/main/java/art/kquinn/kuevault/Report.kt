package art.kquinn.kuevault

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened, kept on the phone as short lines, for "Copy report for Claude" in Settings. Holds no file
 * contents and no passwords: only what KueVault did and what came back.
 */
object Report {
    private val lines = ArrayList<String>()
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "report.txt")
        synchronized(lines) {
            lines.clear()
            runCatching { lines.addAll(file!!.readLines().takeLast(300)) }
        }
    }

    fun note(text: String) = synchronized(lines) {
        val line = SimpleDateFormat("d MMM HH:mm:ss", Locale.UK).format(Date()) + "  " + text
        lines += line
        while (lines.size > 300) lines.removeAt(0)
        runCatching { file?.writeText(lines.joinToString("\n")) }
    }

    fun text(context: Context): String = synchronized(lines) {
        "KueVault " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
            "Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n" +
            "Accounts: " + Vault.accounts.joinToString { Vault.hidden(it.email) + " for " + it.section.title } + "\n\n" +
            lines.takeLast(150).joinToString("\n")
    }
}
