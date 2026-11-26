package art.kquinn.kuesounds

import android.content.Context
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * The cleverer parts of KueSounds' music (Kevin, 26 November 2026): duplicate songs found across the library and inside
 * playlists, mixes made by themselves, songs suggested for a playlist, how often each song is played, and covers to pick
 * by hand for an album the catalogue doesn't know.
 */
object Smart {
    // ---- Duplicates ----

    /** What two copies of one song share: the name without anything in brackets, and the main artist. */
    fun sameSongKey(t: Track): String {
        val name = Catalogue.compact(Library.withoutBrackets(t.title))
        val who = Catalogue.compact(t.artist.split(Regex(",|;|/|\\s&\\s|\\sfeat|\\sft\\.?", RegexOption.IGNORE_CASE)).first())
        return "$who|$name"
    }

    /**
     * Songs held more than once: the same name and artist, and lengths within 5 seconds of each other (a live or
     * extended version, much longer, isn't a duplicate). Each group largest first, then by artist.
     */
    fun duplicates(tracks: List<Track>): List<List<Track>> {
        val out = ArrayList<List<Track>>()
        for ((key, list) in tracks.groupBy { sameSongKey(it) }) {
            if (list.size < 2 || key.endsWith("|")) continue
            // Copies of near enough the same length, together.
            val left = list.sortedBy { it.durationMs }.toMutableList()
            while (left.isNotEmpty()) {
                val first = left.removeAt(0)
                val group = mutableListOf(first)
                val it = left.iterator()
                while (it.hasNext()) {
                    val t = it.next()
                    if (first.durationMs <= 0 || t.durationMs <= 0 || kotlin.math.abs(t.durationMs - first.durationMs) <= 5_000) { group += t; it.remove() }
                }
                if (group.size > 1) out += group
            }
        }
        return out.sortedWith(compareByDescending<List<Track>> { it.size }.thenBy { Library.sortName(it.first().artist) }.thenBy { Library.sortName(it.first().title) })
    }

    /** A playlist's songs with each song kept only once (the first time it appears), and how many went. */
    fun withoutRepeats(ids: List<String>, byId: Map<String, Track>): Pair<List<String>, Int> {
        val seen = HashSet<String>()
        val kept = ids.filter { id -> seen.add(byId[id]?.let { sameSongKey(it) } ?: id) }
        return kept to (ids.size - kept.size)
    }

    // ---- Suggestions ----

    /**
     * Songs from your music that go with [seed]: the same artists most, then the same kinds of music, then the same
     * decade. Not ones already in it, and not two copies of one song.
     */
    fun suggest(seed: List<Track>, all: List<Track>, d: LibraryData, count: Int = 20): List<Track> {
        if (seed.isEmpty()) return emptyList()
        val have = seed.map { it.id }.toSet()
        val haveSongs = seed.map { sameSongKey(it) }.toSet()
        val artists = seed.groupingBy { Library.sortName(it.artist) }.eachCount()
        val kinds = seed.map { Library.genreOf(it, d).lowercase() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        val decades = seed.filter { it.year > 0 }.groupingBy { it.year / 10 }.eachCount()
        val random = java.util.Random(seed.first().id.hashCode().toLong())
        return all.asSequence()
            .filter { it.id !in have && sameSongKey(it) !in haveSongs }
            .map { t ->
                val score = 3.0 * (artists[Library.sortName(t.artist)] ?: 0).coerceAtMost(3) +
                    2.0 * (kinds[Library.genreOf(t, d).lowercase()] ?: 0).coerceAtMost(3) +
                    1.0 * (if (t.year > 0) (decades[t.year / 10] ?: 0).coerceAtMost(3) else 0) +
                    random.nextDouble() * 0.5
                t to score
            }
            .filter { it.second >= 2.0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { sameSongKey(it) }
            .take(count)
            .toList()
    }

    // ---- How often each song is played ----

    private lateinit var app: Context
    private fun plays() = app.getSharedPreferences("plays", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    /** A song started playing: counted, with when. Called by the player. */
    fun played(id: String) {
        if (!::app.isInitialized || id.startsWith("pod:") || id.startsWith("radio:") || id.isEmpty()) return
        val (n, _) = playsOf(id)
        plays().edit().putString(id, (n + 1).toString() + "|" + System.currentTimeMillis()).apply()
    }

    /** How many times a song has been played, and when last (0 when never). */
    fun playsOf(id: String): Pair<Int, Long> {
        if (!::app.isInitialized) return 0 to 0L
        val v = plays().getString(id, null) ?: return 0 to 0L
        return (v.substringBefore('|').toIntOrNull() ?: 0) to (v.substringAfter('|').toLongOrNull() ?: 0L)
    }

    // ---- Mixes made by themselves ----

    /** A mix: its name, the line under it, and its songs. */
    data class Mix(val name: String, val below: String, val songs: List<Track>)

    /** The mixes KueSounds makes from your music, best first; each needs at least 10 songs. */
    fun mixes(tracks: List<Track>, d: LibraryData, favourites: Set<String>): List<Mix> {
        if (tracks.isEmpty()) return emptyList()
        val out = ArrayList<Mix>()
        val counted = tracks.map { it to playsOf(it.id) }
        val most = counted.filter { it.second.first > 0 }.sortedByDescending { it.second.first }.map { it.first }.take(50)
        if (most.size >= 10) out += Mix("Most played", "Your 50 most played songs", most)
        val newest = tracks.sortedByDescending { it.modified }.take(50)
        out += Mix("Recently added", "The 50 songs added last", newest)
        val favs = tracks.filter { it.id in favourites }
        if (favs.size >= 5) out += Mix("Favourites mix", "Your favourites and songs like them", (favs.shuffled() + suggest(favs, tracks, d, 30)).distinctBy { it.id })
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60_000
        val forgotten = counted.filter { (t, p) -> (t.id in favourites || p.first >= 3) && p.second in 1 until monthAgo }.map { it.first }
        if (forgotten.size >= 10) out += Mix("Not heard for a while", "Songs you loved, not played for a month", forgotten.shuffled().take(50))
        for (decade in listOf(1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020)) {
            val songs = tracks.filter { it.year in decade until decade + 10 }
            if (songs.size >= 10) out += Mix(decadeName(decade), "Songs from " + decade + " to " + (decade + 9), songs.shuffled().take(80))
        }
        val byKind = tracks.groupBy { Library.genreOf(it, d) }.filterKeys { it.isNotBlank() }.entries.sortedByDescending { it.value.size }.take(8)
        for ((kind, songs) in byKind) if (songs.size >= 10) out += Mix(kind, "A mix of " + kind, songs.shuffled().take(80))
        out += Mix("Shuffle everything", "Fifty songs from all your music", tracks.shuffled().take(50))
        return out
    }

    fun decadeName(decade: Int): String = (if (decade >= 2000) decade.toString() else (decade % 100).toString().padStart(2, '0')) + "s"

    // ---- Covers to pick by hand ----

    /** A cover found online: a small picture to show, a big one to keep, and what it is. */
    data class CoverChoice(val small: String, val big: String, val title: String, val artist: String, val from: String)

    /** Covers from Apple's music shop (free to search, no key). */
    fun parseItunes(json: String): List<CoverChoice> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            val art = o.get("artworkUrl100")?.asString ?: return@mapNotNull null
            CoverChoice(art, art.replace("100x100bb", "600x600bb"), o.get("collectionName")?.asString.orEmpty(), o.get("artistName")?.asString.orEmpty(), "Apple Music")
        }
    }.getOrDefault(emptyList())

    /** Covers from Deezer (free to search, no key). */
    fun parseDeezer(json: String): List<CoverChoice> = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val big = o.get("cover_xl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            CoverChoice(o.get("cover_medium")?.asString ?: big, big, o.get("title")?.asString.orEmpty(), o.getAsJsonObject("artist")?.get("name")?.asString.orEmpty(), "Deezer")
        }
    }.getOrDefault(emptyList())

    /** Covers for [words] ("artist album") from both, one of each in turn. Call off the main thread. */
    fun findCovers(words: String): List<CoverChoice> {
        fun get(url: okhttp3.HttpUrl): String? = runCatching {
            Drive.http.newCall(Request.Builder().url(url).header("User-Agent", Catalogue.WHO).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
        }.getOrNull()
        val apple = get("https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("term", words).addQueryParameter("entity", "album").addQueryParameter("limit", "15").build())
            ?.let { parseItunes(it) }.orEmpty()
        val deezer = get("https://api.deezer.com/search/album".toHttpUrl().newBuilder().addQueryParameter("q", words).addQueryParameter("limit", "15").build())
            ?.let { parseDeezer(it) }.orEmpty()
        val out = ArrayList<CoverChoice>()
        for (i in 0 until maxOf(apple.size, deezer.size)) {
            apple.getOrNull(i)?.let { out += it }
            deezer.getOrNull(i)?.let { out += it }
        }
        return out
    }

    /** Downloads a picked cover. Call off the main thread. */
    fun download(url: String): ByteArray? = runCatching {
        Drive.http.newCall(Request.Builder().url(url).header("User-Agent", Catalogue.WHO).build()).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }.getOrNull()
}

/** Words as Kue shows them. */
object Plain {
    fun noEmoji(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val emoji = cp >= 0x1F000 && cp <= 0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x2300..0x23FF ||
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F || cp in 0x1F1E6..0x1F1FF
            if (!emoji) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        // A name that was only pictures is called "Untitled".
        return out.toString().replace(Regex(" {2,}"), " ").trim().ifEmpty { if (text.isBlank()) text else "Untitled" }
    }
}
