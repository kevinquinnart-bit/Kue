package art.kquinn.kuesounds

import com.google.gson.Gson
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder
import java.util.Locale

/**
 * Playlists: your own lists of songs from your music. Each is kept in KueSounds' own file in the Drive (see
 * [Sync]), so the phone and the TV have the same ones. A playlist from another player comes in from its file
 * (M3U or M3U8, PLS, XSPF, WPL, or a CSV with song and artist columns, as Spotify's exports are), each song
 * in it matched to the same song in your music; those not in your music are listed, not guessed.
 */
object Playlists {
    data class Playlist(val id: String, val name: String, val ids: List<String>)

    /** One song as another player's file names it. */
    data class Wanted(val title: String, val artist: String, val file: String) {
        fun words(): String = when {
            title.isNotBlank() && artist.isNotBlank() -> "$artist – $title"
            title.isNotBlank() -> title
            else -> file
        }
    }

    data class Brought(val playlist: Playlist, val matched: Int, val missing: List<String>)

    private const val PREFIX = "pl:"
    private val gson = Gson()

    private data class Stored(val name: String = "", val ids: List<String> = emptyList())

    fun all(): List<Playlist> = Sync.entries.filter { it.key.startsWith(PREFIX) && it.value.value.isNotEmpty() }.mapNotNull { (k, e) ->
        runCatching { gson.fromJson(e.value, Stored::class.java) }.getOrNull()?.let { Playlist(k.removePrefix(PREFIX), it.name, it.ids) }
    }.sortedBy { it.name.lowercase(Locale.ROOT) }

    fun get(id: String): Playlist? = all().firstOrNull { it.id == id }

    fun save(p: Playlist) = Sync.put(PREFIX + p.id, gson.toJson(Stored(p.name, p.ids)))

    fun create(name: String, ids: List<String> = emptyList()): Playlist {
        val p = Playlist(System.currentTimeMillis().toString(36) + (100..999).random(), name.trim().ifEmpty { "New playlist" }, ids)
        save(p)
        return p
    }

    fun delete(id: String) = Sync.put(PREFIX + id, "")

    fun add(id: String, trackIds: List<String>) {
        val p = get(id) ?: return
        save(p.copy(ids = p.ids + trackIds))
    }

    fun removeAt(id: String, index: Int) {
        val p = get(id) ?: return
        if (index !in p.ids.indices) return
        save(p.copy(ids = p.ids.toMutableList().apply { removeAt(index) }))
    }

    fun move(id: String, from: Int, to: Int) {
        val p = get(id) ?: return
        if (from !in p.ids.indices || to !in p.ids.indices) return
        save(p.copy(ids = p.ids.toMutableList().apply { add(to, removeAt(from)) }))
    }

    fun rename(id: String, name: String) {
        val p = get(id) ?: return
        save(p.copy(name = name.trim().ifEmpty { p.name }))
    }

    // Bringing one in from another player ------------------------------------------------------

    /** Reads another player's playlist file: its name (if it gives one) and its songs. */
    fun read(fileName: String, text: String): Pair<String, List<Wanted>> {
        val body = text.removePrefix("﻿")
        val lower = fileName.lowercase(Locale.ROOT)
        val trimmed = body.trimStart()
        return when {
            lower.endsWith(".xspf") || trimmed.startsWith("<?xml") && body.contains("<playlist", true) && body.contains("xspf", true) -> xspf(body)
            lower.endsWith(".wpl") || lower.endsWith(".zpl") || trimmed.startsWith("<?wpl") || body.contains("<smil", true) -> wpl(body)
            lower.endsWith(".pls") || trimmed.startsWith("[playlist]", true) -> "" to pls(body)
            lower.endsWith(".csv") || lower.endsWith(".tsv") -> "" to csv(body)
            lower.endsWith(".m3u") || lower.endsWith(".m3u8") || trimmed.startsWith("#EXTM3U") -> m3u(body)
            else -> {
                // Not said by its name: whichever reading finds songs.
                val asCsv = runCatching { csv(body) }.getOrDefault(emptyList())
                if (asCsv.isNotEmpty()) "" to asCsv else m3u(body)
            }
        }
    }

    private fun m3u(text: String): Pair<String, List<Wanted>> {
        var name = ""
        var info = ""
        val out = ArrayList<Wanted>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", true) -> name = line.substringAfter(':').trim()
                line.startsWith("#EXTINF", true) -> info = line.substringAfter(',', "").trim()
                line.startsWith("#") -> Unit
                else -> {
                    val (artist, title) = splitArtistTitle(info)
                    out += Wanted(title, artist, fileOf(line))
                    info = ""
                }
            }
        }
        return name to out
    }

    private fun pls(text: String): List<Wanted> {
        val files = HashMap<Int, String>()
        val titles = HashMap<Int, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val m = Regex("(?i)^(file|title)(\\d+)=(.*)$").find(line) ?: continue
            val n = m.groupValues[2].toInt()
            if (m.groupValues[1].equals("file", true)) files[n] = m.groupValues[3] else titles[n] = m.groupValues[3]
        }
        return files.keys.sorted().map { n ->
            val (artist, title) = splitArtistTitle(titles[n].orEmpty())
            Wanted(title, artist, fileOf(files[n].orEmpty()))
        }
    }

    private fun xspf(text: String): Pair<String, List<Wanted>> {
        val p = android.util.Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(text.reader())
        var name = ""
        var inTrack = false
        var title = ""; var creator = ""; var location = ""
        val out = ArrayList<Wanted>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name.lowercase(Locale.ROOT)) {
                    "track" -> { inTrack = true; title = ""; creator = ""; location = "" }
                    "title" -> { val t = p.nextText().trim(); if (inTrack) title = t else if (name.isEmpty()) name = t }
                    "creator" -> { val t = p.nextText().trim(); if (inTrack) creator = t }
                    "location" -> { val t = p.nextText().trim(); if (inTrack) location = t }
                }
            } else if (event == XmlPullParser.END_TAG && p.name.equals("track", true)) {
                inTrack = false
                out += Wanted(title, creator, fileOf(location))
            }
            event = p.next()
        }
        return name to out
    }

    private fun wpl(text: String): Pair<String, List<Wanted>> {
        val name = Regex("(?is)<title>(.*?)</title>").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val out = Regex("(?i)<media[^>]*\\bsrc=\"([^\"]+)\"").findAll(text).map { Wanted("", "", fileOf(htmlDecode(it.groupValues[1]))) }.toList()
        return name to out
    }

    /** A CSV (or tab-separated) list with a first line naming its columns: the song's name and its artist. */
    private fun csv(text: String): List<Wanted> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val sep = listOf('\t', ';', ',').maxBy { c -> lines[0].count { it == c } }
        val head = cells(lines[0], sep).map { it.trim().lowercase(Locale.ROOT) }
        fun col(vararg names: String): Int = names.firstNotNullOfOrNull { n -> head.indexOf(n).takeIf { it >= 0 } }
            ?: names.firstNotNullOfOrNull { n -> head.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } } ?: -1
        val t = col("track name", "song title", "title", "song", "track", "name")
        val a = col("artist name(s)", "artist names", "artist name", "artists", "artist", "creator")
        if (t < 0) return emptyList()
        return lines.drop(1).mapNotNull { line ->
            val c = cells(line, sep)
            val title = c.getOrNull(t)?.trim().orEmpty()
            if (title.isEmpty()) null else Wanted(title, c.getOrNull(a)?.trim().orEmpty(), "")
        }
    }

    /** One CSV line's cells, with quoted cells kept whole. */
    private fun cells(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    private fun htmlDecode(s: String) = s.replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    /** A path or web address down to the file's own name, without its ending. */
    private fun fileOf(path: String): String {
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        return decoded.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    }

    /** "Artist - Title" (as most players write a song's name), as artist and title. */
    private fun splitArtistTitle(s: String): Pair<String, String> {
        val parts = s.split(" - ", " – ", " — ", limit = 2)
        return if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to s.trim()
    }

    // Matching to your music ---------------------------------------------------------------------

    /** A name made plain for matching: no brackets, "feat.", remaster notes, "the", or punctuation. */
    fun plain(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
        .replace(Regex("\\s[-–—]\\s.*(remaster|version|edit|mix|live|mono|stereo|single).*$"), " ")
        .replace(Regex("\\b(feat|ft|featuring)\\b.*$"), " ")
        .replace("&", " and ")
        .replace(Regex("^the\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun firstArtist(s: String): String = plain(s.split(Regex(",|;|/|\\s&\\s|\\sx\\s|\\sand\\s|\\sfeat|\\sft\\.?", RegexOption.IGNORE_CASE)).first())

    /** Finds each wanted song in [tracks]; only a clear match counts. */
    fun match(wanted: List<Wanted>, tracks: List<Track>): List<Track?> {
        val byBoth = HashMap<String, Track>()
        val byTitle = HashMap<String, MutableList<Track>>()
        val byFile = HashMap<String, Track>()
        for (t in tracks) {
            val title = plain(t.title)
            byBoth.putIfAbsent(title + "|" + firstArtist(t.artist), t)
            if (t.albumArtist.isNotBlank()) byBoth.putIfAbsent(title + "|" + firstArtist(t.albumArtist), t)
            byTitle.getOrPut(title) { ArrayList() } += t
            byFile.putIfAbsent(plain(t.fileName.substringBeforeLast('.')), t)
        }
        return wanted.map { w ->
            val title = plain(w.title)
            val artist = firstArtist(w.artist)
            when {
                title.isNotEmpty() && artist.isNotEmpty() && byBoth[title + "|" + artist] != null -> byBoth[title + "|" + artist]
                w.file.isNotEmpty() && byFile[plain(w.file)] != null -> byFile[plain(w.file)]
                // A file named "Artist - Title".
                w.file.isNotEmpty() && w.title.isEmpty() -> splitArtistTitle(w.file).let { (a, t) ->
                    byBoth[plain(t) + "|" + firstArtist(a)] ?: byTitle[plain(t)]?.singleOrNull()
                }
                // No artist given, or the artist written differently: the title alone, when only one song has it.
                title.isNotEmpty() -> byTitle[title]?.let { list -> if (list.size == 1) list[0] else list.firstOrNull { firstArtist(it.artist).let { a -> artist.isNotEmpty() && (a.contains(artist) || artist.contains(a)) } } }
                else -> null
            }
        }
    }

    /** Makes a playlist from another player's file. */
    fun bringIn(fileName: String, text: String, tracks: List<Track>): Brought? {
        val (named, wanted) = read(fileName, text)
        if (wanted.isEmpty()) return null
        val found = match(wanted, tracks)
        val ids = found.filterNotNull().map { it.id }
        val name = named.ifBlank { fileName.substringBeforeLast('.').ifBlank { "Brought in" } }
        val p = create(name, ids)
        val missing = wanted.indices.filter { found[it] == null }.map { wanted[it].words() }
        return Brought(p, ids.size, missing)
    }

    /** The playlist as an M3U8 file, for another player: each song by artist and title, with its file's name. */
    fun m3u8(p: Playlist, tracks: Map<String, Track>): String = buildString {
        append("#EXTM3U\n#PLAYLIST:").append(p.name).append('\n')
        for (id in p.ids) {
            val t = tracks[id] ?: continue
            append("#EXTINF:").append(t.durationMs / 1000).append(',').append(t.artist).append(" - ").append(t.title).append('\n')
            append(t.fileName).append('\n')
        }
    }
}
