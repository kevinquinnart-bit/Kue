package art.kquinn.kuevault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.CacheControl
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Checking for a newer KueVault and putting it on.
 *
 * Every build publishes a small file, version.txt, beside the app on the same download. It holds the
 * build's number and its version, such as "code=33" and "name=0.1.33". The app reads it each time it
 * comes to the front. A higher number than its own means a newer app is waiting. Updating downloads
 * the app into KueVault' own space and hands it to the device's installer, which asks before it installs.
 */
object Updates {
    private const val BASE = "https://github.com/kevinquinnart-bit/Kue/releases/download/kuevault/"
    const val VERSION_URL = BASE + "version.txt"
    const val APP_URL = BASE + "KueVault.apk"

    /** A version on the download: its build number (always going up) and the name people see. */
    data class Latest(val code: Int, val name: String)

    /** Reads version.txt. Null when it doesn't hold a build number. */
    fun parse(text: String): Latest? {
        val pairs = text.lines().mapNotNull { line ->
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }.toMap()
        val code = pairs["code"]?.toIntOrNull() ?: return null
        return Latest(code, pairs["name"].orEmpty().ifBlank { code.toString() })
    }

    /** Whether [latest] is newer than the build [mine] that is running. */
    fun isNewer(latest: Latest, mine: Int): Boolean = latest.code > mine

    /** A newer version found on opening, still waiting to be put on or put off. */
    var offer by mutableStateOf<Latest?>(null)

    /** The version put off with "Not now"; it isn't offered again until the app is next started from cold. */
    var putOff: Int = 0

    private val http by lazy { Drive.http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** When the download was last asked, and what it said: Update pressed soon after doesn't ask again. */
    @Volatile private var lastChecked = 0L
    @Volatile private var lastSeen: Latest? = null

    /** What is on the download now. Call off the main thread; throws when it can't be reached. */
    fun check(): Latest {
        val request = Request.Builder().url(VERSION_URL).cacheControl(CacheControl.FORCE_NETWORK).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("the download answered " + response.code)
            val latest = parse(response.body.string()) ?: throw java.io.IOException("the version file had no build number")
            lastChecked = android.os.SystemClock.elapsedRealtime()
            lastSeen = latest
            return latest
        }
    }

    /** On opening: offer a newer version, unless that one was put off. Quiet when nothing can be reached. */
    fun checkOnOpen() {
        val latest = runCatching { check() }.getOrElse { return }
        if (isNewer(latest, BuildConfig.VERSION_CODE) && latest.code != putOff) offer = latest
    }

    private fun folder(context: Context) = File(context.cacheDir, "update").apply { mkdirs() }

    /** Downloads the newest app, telling [progress] how far it has got, 0 to 100. Call off the main thread. */
    fun download(context: Context, progress: (Int) -> Unit, url: String = APP_URL): File {
        val target = File(folder(context), "KueVault.apk")
        val part = File(folder(context), "KueVault.apk.part")
        val big = Drive.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
        big.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("the download answered " + response.code)
            val total = response.body.contentLength()
            response.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val now = (done * 100 / total).toInt()
                            if (now != shown) { shown = now; progress(now) }
                        }
                    }
                }
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw java.io.IOException("the downloaded app couldn't be kept")
        return target
    }

    /**
     * The build number inside a downloaded app, read the way the installer reads it. Null when the file
     * isn't a whole, readable app (cut short, or something other than an app).
     */
    fun buildInside(context: Context, file: File): Long? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrNull()

    /**
     * Downloads the newest app and checks it before the installer sees it: it must be a whole app with a
     * newer build number than the one running. If not, it downloads once more. Throws when neither try
     * gives a newer app.
     */
    fun downloadChecked(context: Context, progress: (Int) -> Unit): File {
        // This phone's own half of the app first (the parts for other kinds of chip left out), else the whole app.
        val half: String? = KueUpdate.ownHalf(context, APP_URL)
        repeat(2) { attempt ->
            val url = if (attempt == 0 && half != null) half else APP_URL
            val file = runCatching { download(context, progress, url) }.getOrElse { if (attempt == 0) return@repeat else throw it }
            val inside = buildInside(context, file)
            if (inside != null && inside > BuildConfig.VERSION_CODE) return file
            file.delete()
        }
        throw java.io.IOException("the download wasn't a newer, whole app, twice")
    }

    /** Whether the device lets KueVault put apps on. Fire TV and phones ask for this once, in their own settings. */
    fun mayInstall(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the device's own page for letting KueVault put apps on. False when the device has no such page. */
    fun openInstallPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    /** Hands the downloaded app to the device's installer, which asks before it installs over this one. */
    fun install(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Clears a downloaded app once the newer version is the one running. */
    fun tidy(context: Context) {
        runCatching { File(context.cacheDir, "update").deleteRecursively() }
        KueUpdate.tidy(context)
        inInstaller = 0
    }

    // ---------------------------------------------------------------------------------------------
    // Updating by itself
    //
    // Android lets an app put its own updates on without asking only when the app itself put the
    // version that's running on (it is then the app's "installer"), on Android 12 and later. The very
    // first time, KueVault was put on by the browser, so that one update asks once. From then on each
    // new version is downloaded quietly and goes on the next time KueVault is left (Home or another
    // app), never while a file is being moved.

    /** A newer version downloaded and checked, kept in case writing it into the installer ahead didn't work. */
    var ready by mutableStateOf<File?>(null)

    /** The build already written into Android's installer, waiting to go on (0 when none). See KueUpdate. */
    var inInstaller by mutableStateOf(0)
        private set

    /** What the update is doing, for the screen. Empty when nothing is happening. */
    var doing by mutableStateOf("")

    @Volatile private var fetching = false

    /** Whether updates can now go on without asking. */
    fun quiet(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !mayInstall(context)) return false
        val installer = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }.getOrNull()
        return installer == context.packageName
    }

    /**
     * Downloads build [latest] and writes it straight into Android's installer, out of sight, so putting it on
     * later takes no time (Kevin, 25 November 2026: updates as quick as they can be, in all the apps). The
     * downloaded copy is kept only if that writing didn't work. Call off the main thread.
     */
    private fun fetch(context: Context, latest: Latest, progress: (Int) -> Unit = {}): Boolean {
        if (inInstaller >= latest.code) return true
        val kept = ready?.takeIf { it.exists() && (buildInside(context, it) ?: 0) >= latest.code }
        val file = kept ?: runCatching { downloadChecked(context, progress) }.getOrNull() ?: return false
        val build = buildInside(context, file)?.toInt() ?: latest.code
        val written = runCatching { KueUpdate.prepare(context, file, build) }.isSuccess
        if (written) {
            inInstaller = build
            file.delete()
            ready = null
        } else ready = file
        return true
    }

    /**
     * On opening: when a newer version is out, downloads it quietly and writes it into the installer, ready for
     * when KueVault is left (or, before updates go on by themselves, for one tap on the offer). Call off the main thread.
     */
    fun checkAndFetch(context: Context) {
        if (BuildConfig.STORE) return
        val latest = runCatching { check() }.getOrNull() ?: return
        inInstaller = KueUpdate.waiting(context)
        if (!isNewer(latest, BuildConfig.VERSION_CODE)) {
            tidy(context)
            return
        }
        val asks = !quiet(context)
        if (asks && latest.code == putOff) return
        if (!fetching) {
            fetching = true
            if (!asks) doing = "Getting KueVault " + latest.name
            try { fetch(context, latest) } finally { fetching = false; doing = "" }
        }
        if (asks) offer = latest
    }

    /**
     * Update pressed (the offer, or Settings › Check for updates): puts the newest version on now, at once when it
     * is already in the installer. [say] is told how it is going. Gives back the last word.
     */
    suspend fun updateByHand(context: Context, say: (String) -> Unit): String {
        if (BuildConfig.STORE) return "Google Play keeps KueVault up to date."
        // Asked within the last 5 minutes: not asked again.
        val fresh = lastSeen?.takeIf { android.os.SystemClock.elapsedRealtime() - lastChecked < 5 * 60_000L }
        if (fresh == null) say("Checking…")
        val latest = fresh ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { check() }.getOrNull() }
            ?: return "Couldn't reach the download. Check the internet and try again."
        if (!isNewer(latest, BuildConfig.VERSION_CODE)) return "You have the newest version."
        if (!mayInstall(context)) {
            openInstallPermission(context)
            return "Version " + latest.name + " is ready. First allow KueVault to install updates on the page that opened, then tap Check for updates again."
        }
        offer = null
        if (inInstaller < latest.code) {
            say("Downloading version " + latest.name + " · 0%")
            val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                fetch(context, latest) { p -> say("Downloading version " + latest.name + " · $p%") }
            }
            if (!got) return "The download didn't work. Try again later."
        }
        say("Putting version " + latest.name + " on…")
        Reopen.handed(context, latest)
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { putOn(context) }
        if (!ok) return "The update didn't go on. Try again later."
        return "Putting version " + latest.name + " on. Android may ask first. KueVault closes while it goes on, then a notice opens it again."
    }

    /** Puts on what is waiting: from the installer when it is there, otherwise from the kept download. */
    private fun putOn(context: Context): Boolean {
        if (runCatching { KueUpdate.putOn(context, UpdateReceiver::class.java) }.getOrDefault(false)) {
            inInstaller = 0
            return true
        }
        val file = ready?.takeIf { it.exists() } ?: return false
        ready = null
        return runCatching { installSession(context, file) }.isSuccess || runCatching { install(context, file) }.isSuccess
    }

    /** KueVault has been left: a waiting update goes on now, away from the screen. */
    fun installIfReady(context: Context) {
        if (BuildConfig.STORE) return
        if (inInstaller <= 0 && ready == null) return
        if (!quiet(context)) return
        val app = context.applicationContext
        Thread({ putOn(app) }, "update").start()
    }

    /**
     * Puts the downloaded app on through Android's own installer. It goes on without a question when
     * Android allows that; otherwise Android shows its usual "Update this app?" question.
     */
    fun installSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("KueVault.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val done = android.app.PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }
}

/** Hears back from Android's installer: shows its question when it has one, and tidies up afterwards. */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        if (status == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val ask = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { context.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            // Tried again the next time KueVault opens.
            Report.note("update didn't go on: " + intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}

/**
 * After an update pressed by hand: KueVault closes while it goes on, and the phone doesn't let an app open itself
 * again, so a notice says it is on; a tap opens KueVault (Kevin, 25 November 2026). Only within 10 minutes of Update.
 */
object Reopen {
    private const val WITHIN_MS = 10 * 60_000L

    fun handed(context: Context, latest: Updates.Latest) {
        context.getSharedPreferences("updates-reopen", Context.MODE_PRIVATE).edit()
            .putLong("at", System.currentTimeMillis()).putInt("build", latest.code).putString("name", latest.name).apply()
    }

    fun afterUpdate(context: Context) {
        val prefs = context.getSharedPreferences("updates-reopen", Context.MODE_PRIVATE)
        val at = prefs.getLong("at", 0L)
        val name = prefs.getString("name", null) ?: BuildConfig.VERSION_NAME
        if (BuildConfig.VERSION_CODE >= prefs.getInt("build", 0)) prefs.edit().clear().apply()
        if (at == 0L || System.currentTimeMillis() - at > WITHIN_MS) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(android.app.NotificationChannel("updates", "Updates", android.app.NotificationManager.IMPORTANCE_HIGH))
        }
        val open = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = androidx.core.app.NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.kue_status)
            .setContentTitle("KueVault " + name + " is on")
            .setContentText("Tap to open KueVault.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(WITHIN_MS)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(4242, notice) }.onFailure { Report.note("update on, but the notice couldn't show: " + it.message) }
    }
}

/** Android saying a newer KueVault is now on. */
class UpdatedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Reopen.afterUpdate(context.applicationContext)
    }
}
