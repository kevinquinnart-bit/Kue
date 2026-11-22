package art.kquinn.kuetv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The TV's own aerial: the digital channels from the aerial lead plugged into the TV.
 *
 * Android only lets the TV's own TV app show the aerial's picture: an app like KueTV can't play it in its own
 * screen or read its channel list. So KueTV opens the TV's own aerial viewer, from Home, from Settings › This TV,
 * and by itself when the home internet goes down. Home or Back there brings KueTV back as usual.
 */
object Aerial {
    var changes by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("aerial", Context.MODE_PRIVATE)

    /** The TV's built-in tuner, or null when this device has none (a phone, a Fire TV stick). */
    fun tuner(context: Context): TvInputInfo? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) return null
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return null
        val inputs = runCatching { tim.tvInputList }.getOrDefault(emptyList())
        // The TV maker's own tuner, not one an app (ITVX, Pluto TV) has added for its internet channels.
        val tuners = inputs.filter { it.type == TvInputInfo.TYPE_TUNER && !it.isPassthroughInput }
        return tuners.firstOrNull { isSystem(it) } ?: tuners.firstOrNull { it.serviceInfo.packageName.startsWith("com.amazon") }
    }

    private fun isSystem(info: TvInputInfo): Boolean =
        (info.serviceInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

    /** Every input the TV lists (its tuner, HDMI sockets, apps' channels), for the report. */
    fun describe(context: Context): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)) return "no live TV on this device"
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return "no TV input service"
        val inputs = runCatching { tim.tvInputList }.getOrDefault(emptyList())
        if (inputs.isEmpty()) return "no inputs listed"
        return inputs.joinToString("; ") { i -> i.id + " type " + i.type + (if (i.isPassthroughInput) " passthrough" else "") + (if (isSystem(i)) " system" else "") }
    }

    /** Whether this TV has an aerial tuner KueTV can hand over to. */
    fun available(context: Context): Boolean = tuner(context) != null

    /** The tuner's name as the TV gives it, for Settings: "Antenna", "Live TV". */
    fun name(context: Context): String? = tuner(context)?.let { runCatching { it.loadLabel(context).toString() }.getOrNull() }

    /**
     * Opens the TV's own aerial viewer. The ways a TV can be asked are tried in turn, best first; the first one
     * the TV takes is remembered and tried first next time. False when none worked.
     */
    fun open(context: Context): Boolean {
        val tuner = tuner(context) ?: return false
        val ways = ways(context, tuner)
        val first = prefs(context).getString("way", "").orEmpty()
        for ((name, intent) in ways.sortedBy { if (it.first == first) 0 else 1 }) {
            val ok = runCatching {
                HomeGuard.pause()
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) {
                Report.note("Aerial", "opened the TV's aerial viewer ($name, tuner " + tuner.id + ")")
                if (name != first) prefs(context).edit().putString("way", name).apply()
                return true
            }
        }
        Report.note("Aerial", "the TV's aerial viewer wouldn't open (tuner " + tuner.id + ", tried " + ways.joinToString { it.first } + ")")
        return false
    }

    /** The ways of asking the TV to show its aerial channels, best first. Only ways that lead outside KueTV. */
    private fun ways(context: Context, tuner: TvInputInfo): List<Pair<String, Intent>> {
        val pm = context.packageManager
        val list = listOf(
            // Android's own way: show this input's channels.
            "input channels" to Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner.id)),
            // Show live TV channels, whichever input.
            "all channels" to Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI),
            // The tuner's own app.
            "tuner app" to (pm.getLeanbackLaunchIntentForPackage(tuner.serviceInfo.packageName) ?: pm.getLaunchIntentForPackage(tuner.serviceInfo.packageName)),
        )
        return list.mapNotNull { (name, intent) ->
            intent ?: return@mapNotNull null
            // Never KueTV itself.
            val to = runCatching { pm.resolveActivity(intent, 0)?.activityInfo?.packageName }.getOrNull()
            if (to == context.packageName) null else name to intent
        }
    }

    /**
     * The aerial's channels on Kevin's TV, by their number in Freely's guide (Freely's "backup channels", which come
     * from the aerial and start at 233). Read from his photos of Freely's guide, 22 November 2026; Freely's own lower
     * numbers come over the internet. BBC One, BBC Two and Channel 5 aren't on his aerial list. A rescan in Freely may
     * renumber them.
     */
    private val numbers: Map<String, Int> = mapOf(
        "itv" to 233, "itv1" to 233, "itv1hd" to 233, "itvhd" to 233, "utv" to 233,
        "itv2" to 238, "itv3" to 239, "itv4" to 240, "itvquiz" to 241, "itvbe" to 241,
        "channel4" to 242, "c4" to 242, "ch4" to 242, "film4" to 243, "more4" to 244, "4seven" to 245,
        "5star" to 246, "channel5star" to 246, "5action" to 247, "5usa" to 248, "5drama" to 249,
        "itv1+1" to 251, "itv+1" to 251, "channel4+1" to 252, "c4+1" to 252, "4+1" to 252,
        "5+1" to 253, "channel5+1" to 253, "itv2+1" to 254, "itv3+1" to 255, "film4+1" to 256, "more4+1" to 257,
        "quest+1" to 258, "udavejavu" to 259, "davejavu" to 259, "udrama+1" to 260, "drama+1" to 260,
        "itv4+1" to 261, "tlc+1" to 262, "greattv+1" to 263, "blaze+1" to 264, "greatplayer" to 265,
        "togethertv+1" to 266,
        "bbcnews" to 301, "bbcparliament" to 302, "skynews" to 303, "gbnews" to 304, "cnn" to 305,
        "cnninternational" to 305, "cnnheadlines" to 305, "france24" to 306, "france24english" to 306,
        "cbbc" to 351, "cbeebies" to 352, "pop" to 353, "popmax" to 354, "popup" to 355,
        "ketchuptv" to 356, "ketchuptoo" to 357, "shaked" to 360,
    )

    /** A channel's name as the provider writes it, made comparable: "UK: ITV 2 +1 HD" is "itv2+1". */
    internal fun key(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("^[a-z]{2,3}\\s*[:|]\\s*"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("\\b(hd|fhd|uhd|4k|sd|uk|london|england|raw)\\b"), " ")
            .replace(Regex("\\bplus\\s*1\\b"), "+1")
            .replace(Regex("[^a-z0-9+]"), "")

    /** The aerial channel number in Freely for this channel, or null when the aerial doesn't carry it. */
    fun numberFor(name: String): Int? = numbers[key(name)]

    /**
     * Opens Freely on the aerial channel [number], through Fire TV's own TV player (tried on Kevin's TV,
     * 22 November 2026: 233 played ITV1 HD from the aerial with a full signal). False when it wouldn't open.
     */
    fun openChannel(context: Context, number: Int): Boolean {
        val uri = android.net.Uri.parse("content://android.media.tv/displayNumber/$number")
        val fire = Intent(Intent.ACTION_VIEW, uri).setClassName("com.amazon.tv.livetv", "com.amazon.tv.livetv.TvChannelsPlayerActivityAlias")
        for (intent in listOf(fire, Intent(Intent.ACTION_VIEW, uri))) {
            val ok = runCatching {
                HomeGuard.pause()
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) {
                Report.note("Aerial", "opened aerial channel $number")
                return true
            }
        }
        Report.note("Aerial", "aerial channel $number wouldn't open")
        return false
    }

    /** Whether to offer the aerial when the home internet goes down. On unless switched off. */
    fun offerWhenOffline(context: Context): Boolean = prefs(context).getBoolean("offer", true)
    fun offerWhenOffline(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("offer", on).apply()
        changes++
    }

    /** The pop-up offering the aerial, while the internet is down. */
    var offering by mutableStateOf(false)
}

/** Whether the TV or phone can reach the internet, watched for as long as KueTV runs. */
object NetWatch {
    /** False once the network has gone or stopped reaching the internet. */
    var online by mutableStateOf(true)
        private set

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        started = true
        online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let { reaches(it) } ?: false
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { set(reaches(caps)) }
                override fun onLost(network: Network) { set(false) }
                override fun onUnavailable() { set(false) }
            })
        }.onFailure { Report.note("Internet", "can't watch the connection: " + it.message) }
    }

    private fun set(now: Boolean) {
        if (now == online) return
        Report.note("Internet", if (now) "back" else "gone")
        // Compose state is changed on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post { online = now }
    }

    private fun reaches(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
