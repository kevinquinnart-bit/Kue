package art.kquinn.kuesounds

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Podcasts: shows followed, their episodes, how far each has been listened to, and episodes saved on the phone.
 * Which shows are followed, where each episode got to and which are finished go into KueSounds' own file in the
 * Drive (see [Sync]), so another phone or the TV carries on from the same place. Shows are found in Apple's free
 * podcast directory, or added by their web address (RSS); the episodes come straight from each show's own feed.
 */
object Podcasts {
    /** A show. [feed] is its RSS web address, which is also how it is known. */
    data class Show(val feed: String, val title: String, val author: String, val art: String, val about: String, val link: String = "")

    /** One episode. [id] is KueSounds' own name for it, the same on every device. */
    data class Episode(
        val id: String, val feed: String, val title: String, val url: String, val published: Long,
        val durationSec: Long, val about: String, val art: String, val bytes: Long,
    )

    /** A show found in the directory, not necessarily followed. */
    data class Found(val title: String, val author: String, val feed: String, val art: String, val genre: String)

    var shows by mutableStateOf<List<Show>>(emptyList())
        private set
    var episodes by mutableStateOf<Map<String, List<Episode>>>(emptyMap())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var problem by mutableStateOf("")
    /** Episodes on the phone, by id. */
    var saved by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Episodes being saved now, with how far each has got (0 to 100). */
    var saving by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** Goes up as positions change, so the screens showing them follow. */
    var touches by mutableStateOf(0)
        private set

    const val SPEEDS = "0.8,1,1.2,1.5,1.75,2,2.5,3"
    private const val PREFIX = "pod:"

    private lateinit var app: Context
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()

    private fun showsFile() = File(app.filesDir, "podcast-shows.json")
    private fun feedFile(feed: String) = File(File(app.filesDir, "podcast-feeds").apply { mkdirs() }, hash(feed) + ".json")
    private fun positions() = app.getSharedPreferences("podcast-positions", Context.MODE_PRIVATE)
    private fun prefs() = app.getSharedPreferences("podcasts", Context.MODE_PRIVATE)
    val folder: File get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "podcasts").apply { mkdirs() }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Sync.init(app)
        shows = runCatching { gson.fromJson<List<Show>>(showsFile().readText(), object : TypeToken<List<Show>>() {}.type) }.getOrNull().orEmpty()
        val eps = HashMap<String, List<Episode>>()
        for (s in shows) {
            runCatching { gson.fromJson<List<Episode>>(feedFile(s.feed).readText(), object : TypeToken<List<Episode>>() {}.type) }.getOrNull()?.let { eps[s.feed] = it }
        }
        episodes = eps
        saved = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    // Following ------------------------------------------------------------------------------

    /** The shows followed: those marked in the Drive's file, in the order they were added. */
    fun followed(): List<Show> {
        val feeds = Sync.entries.filter { it.key.startsWith(PREFIX + "sub:") && it.value.value == "1" }.toList()
            .sortedBy { it.second.at }.map { it.first.removePrefix(PREFIX + "sub:") }
        val byFeed = shows.associateBy { it.feed }
        return feeds.map { byFeed[it] ?: Show(it, "Loading…", "", "", "") }
    }

    fun isFollowed(feed: String): Boolean = Sync.get(PREFIX + "sub:" + feed) == "1"

    /** Follows the show at [feed]: reads its feed first, so it shows straight away. Call off the main thread. */
    fun follow(feed: String) {
        val (show, eps) = read(feed)
        keep(show, eps)
        Sync.put(PREFIX + "sub:" + show.feed, "1")
    }

    fun unfollow(feed: String) {
        Sync.put(PREFIX + "sub:" + feed, "")
    }

    private fun keep(show: Show, eps: List<Episode>) {
        val list = shows.filter { it.feed != show.feed } + show
        shows = list
        runCatching { showsFile().writeText(gson.toJson(list)) }
        episodes = episodes + (show.feed to eps)
        runCatching { feedFile(show.feed).writeText(gson.toJson(eps)) }
    }

    /** Reads every followed show's feed afresh; a show that can't be read keeps what was had. Off the main thread. */
    fun refresh() {
        if (refreshing) return
        refreshing = true
        problem = ""
        var failed = 0
        try {
            for (s in followed()) {
                runCatching { read(s.feed) }.onSuccess { (show, eps) -> keep(show, eps) }.onFailure { failed++ }
            }
            prefs().edit().putLong("refreshedAt", System.currentTimeMillis()).apply()
            if (failed > 0) problem = if (failed == 1) "One show couldn't be checked just now." else "$failed shows couldn't be checked just now."
        } finally {
            refreshing = false
        }
    }

    /** Checks for new episodes when it hasn't been done for a while. */
    fun refreshIfStale(): Boolean = System.currentTimeMillis() - prefs().getLong("refreshedAt", 0) > 30 * 60_000L

    // Moving from another podcast app --------------------------------------------------------

    /**
     * The shows' web addresses in a file from another podcast app: the usual OPML export (every podcast app
     * can make one), or failing that any web addresses in the file, one per show.
     */
    fun feedsInFile(text: String): List<String> {
        val found = LinkedHashSet<String>()
        runCatching {
            val p = android.util.Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(text.reader())
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.name.equals("outline", ignoreCase = true)) {
                    (p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl") ?: p.getAttributeValue(null, "url"))
                        ?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { found += it }
                }
                event = p.next()
            }
        }
        if (found.isEmpty()) Regex("https?://[^\\s\"'<>]+").findAll(text).forEach { found += it.value.trimEnd('.', ',', ')') }
        return found.toList()
    }

    /** Where bringing shows in from a file has got to ("12 of 40 added"), or "" when not doing it. */
    var importing by mutableStateOf("")
        private set

    /** Follows each show in [feeds] not followed already. Off the main thread. Gives how many came in, and how many couldn't. */
    fun importFeeds(feeds: List<String>): Pair<Int, Int> {
        var added = 0
        var failed = 0
        val todo = feeds.filter { !isFollowed(it) }
        try {
            todo.forEachIndexed { i, feed ->
                importing = "Adding " + (i + 1) + " of " + todo.size + "…"
                if (runCatching { follow(feed) }.isSuccess) added++ else failed++
            }
        } finally {
            importing = ""
        }
        return added to failed
    }

    /** Your shows as an OPML file, which any other podcast app can bring in. */
    fun opml(): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val lines = followed().joinToString("\n") { s -> "    <outline type=\"rss\" text=\"" + esc(s.title) + "\" title=\"" + esc(s.title) + "\" xmlUrl=\"" + esc(s.feed) + "\" />" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n  <head><title>KueSounds podcasts</title></head>\n  <body>\n" + lines + "\n  </body>\n</opml>\n"
    }

    // Finding shows ----------------------------------------------------------------------------

    /** Searches Apple's free podcast directory. Off the main thread. */
    fun search(term: String): List<Found> {
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&term=" + URLEncoder.encode(term.trim(), "UTF-8")
        return parseFound(get(url))
    }

    /** The most listened-to shows in the UK this week, from Apple's charts. Off the main thread. */
    fun top(): List<Found> {
        val chart = JsonParser.parseString(get("https://rss.applemarketingtools.com/api/v2/gb/podcasts/top/50/podcasts.json")).asJsonObject
        val ids = chart.getAsJsonObject("feed").getAsJsonArray("results").mapNotNull { it.asJsonObject.get("id")?.asString }
        if (ids.isEmpty()) return emptyList()
        val found = parseFound(get("https://itunes.apple.com/lookup?entity=podcast&id=" + ids.joinToString(",")))
        // In the chart's order.
        return found
    }

    private fun parseFound(json: String): List<Found> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            val feed = o.get("feedUrl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            Found(
                o.get("collectionName")?.asString.orEmpty(), o.get("artistName")?.asString.orEmpty(), feed,
                (o.get("artworkUrl600") ?: o.get("artworkUrl100"))?.asString.orEmpty(), o.get("primaryGenreName")?.asString.orEmpty(),
            )
        }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "KueSounds/1.0 (Android podcast player)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
            return r.body.string()
        }
    }

    // Reading a feed ---------------------------------------------------------------------------

    /** Reads the show and its episodes, newest first, from its RSS feed. Off the main thread. */
    fun read(feed: String): Pair<Show, List<Episode>> = parse(feed, get(feed))

    fun parse(feed: String, xml: String): Pair<Show, List<Episode>> {
        val p = android.util.Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var title = ""
        var author = ""
        var about = ""
        var art = ""
        var link = ""
        val eps = ArrayList<Episode>()
        var inItem = false
        var inImage = false
        // The episode being read.
        var eTitle = ""; var eGuid = ""; var eUrl = ""; var eDate = 0L; var eDur = 0L; var eAbout = ""; var eArt = ""; var eBytes = 0L
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                when {
                    name == "item" -> {
                        inItem = true
                        eTitle = ""; eGuid = ""; eUrl = ""; eDate = 0L; eDur = 0L; eAbout = ""; eArt = ""; eBytes = 0L
                    }
                    name == "image" && !inItem && p.getAttributeValue(null, "href") == null -> inImage = true
                    name == "enclosure" && inItem -> {
                        eUrl = p.getAttributeValue(null, "url").orEmpty()
                        eBytes = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                    }
                    name == "itunes:image" -> p.getAttributeValue(null, "href")?.let { if (inItem) eArt = it else if (art.isEmpty()) art = it }
                    else -> {
                        val text = runCatching { p.nextText() }.getOrDefault("").trim()
                        if (inItem) when (name) {
                            "title" -> eTitle = text
                            "guid" -> eGuid = text
                            "pubdate" -> eDate = date(text)
                            "itunes:duration" -> eDur = duration(text)
                            "description", "itunes:summary", "content:encoded" -> if (text.length > eAbout.length) eAbout = text
                        } else when (name) {
                            "title" -> if (title.isEmpty() && !inImage) title = text
                            "url" -> if (inImage && art.isEmpty()) art = text
                            "itunes:author" -> if (author.isEmpty()) author = text
                            "description", "itunes:summary" -> if (text.length > about.length) about = text
                            "link" -> if (link.isEmpty() && !inImage) link = text
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                val name = p.name.lowercase(Locale.ROOT)
                if (name == "image") inImage = false
                if (name == "item") {
                    inItem = false
                    if (eUrl.isNotEmpty()) {
                        val key = eGuid.ifEmpty { eUrl }
                        eps += Episode(hash(feed + "\n" + key), feed, eTitle.ifEmpty { "Episode" }, eUrl, eDate, eDur, plain(eAbout), eArt, eBytes)
                    }
                }
            }
            event = p.next()
        }
        val show = Show(feed, title.ifEmpty { feed }, author, art, plain(about), link)
        return show to eps.sortedByDescending { it.published }
    }

    /** "Tue, 04 Nov 2025 06:00:00 +0000" and the other ways feeds write their dates. */
    private fun date(text: String): Long {
        val t = text.trim()
        for (f in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm zzz", "dd MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            runCatching { SimpleDateFormat(f, Locale.ENGLISH).parse(t)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    /** "1:02:03", "62:03" or "3723", as seconds. */
    fun duration(text: String): Long {
        val parts = text.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0L }
        return parts.fold(0.0) { acc, v -> acc * 60 + v }.toLong()
    }

    /** A description with its web formatting taken out, for plain reading. */
    fun plain(html: String): String {
        if (html.isBlank()) return ""
        val s = html.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n").replace(Regex("<[^>]+>"), "")
        return androidx.core.text.HtmlCompat.fromHtml(s.replace("\n", "<br>"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // Listening --------------------------------------------------------------------------------

    fun find(id: String): Episode? = episodes.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } }
    fun showOf(e: Episode): Show? = shows.firstOrNull { it.feed == e.feed }

    /** Where [id] was got to, in ms: on this device, or the later of this and the Drive's. */
    fun position(id: String): Long {
        val here = positions().getLong(id, 0L)
        val there = Sync.get(PREFIX + "pos:" + id)?.toLongOrNull() ?: 0L
        return maxOf(here, there)
    }

    /** Keeps where [id] has got to. [share] also puts it in the Drive's file (done on pause and every few minutes). */
    fun keepPosition(id: String, ms: Long, share: Boolean) {
        positions().edit().putLong(id, ms).apply()
        if (share) Sync.put(PREFIX + "pos:" + id, ms.toString())
        touches++
    }

    fun isPlayed(id: String): Boolean = Sync.get(PREFIX + "done:" + id) == "1"

    fun setPlayed(id: String, played: Boolean) {
        Sync.put(PREFIX + "done:" + id, if (played) "1" else "")
        if (played) keepPosition(id, 0L, true)
        touches++
        // A finished episode saved on the phone is taken off it, when chosen in the settings.
        if (played && autoRemove()) find(id)?.let { remove(it) }
    }

    fun speed(): Float = prefs().getFloat("speed", 1f)
    fun speed(value: Float) = prefs().edit().putFloat("speed", value).apply()
    fun autoRemove(): Boolean = prefs().getBoolean("autoRemove", true)
    fun autoRemove(value: Boolean) { prefs().edit().putBoolean("autoRemove", value).apply(); touches++ }

    /** "45 min", "1 hr 5 min left" and so on. */
    fun lengthWords(e: Episode): String {
        val total = e.durationSec * 1000
        val at = position(e.id)
        return when {
            isPlayed(e.id) -> "Played"
            at > 0 && total > 0 -> minutes(total - at) + " left"
            total > 0 -> minutes(total)
            else -> ""
        }
    }

    fun minutes(ms: Long): String {
        val m = ((ms + 30_000) / 60_000).toInt()
        return if (m >= 60) (m / 60).toString() + " hr " + (if (m % 60 > 0) (m % 60).toString() + " min" else "") else "$m min"
    }

    fun dateWords(ms: Long): String = if (ms == 0L) "" else SimpleDateFormat("d MMM yyyy", Locale.UK).format(java.util.Date(ms))

    /** Episodes started but not finished, the most recently listened first. */
    fun inProgress(): List<Episode> {
        val followedFeeds = followed().map { it.feed }.toSet()
        return episodes.filterKeys { it in followedFeeds }.values.flatten().filter { position(it.id) > 0 && !isPlayed(it.id) }
            .sortedByDescending { Sync.entries[PREFIX + "pos:" + it.id]?.at ?: 0L }
    }

    /** The newest episodes of every followed show, newest first. */
    fun latest(limit: Int = 60): List<Episode> {
        val followedFeeds = followed().map { it.feed }.toSet()
        return episodes.filterKeys { it in followedFeeds }.values.flatten().sortedByDescending { it.published }.take(limit)
    }

    fun unplayedCount(feed: String): Int = episodes[feed].orEmpty().take(50).count { !isPlayed(it.id) && position(it.id) == 0L }

    // Saving on the phone ----------------------------------------------------------------------

    private fun ending(url: String): String = url.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase(Locale.ROOT).ifEmpty { "mp3" }

    fun fileFor(e: Episode): File? = if (e.id in saved) folder.listFiles()?.firstOrNull { it.nameWithoutExtension == e.id && !it.name.endsWith(".part") } else null

    /** Saves the episode on the phone, in the background. */
    fun save(e: Episode) {
        if (e.id in saved || e.id in saving) return
        saving = saving + (e.id to 0)
        Thread({
            val part = File(folder, e.id + ".part")
            try {
                val req = Request.Builder().url(e.url).header("User-Agent", "KueSounds/1.0 (Android podcast player)").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP " + r.code)
                    val total = r.body.contentLength().takeIf { it > 0 } ?: e.bytes
                    var got = 0L
                    var shown = -1
                    r.body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else 0
                                if (pct != shown) { shown = pct; saving = saving + (e.id to pct) }
                            }
                        }
                    }
                }
                part.renameTo(File(folder, e.id + "." + ending(e.url)))
                saved = saved + e.id
            } catch (x: Exception) {
                part.delete()
                problem = "\"" + e.title + "\" couldn't be saved: " + (x.message ?: "the connection dropped")
            } finally {
                saving = saving - e.id
            }
        }, "kuesounds-podcast-save").start()
    }

    fun remove(e: Episode) {
        folder.listFiles()?.filter { it.nameWithoutExtension == e.id }?.forEach { it.delete() }
        saved = saved - e.id
    }

    fun bytesUsed(): Long = folder.listFiles()?.sumOf { it.length() } ?: 0L
}
