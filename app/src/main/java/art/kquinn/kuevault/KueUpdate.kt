package art.kquinn.kuevault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Shared by the three Kue apps, the same in each bar the first line (v1, 25 November 2026).
 *
 * Making an update quick to put on (Kevin, 25 November 2026: Install left him waiting on the update screen, then
 * on the TV's home screen before KueTV came back):
 * - the update is written into Android's installer as soon as it has downloaded, out of sight, so pressing
 *   Install only has to say "go" (on the TV that writing took 2 to 6 seconds with nothing happening);
 * - only this device's own half of the app is downloaded (TVs and phones use different chips: the whole app
 *   carries the parts for both).
 */
object KueUpdate {
    private fun prefs(context: Context) = context.getSharedPreferences("kue-update", Context.MODE_PRIVATE)

    /** The kind of chip this app's parts were put on for: "arm64-v8a" or "armeabi-v7a". Null when it can't be told. */
    fun chip(context: Context): String? = when (File(context.applicationInfo.nativeLibraryDir ?: "").name) {
        "arm64" -> "arm64-v8a"
        "arm" -> "armeabi-v7a"
        else -> Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
    }

    /** The download of only this device's half of the app (".../KueTV.apk" gives ".../KueTV-armeabi-v7a.apk"). */
    fun ownHalf(context: Context, whole: String): String? = chip(context)?.let { whole.removeSuffix(".apk") + "-" + it + ".apk" }

    /**
     * Writes [file] (build [build]) into Android's installer, not yet put on. Gives back true once it is there.
     * A different build waiting there is taken out first. Call off the main thread.
     */
    fun prepare(context: Context, file: File, build: Int): Boolean {
        if (waiting(context) >= build) return true
        val installer = context.packageManager.packageInstaller
        dropWaiting(context)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Android 12 and later: no question when this app put the running version on itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("update.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        prefs(context).edit().putInt("session", id).putInt("build", build).apply()
        return true
    }

    /** The build written into the installer and waiting to be put on; 0 when there is none (or Android has let it go). */
    fun waiting(context: Context): Int {
        val p = prefs(context)
        val id = p.getInt("session", -1)
        if (id < 0) return 0
        val there = runCatching { context.packageManager.packageInstaller.getSessionInfo(id) }.getOrNull()
        if (there == null) {
            p.edit().remove("session").remove("build").apply()
            return 0
        }
        return p.getInt("build", 0)
    }

    /**
     * Puts on the build waiting in the installer: Android asks (when it must) and answers [answer], the app's own
     * receiver. False when nothing is waiting. Quick: the app is already there.
     */
    fun putOn(context: Context, answer: Class<*>): Boolean {
        if (waiting(context) <= 0) return false
        val p = prefs(context)
        val id = p.getInt("session", -1)
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
        val done = android.app.PendingIntent.getBroadcast(context, id, Intent(context, answer), flags)
        context.packageManager.packageInstaller.openSession(id).use { it.commit(done.intentSender) }
        p.edit().remove("session").remove("build").apply()
        return true
    }

    /** Takes out an update written into the installer and not yet put on. */
    fun dropWaiting(context: Context) {
        val p = prefs(context)
        val id = p.getInt("session", -1)
        if (id >= 0) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        p.edit().remove("session").remove("build").apply()
    }

    /** The newest version is the one running: anything this app left in the installer goes. */
    fun tidy(context: Context) {
        val installer = context.packageManager.packageInstaller
        runCatching { installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } } }
        prefs(context).edit().remove("session").remove("build").apply()
    }
}
