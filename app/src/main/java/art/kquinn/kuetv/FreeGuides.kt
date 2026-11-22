package art.kquinn.kuetv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Free TV guides (XMLTV files) for the UK and Ireland, used for any channel the provider has no guide for, and for
 * free channels. A channel is matched by its name: "UK: BBC One HD" finds "BBC One London HD".
 */
object FreeGuides {
    class Source(val id: String, val name: String, val url: String)

    val sources = listOf(
        Source("freeview", "Freeview (UK)", "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml"),
        Source("uk", "UK satellite and cable", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"),
        Source("ie", "Ireland", "https://epgshare01.online/epgshare01/epg_ripper_IE1.xml.gz"),
    )

    /** Fetched again once they are this old. */
    private const val FRESH_MS = 12 * 3_600_000L

    var changes by mutableIntStateOf(0)
        private set

    /** What was found the last time: how many of the lists' channels have a free guide. */
    @Volatile var matched: Int = 0
        private set

    /** The programmes for each channel name, in the plain form [plain] gives. */
    @Volatile private var index: Map<String, List<Programme>> = emptyMap()

    private fun prefs(context: Context) = context.getSharedPreferences("free_guides", Context.MODE_PRIVATE)

    /** Freeview, Ireland and guides added in Settings are on to begin with. */
    fun isOn(context: Context, id: String): Boolean = prefs(context).getBoolean("on.$id", id == "freeview" || id == "ie" || id.startsWith("own-"))

    /** Guides added by their web address in Settings, kept on this device. */
    fun own(context: Context): List<Source> =
        prefs(context).getString("own", "").orEmpty().split('\n').mapNotNull { line -> line.split('\u0001').takeIf { it.size == 3 }?.let { Source(it[0], it[1], it[2]) } }

    /** The built-in free guides, then the ones added. */
    fun all(context: Context): List<Source> = sources + own(context)

    /** Adds a guide by its web address (an XMLTV file, plain or .gz). Named after its website. */
    fun addOwn(context: Context, address: String): Source {
        val url = address.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        val host = runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("").ifEmpty { "your guide" }
        val source = Source("own-" + (url.hashCode() and 0x7fffffff), "Your guide from $host", url)
        val kept = own(context).filter { it.id != source.id } + source
        prefs(context).edit().putString("own", kept.joinToString("\n") { it.id + "\u0001" + it.name + "\u0001" + it.url }).apply()
        changes++
        return source
    }

    fun removeOwn(context: Context, id: String) {
        val kept = own(context).filter { it.id != id }
        prefs(context).edit().putString("own", kept.joinToString("\n") { it.id + "\u0001" + it.name + "\u0001" + it.url }).remove("on.$id").apply()
        File(context.cacheDir, "guide-$id").delete()
        changes++
    }

    /** Every channel name in the guides read last time, A to Z, for matching a channel by hand. */
    @Volatile var guideNames: List<String> = emptyList()
        private set

    /**
     * Channels matched to a guide channel by hand: the channel's id to the guide channel's [key]. Used before the
     * matching by name, so a wrong match can be put right.
     */
    fun matches(context: Context): Map<String, String> =
        context.getSharedPreferences("guide_matches", Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Changes when a channel is matched by hand, so the guides are read again. */
    var matchChanges by mutableIntStateOf(0)
        private set

    /** Matches a channel (all its [channelIds]) to the guide channel called [guideName]; null goes back to matching by name. */
    fun match(context: Context, channelIds: List<String>, guideName: String?) {
        val edit = context.getSharedPreferences("guide_matches", Context.MODE_PRIVATE).edit()
        for (id in channelIds) if (guideName == null) edit.remove(id) else edit.putString(id, key(guideName))
        edit.apply()
        matchChanges++
    }

    fun setOn(context: Context, id: String, on: Boolean) {
        prefs(context).edit().putBoolean("on.$id", on).apply()
        changes++
    }

    fun lastFetchedMs(context: Context): Long = prefs(context).getLong("fetched", 0)

    /** A channel's free guide, oldest first; empty when none of the free guides has it. */
    /** Every channel's free guide, by [key]. */
    fun everything(): Map<String, List<Programme>> = index

    fun lookup(name: String): List<Programme> = if (name.isBlank()) emptyList() else index[key(name)].orEmpty()

    /**
     * Fetches the free guides that are on (when the copy kept here is old) and reads the programmes for the channels
     * called [names]. Call off the main thread. Returns how many of [names] have a guide.
     */
    fun refresh(
        context: Context,
        names: Collection<String>,
        now: Long = System.currentTimeMillis(),
        /** Channels matched by hand: the channel's name, as [key] gives it, to the guide channel's. */
        byHand: Map<String, String> = emptyMap(),
    ): Int {
        val wanted = names.mapTo(HashSet()) { key(it) }.filterTo(HashSet()) { it.isNotBlank() }
        val all = HashMap<String, MutableList<Programme>>()
        val seen = java.util.TreeSet(String.CASE_INSENSITIVE_ORDER)
        for (source in all(context)) {
            if (!isOn(context, source.id)) continue
            val file = File(context.cacheDir, "guide-" + source.id)
            try {
                if (!file.exists() || now - file.lastModified() > FRESH_MS) fetch(source.url, file)
                file.inputStream().buffered().use { input ->
                    for ((name, list) in read(unzipped(input), wanted, now, byHand, seen)) all.getOrPut(name) { ArrayList() }.addAll(list)
                }
            } catch (e: Exception) {
                Report.note("Free guides", source.name + " couldn't be read: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        index = all.mapValues { (_, list) -> list.sortedBy { it.startMs }.distinctBy { it.startMs } }
        guideNames = seen.toList()
        matched = index.size
        prefs(context).edit().putLong("fetched", now).apply()
        changes++
        Report.note("Free guides", "read: " + index.size + " of the lists' channels have a free guide")
        return index.size
    }

    private fun fetch(url: String, into: File) {
        val http = Provider.http.newBuilder().callTimeout(120, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(url).header("User-Agent", Provider.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("the guide answered " + r.code)
            val part = File(into.path + ".part")
            part.outputStream().use { out -> r.body.byteStream().copyTo(out) }
            if (!part.renameTo(into)) throw java.io.IOException("couldn't keep the guide")
        }
    }

    /** The file as it is, or opened up when it is a .gz. */
    private fun unzipped(input: java.io.BufferedInputStream): InputStream {
        input.mark(2)
        val a = input.read()
        val b = input.read()
        input.reset()
        return if (a == 0x1f && b == 0x8b) java.util.zip.GZIPInputStream(input) else input
    }

    /**
     * Reads an XMLTV file, keeping the programmes from a day ago to a week ahead for the channels in [wanted]
     * (as [key] gives them). A channel in the file serves a wanted name when its own is the same, or, for a regional
     * channel, when it is the wanted name followed by a region ("bbc1london" for "bbc1"; London is preferred).
     */
    fun read(
        input: InputStream,
        wanted: Set<String>,
        now: Long = System.currentTimeMillis(),
        /** Channels matched by hand: a wanted name to the guide channel's own [key]. */
        byHand: Map<String, String> = emptyMap(),
        /** Every channel's name in the file is added here, as the file writes it. */
        seen: MutableCollection<String>? = null,
    ): Map<String, List<Programme>> {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, null)
        val names = HashMap<String, MutableList<String>>()  // the file's channel id -> its plain names
        var serves: Map<String, List<String>>? = null       // the file's channel id -> the wanted names it serves
        val out = HashMap<String, MutableList<Programme>>()
        val from = now - 24 * 3_600_000L
        val until = now + 7 * 24 * 3_600_000L
        var channelId: String? = null
        var programme: Array<String>? = null                // channel, start, stop, title, desc
        var field = ""
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")
                    "display-name" -> field = if (channelId != null) "display-name" else ""
                    "programme" -> {
                        if (serves == null) serves = match(names, wanted, byHand)
                        val ch = parser.getAttributeValue(null, "channel").orEmpty()
                        programme = if (serves[ch].isNullOrEmpty()) null
                        else arrayOf(ch, parser.getAttributeValue(null, "start").orEmpty(), parser.getAttributeValue(null, "stop").orEmpty(), "", "")
                    }
                    "title" -> field = if (programme != null) "title" else ""
                    "desc" -> field = if (programme != null) "desc" else ""
                    else -> field = ""
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty()
                    when (field) {
                        "display-name" -> channelId?.let { id ->
                            names.getOrPut(id) { ArrayList() } += key(text)
                            text.trim().takeIf { it.isNotEmpty() }?.let { seen?.add(it) }
                        }
                        "title" -> programme?.let { if (it[3].isEmpty()) it[3] = text.trim() }
                        "desc" -> programme?.let { if (it[4].isEmpty()) it[4] = text.trim() }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> channelId = null
                        "programme" -> {
                            val p = programme
                            programme = null
                            val start = p?.let { time(it[1]) }
                            val stop = p?.let { time(it[2]) }
                            if (p != null && start != null && stop != null && stop > start && stop > from && start < until) {
                                val made = Programme(p[3], p[4], start, stop, "")
                                for (name in serves?.get(p[0]).orEmpty()) out.getOrPut(name) { ArrayList() } += made
                            }
                        }
                    }
                    field = ""
                }
            }
        }
        return out
    }

    /** Which of the file's channels serve which wanted names: the same plain name first, then a regional one. */
    private fun match(names: Map<String, List<String>>, wanted: Set<String>, byHand: Map<String, String> = emptyMap()): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        val exact = HashSet<String>()
        // Matched by hand first: those wanted names take only the guide channel chosen for them.
        for ((w, g) in byHand) {
            if (w !in wanted) continue
            val id = names.entries.firstOrNull { (_, plains) -> g in plains }?.key ?: continue
            out.getOrPut(id) { ArrayList() } += w
            exact += w
        }
        for ((id, plains) in names) for (p in plains) if (p in wanted && p !in byHand) { out.getOrPut(id) { ArrayList() } += p; exact += p }
        for (w in wanted - exact - byHand.keys) {
            val regional = names.entries.filter { (_, keys) -> keys.any { it.startsWith(w) && it.removePrefix(w) in regions } }
            val pick = regional.firstOrNull { (_, keys) -> keys.any { it.endsWith("london") } } ?: regional.firstOrNull()
            if (pick != null) out.getOrPut(pick.key) { ArrayList() } += w
        }
        return out
    }

    /** "20261117222000 +0000" as milliseconds; null when it isn't that shape. */
    fun time(text: String): Long? = runCatching {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.UK)
        f.parse(text.trim().let { if (it.length == 14) "$it +0000" else it })?.time
    }.getOrNull()

    /** The regions a channel's name can end with: "BBC One London" is a BBC One. */
    private val regions = setOf(
        "london", "wales", "scotland", "ni", "northernireland", "ci", "east", "west", "north", "south", "southeast", "southwest", "northeast",
        "northwest", "eastmidlands", "westmidlands", "yorkshire", "yorksandlincs", "anglia", "border", "granada", "central", "meridian",
        "tynetees", "westcountry", "cymru", "england", "uk",
    )

    private val numbers = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5")

    /**
     * How a channel is matched between a list and a guide: its plain name with numbers as figures and no spaces.
     * "BBC One HD" and "BBC1" are both "bbc1"; "ITV 1" is "itv1"; "U&Dave" is "dave"; "Channel 5" is "5".
     */
    fun key(name: String): String {
        val words = plain(name).split(' ').filter { it.isNotEmpty() }.map { numbers[it] ?: it }
        val joined = words.joinToString("").removePrefix("uand")
        return if (joined == "channel5") "5" else joined
    }

    private val tags = Regex("^(\\[[^]]*]|\\|[^|]*\\||[a-z]{2,3}\\s*[:|]|[a-z]{2,3}\\s+-)\\s*")
    private val quality = Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|raw|backup)\\b")

    /** A channel's name, plain: "UK: BBC One HD" and "BBC ONE (HD)" both as "bbc one"; "RTÉ One" as "rte one". */
    fun plain(name: String): String {
        val noMarks = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noMarks.lowercase().trim()
            .replace(tags, "")
            .replace("&", " and ")
            .replace(quality, " ")
            .replace(Regex("[^a-z0-9+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
