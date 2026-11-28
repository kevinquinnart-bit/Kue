package art.kquinn.kuesounds

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** What the sign-in screen shows under its button: empty, or why the last try failed. */
    private var signInProblem by mutableStateOf("")
    private var signingIn by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)

    private val googlePage = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        signingIn = false
        when (val r = Google.finish(this, result.data)) {
            is Google.Start.Ready -> afterSignIn()
            is Google.Start.Failed -> signInProblem = r.message
            is Google.Start.ShowGoogle -> signInProblem = "Sign-in didn't finish. Try again."
        }
    }

    /** What to do once the person has allowed KueSounds to edit the music files. */
    private var afterEditAllowed: (() -> Unit)? = null

    private val editPage = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        when (val r = Google.finishEdit(this, result.data)) {
            is Google.Start.Ready -> afterEditAllowed?.invoke()
            is Google.Start.Failed -> Fixer.permissionProblem = r.message
            is Google.Start.ShowGoogle -> Fixer.permissionProblem = "Permission wasn't given. Try again."
        }
        afterEditAllowed = null
    }

    private val noticesPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { readNotices() }

    /** Whether notices are allowed: on Android 13 and later only once the person says yes. */
    private fun readNotices() {
        Fixer.noticesOn = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /** Asks Google for permission to edit the music files, then does [then]. */
    private fun askEdit(then: () -> Unit) {
        Fixer.permissionProblem = ""
        afterEditAllowed = then
        lifecycleScope.launch {
            when (val r = Google.startEdit(this@MainActivity)) {
                is Google.Start.Ready -> { afterEditAllowed = null; then() }
                is Google.Start.ShowGoogle -> editPage.launch(IntentSenderRequest.Builder(r.intent.intentSender).build())
                is Google.Start.Failed -> { afterEditAllowed = null; Fixer.permissionProblem = r.message }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Google.init(this)
        KueSettings.init(this)
        Scanner.init(this)
        CrashLog.install(this)
        Offline.init(this)
        Sync.init(this)
        Podcasts.init(this)
        Sound.init(this)
        Views.init(this)
        Lyrics.init(this)
        PlayPrefs.init(this)
        Smart.init(this)
        SoundMatch.init(this)
        AiSort.init(this)
        Listen.init(this)
        Backup.init(this)
        SpotifyLink.init(this)
        takeShared(intent)
        Playing.connect(this)
        Fixer.askPermission = ::askEdit
        // Settings › Notifications › Turn on: Android's question the first time; after that (Android won't
        // ask twice), the phone's own notification settings for KueSounds.
        Fixer.askNotices = {
            val prefs = getSharedPreferences("notices", MODE_PRIVATE)
            val granted = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted && !prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                noticesPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                runCatching {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
                    )
                }
            }
        }
        signedIn = Google.signedIn
        // Unfinished: song details or album lookups still to do, or (on Wi-Fi) singles still to look for.
        val wifi = Net.unmetered(this)
        val unfinished = Scanner.data.tracks.any { !it.tagged || !it.checked || (wifi && !it.singleChecked) }
        if (signedIn && Net.online(this) && (Scanner.data.tracks.isEmpty() || unfinished || System.currentTimeMillis() - Scanner.data.scannedAt > 6 * 60 * 60 * 1000L)) {
            Scanner.scan()
        }
        // The daily backup to the Drive, a minute after opening, out of the way of everything else.
        if (signedIn) Thread({
            Thread.sleep(60_000)
            kotlinx.coroutines.runBlocking { Backup.ifDue() }
        }, "kuesounds-backup").start()
        setContent {
            Ui.App(
                // Google can take a sign-in back (a changed password, access removed): then it's asked for again.
                signedIn = signedIn && !Scanner.signedOut,
                signingIn = signingIn,
                signInProblem = signInProblem,
                onSignIn = ::signIn,
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** A link shared to KueSounds (YouTube, YouTube Music or Spotify's Share button). */
    private fun takeShared(intent: android.content.Intent?) {
        // A picture or a link shared while Fix it by hand is open becomes that song's cover (Kevin, 27 November 2026).
        if (intent?.action == android.content.Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: return
            if (FixByHandUi.isOpen) FixByHandUi.sharedPicture = uri
            else android.widget.Toast.makeText(this, "Open Choose the cover or Fix it by hand first, then share the picture to KueSounds.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (intent?.action == android.content.Intent.ACTION_SEND && FixByHandUi.isOpen) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { FixByHandUi.sharedLink = it; return }
        }
        val text = when (intent?.action) {
            android.content.Intent.ACTION_SEND -> intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            android.content.Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        Ui.sharedLink = text
    }

    override fun onResume() {
        super.onResume()
        // Kept upright when chosen in Settings (CloudPlayer's "portrait lock").
        // Once: let KueSounds keep finding and fixing music with the phone locked.
        if (Scanner.running || Fixer.running) KueApp.askOnce(this)
        requestedOrientation = if (PlayPrefs.portraitLock) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        readNotices()
        if (signedIn) Sync.later(1000)
        lifecycleScope.launch(Dispatchers.IO) { Updates.checkAndFetch(applicationContext) }
    }

    /** Leaving KueSounds (Home or another app): a downloaded update goes on now, unless music is playing. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Updates.installIfReady(applicationContext)
    }

    private fun signIn() {
        if (signingIn) return
        signingIn = true
        signInProblem = ""
        lifecycleScope.launch {
            when (val r = Google.start(this@MainActivity)) {
                is Google.Start.Ready -> {
                    signingIn = false
                    afterSignIn()
                }
                is Google.Start.ShowGoogle -> googlePage.launch(IntentSenderRequest.Builder(r.intent.intentSender).build())
                is Google.Start.Failed -> {
                    signingIn = false
                    signInProblem = r.message
                }
            }
        }
    }

    private fun afterSignIn() {
        signedIn = true
        Scanner.signedOut = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { Google.keepEmail(Drive.whoAmI()) } }
            Scanner.scan()
        }
    }
}
