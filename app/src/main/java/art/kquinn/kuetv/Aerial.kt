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

    /** Something about the aerial changed (its channel list): Settings shows it afresh. */
    fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { changes++ } }

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

    /** A channel's name as the provider writes it, made comparable: "UK: ITV 2 +1 HD" is "itv2+1". */
    internal fun key(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(marks, "").lowercase()
            .replace(tag, "")
            .replace(brackets, " ")
            .replace(versionWords, " ")
            .replace(plusOne, "+1")
            .replace(notName, "")

    // Made once: making them afresh for each name froze the screen when every channel was compared (26 November 2026).
    private val marks = Regex("\\p{Mn}+")
    private val tag = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val brackets = Regex("\\(.*?\\)|\\[.*?]")
    private val versionWords = Regex("\\b(hd|fhd|uhd|4k|sd|uk|london|england|raw)\\b")
    private val plusOne = Regex("\\bplus\\s*1\\b")
    private val notName = Regex("[^a-z0-9+]")

    /** The aerial channel number for this channel in the list in use, or null when the aerial doesn't carry it. */
    fun numberFor(name: String): Int? = key(name).let { k -> AerialLists.own?.numbers?.get(k) ?: AerialLists.current?.numbers?.get(k) }

    /**
     * Opens the aerial channel [number] (in Freely on a Freely TV), through Fire TV's own TV player (tried on Kevin's TV,
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

    /** "On Freely" on a Freely TV (its channels come over the aerial and the internet both), else "On aerial". */
    fun onLabel(): String = if (AerialLists.current?.id == "GB-freely" && AerialLists.own == null) "On Freely" else "On aerial"

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

/**
 * The aerial's channel numbers for each TV platform (UK Freely, UK Freeview, Ireland's Saorview…), from KueTV's own
 * channel file online, fetched once a day so a change reaches every TV without a new app; the copy inside the app
 * covers the first start and no internet. Which list is used: the one picked in Settings › This TV, or by itself
 * from the TV's country, Freely's app being on the TV choosing the Freely list.
 */
object AerialLists {
    const val URL = "https://raw.githubusercontent.com/kevinquinnart-bit/Kue/main/channels/aerial-channels.json"
    private const val DAY_MS = 24 * 3600_000L

    class Channel(val number: Int, val name: String)
    class Plan(val id: String, val label: String, val countries: List<String>, val whenInstalled: String?, val channels: List<Channel>) {
        /** Every name each channel goes by, made comparable, to its number. */
        val numbers: Map<String, Int> = HashMap<String, Int>().also { m ->
            channels.forEach { c -> m.putIfAbsent(Aerial.key(c.name), c.number) }
        }
    }

    @Volatile var plans: List<Plan> = emptyList()
        private set
    @Volatile var current: Plan? = null
        private set
    @Volatile var updated: String = ""
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("aerial", Context.MODE_PRIVATE)
    private fun cache(context: Context) = java.io.File(context.filesDir, "aerial-channels.json")

    /** The list picked in Settings, or "" for the one KueTV picks by itself. */
    fun chosen(context: Context): String = prefs(context).getString("list", "").orEmpty()
    fun choose(context: Context, id: String) {
        prefs(context).edit().putString("list", id).apply()
        current = pick(context, plans)
        Aerial.bump()
    }

    /** Reads the file kept from last time (or the app's own copy), then fetches a fresh one once a day. Off the screen's thread. */
    fun load(context: Context) {
        runCatching { loadOwn(context) }
        val kept = runCatching { cache(context).takeIf { it.exists() }?.readText() }.getOrNull()
        val text = kept ?: runCatching { context.assets.open("aerial-channels.json").bufferedReader().use { it.readText() } }.getOrNull()
        text?.let { use(context, it) }
        val last = prefs(context).getLong("fetched", 0L)
        if (System.currentTimeMillis() - last < DAY_MS && kept != null) return
        runCatching {
            Provider.http.newCall(okhttp3.Request.Builder().url(URL).header("User-Agent", Provider.USER_AGENT).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("answered " + r.code)
                val fresh = r.body.string()
                if (parse(fresh).isEmpty()) throw IllegalStateException("no lists in it")
                cache(context).writeText(fresh)
                prefs(context).edit().putLong("fetched", System.currentTimeMillis()).apply()
                use(context, fresh)
            }
        }.onFailure { Report.note("Aerial", "channel file not fetched: " + it.message) }
    }

    private fun use(context: Context, text: String) {
        val p = parse(text)
        if (p.isEmpty()) return
        plans = p
        updated = runCatching { org.json.JSONObject(text).optString("updated") }.getOrDefault("")
        current = pick(context, p)
        Report.note("Aerial", "channel list: " + (current?.label ?: "none for this TV") + " (file of " + updated + ")")
        Aerial.bump()
    }

    /** The list for this TV: the one picked in Settings; otherwise the first for the TV's country whose app is on it. */
    private fun pick(context: Context, all: List<Plan>): Plan? {
        all.firstOrNull { it.id == chosen(context) }?.let { return it }
        val country = runCatching { context.resources.configuration.locales[0].country }.getOrDefault("").uppercase()
        val pm = context.packageManager
        val installed: (String) -> Boolean = { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
        return pickFor(all, country, installed)
    }

    internal fun pickFor(all: List<Plan>, country: String, installed: (String) -> Boolean): Plan? =
        all.filter { country in it.countries }.firstOrNull { it.whenInstalled == null || installed(it.whenInstalled) }

    internal fun parse(text: String): List<Plan> = runCatching {
        val lists = org.json.JSONObject(text).optJSONArray("lists") ?: return@runCatching emptyList()
        (0 until lists.length()).mapNotNull { i ->
            val o = lists.optJSONObject(i) ?: return@mapNotNull null
            val ch = o.optJSONArray("channels") ?: return@mapNotNull null
            val channels = ArrayList<Channel>()
            for (j in 0 until ch.length()) {
                val c = ch.optJSONObject(j) ?: continue
                val n = c.optInt("n", -1).takeIf { it > 0 } ?: continue
                val names = c.optJSONArray("names") ?: continue
                for (k in 0 until names.length()) channels += Channel(n, names.optString(k))
            }
            val countries = o.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.optString(it).uppercase() } }.orEmpty()
            Plan(o.optString("id"), o.optString("label"), countries, o.optString("whenInstalled").ifBlank { null }, channels)
        }
    }.getOrDefault(emptyList())

    /**
     * The viewer's own aerial numbers, for a country with no list, or to put right a number in the list. Sent from the
     * phone page as lines of "number name", or a link to a file of them (lines like that, KueTV's own channel file, or a
     * channel list with tvg-chno numbers). They come before the list's numbers. A link is read again once a day.
     */
    @Volatile var own: Plan? = null
        private set

    /** The own-numbers screen in Settings › This TV. */
    var ownOpen by mutableStateOf(false)

    fun ownText(context: Context): String = prefs(context).getString("ownText", "").orEmpty()
    fun ownLink(context: Context): String = prefs(context).getString("ownLink", "").orEmpty()

    /** Keeps what was sent and puts it in use; a link is read now. The answer says how many channels came in. Off the screen's thread. */
    fun keepOwn(context: Context, text: String, link: String): String {
        val l = link.trim()
        var fetched = ""
        if (l.isNotEmpty()) {
            fetched = runCatching { fetch(l) }.getOrElse { return "Couldn't read that link: " + (it.message ?: "no answer") + ". Nothing changed." }
            if (ownChannels(fetched).isEmpty()) return "That link had no channel numbers KueTV could read. Nothing changed."
        }
        val t = text.trim()
        if (t.isNotEmpty() && ownChannels(t).isEmpty()) return "No channel numbers found. Each line wants a number then the channel's name, like: 101 BBC One"
        if (t.isEmpty() && l.isEmpty()) return "Nothing was sent."
        prefs(context).edit().apply {
            if (t.isNotEmpty()) putString("ownText", t)
            if (l.isNotEmpty()) putString("ownLink", l).putString("ownFetched", fetched).putLong("ownAt", System.currentTimeMillis())
        }.apply()
        useOwn(context)
        return "Added. " + (own?.channels?.map { it.number }?.distinct()?.size ?: 0) + " of your own aerial channels in use."
    }

    fun clearOwn(context: Context) {
        prefs(context).edit().remove("ownText").remove("ownLink").remove("ownFetched").remove("ownAt").apply()
        useOwn(context)
    }

    private fun useOwn(context: Context) {
        val p = prefs(context)
        val all = ownChannels(p.getString("ownFetched", "").orEmpty()) + ownChannels(p.getString("ownText", "").orEmpty())
        // Lines typed in come after the link's, so a typed number wins over the link's for the same channel.
        own = if (all.isEmpty()) null else Plan("own", "Your own numbers", emptyList(), null, all.reversed())
        Aerial.bump()
    }

    private fun loadOwn(context: Context) {
        useOwn(context)
        val p = prefs(context)
        val link = p.getString("ownLink", "").orEmpty()
        if (link.isEmpty() || System.currentTimeMillis() - p.getLong("ownAt", 0L) < DAY_MS) return
        runCatching {
            val fresh = fetch(link)
            if (ownChannels(fresh).isEmpty()) throw IllegalStateException("no numbers in it")
            p.edit().putString("ownFetched", fresh).putLong("ownAt", System.currentTimeMillis()).apply()
            useOwn(context)
        }.onFailure { Report.note("Aerial", "your own list not fetched: " + it.message) }
    }

    private fun fetch(link: String): String =
        Provider.http.newCall(okhttp3.Request.Builder().url(link).header("User-Agent", Provider.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("answered " + r.code)
            r.body.string()
        }

    /** Numbers from lines of "number name" (or "name number"), KueTV's own channel file, or a channel list's tvg-chno. */
    internal fun ownChannels(text: String): List<Channel> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("{")) return parse(t).flatMap { it.channels }
        val out = ArrayList<Channel>()
        if (t.startsWith("#EXTM3U")) {
            val chno = Regex("tvg-chno=\"(\\d+)\"")
            t.lineSequence().filter { it.startsWith("#EXTINF") }.forEach { line ->
                val n = chno.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val name = line.substringAfterLast(',').trim()
                if (n > 0 && name.isNotEmpty()) out += Channel(n, name)
            }
            return out
        }
        val first = Regex("^\\s*(\\d{1,4})(?![.\\-]\\d)[\\s.:,;=\\-–]+(.+?)\\s*$")
        val last = Regex("^\\s*(.+?)[\\s.:,;=\\-–]+(\\d{1,4})\\s*$")
        t.lineSequence().forEach { line ->
            val m = first.find(line)
            if (m != null) { m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.let { out += Channel(it, m.groupValues[2]) }; return@forEach }
            last.find(line)?.let { l -> l.groupValues[2].toIntOrNull()?.takeIf { it > 0 }?.let { out += Channel(it, l.groupValues[1]) } }
        }
        return out
    }

    /** For the tests: use these lists as if fetched, with [id] in use. */
    internal fun setForChecks(all: List<Plan>, id: String) {
        plans = all
        current = all.firstOrNull { it.id == id }
    }
}
