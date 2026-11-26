package art.kquinn.kuesounds

import com.google.gson.Gson
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** One music file in the Drive, with what is known about it. */
data class Track(
    val id: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val folderId: String?,
    val modified: String,
    var title: String,
    var artist: String,
    var album: String,
    var albumArtist: String,
    var trackNo: Int,
    var discNo: Int,
    var year: Int,
    var durationMs: Long,
    var genre: String,
    /** True once the song's own details (its tags) have been read from the file. */
    var tagged: Boolean,
    /** True once its album has been looked up in the music catalogue (MusicBrainz), found or not. */
    var checked: Boolean = false,
    /** True once the right cover and album details have been written into the file in the Drive. */
    var fixed: Boolean = false,
    /**
     * True once the file was fixed the full way: its own song name and artist put right, everything else
     * inside it taken out, then the album and cover written. Files fixed before that are fixed again.
     */
    var fixedFully: Boolean = false,
    /** True once the song has been looked up on its own (a song from a compilation, or with no album). */
    var ownLook: Boolean = false,
    /** True once the song has been looked up to see whether it came out as a single. */
    var singleChecked: Boolean = false,
    /** True once Kevin has put the song's name and artist right himself: fixing the files keeps them. */
    var named: Boolean = false,
    /** Who wrote it, from the file (null until read; libraries kept before 25 Nov 2026 have no value here). */
    var composer: String? = null,
    /** True once [composer] has been read from the file, found or not. */
    var composerRead: Boolean = false,
)

/**
 * What the music catalogue says about an album, beyond its name and cover (Kevin, 25 November 2026: "add all info for
 * the song or album ... date released etc", and the kind of music found by KueSounds itself).
 */
data class AlbumFacts(
    /** Kinds of music, most agreed on first ("pop", "dance-pop"). */
    val genres: List<String> = emptyList(),
    /** When it first came out: "1994-11-14", "1994-11" or "1994". */
    val firstOut: String = "",
    /** Album, Single, EP, with Compilation, Live or Soundtrack after it when it is one. */
    val type: String = "",
    /** The record label, catalogue number, country and barcode of the release matched (asked when Info is opened). */
    val label: String = "",
    val catalogueNo: String = "",
    val country: String = "",
    val barcode: String = "",
    /** The release's own date (a reissue can be years after [firstOut]). */
    val released: String = "",
    /** The catalogue's id for the album's main artist, for their picture. */
    val artistId: String = "",
    /** True once the label and the rest have been asked for. */
    val detailed: Boolean = false,
)

/** A folder in the Drive: its name and the folder it sits in. */
data class Folder(val id: String, val name: String, val parent: String?)

/** One album: the songs that share an album name and an artist. */
data class Album(val key: String, val title: String, val artist: String, val year: Int, val tracks: List<Track>)

/** One artist and their albums. */
data class Artist(val name: String, val albums: List<Album>, val songCount: Int)

/** Everything KueSounds knows about the music, kept on the phone between visits. */
data class LibraryData(
    val tracks: List<Track> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Album key to the file holding its cover picture on this phone. */
    val art: Map<String, String> = emptyMap(),
    /** Folder id to the Drive file of a cover picture found in it (cover.jpg, folder.jpg, front.jpg). */
    val folderCovers: Map<String, String> = emptyMap(),
    val scannedAt: Long = 0,
    /** Album key to the catalogue's release it was matched to ("release id|release group id"). */
    val releases: Map<String, String> = emptyMap(),
    /** Albums whose cover came from the catalogue. These win over a picture inside the files. */
    val catalogueCovers: Set<String> = emptySet(),
    /** Which way the kept library is laid out, so an older one can be brought up to date. */
    val version: Int = 0,
    /** Song id to the name of the single it came out as. */
    val singleTitles: Map<String, String> = emptyMap(),
    /** Song id to the file holding its single's cover on this phone. */
    val singleArt: Map<String, String> = emptyMap(),
    /** Album keys the catalogue files as a greatest hits, best of or other compilation. */
    val collections: Set<String> = emptySet(),
    /**
     * Album keys of songs made a single of their own: songs off a greatest hits or compilation for which the
     * catalogue has neither an album nor a single (Kevin, 24 November 2026).
     */
    val ownSingles: Set<String> = emptySet(),
    /** Song id to how many times fixing its file has failed (0.1.52 only; no longer used). */
    val fixFailures: Map<String, Int> = emptyMap(),
    /** Song id to why its file couldn't be fixed last time, in plain words. Listed in Settings until it is fixed. */
    val fixProblems: Map<String, String> = emptyMap(),
    /** Songs already listened to by Match by sound (found or not), so each is asked about once. */
    val soundChecked: Set<String> = emptySet(),
    /** Album key to what the catalogue says about it (see [AlbumFacts]). Asked once each. */
    val facts: Map<String, AlbumFacts> = emptyMap(),
    /** Songs whose name and artist were found by their sound: their album counts as found (Kevin, 26 Nov 2026). */
    val soundMatched: Set<String> = emptySet(),
    /** Artist name to the file holding their photo on this phone; "" once looked for and none found. */
    val artistPictures: Map<String, String> = emptyMap(),
)

object Library {
    /**
     * A song's kind of music: what its file says, or else what the catalogue says about its album (Kevin, 25 November
     * 2026: KueSounds to pick it out itself). Written the way people write it: "Dance-Pop", "80's".
     */
    fun genreOf(t: Track, d: LibraryData): String {
        val own = t.genre.trim()
        if (own.isNotEmpty() && !own.all { it.isDigit() || it in "()" }) return own
        val facts = d.facts[albumKey(albumArtistOf(t), t.album)] ?: return ""
        return facts.genres.firstOrNull()?.let { tidyGenre(it) }.orEmpty()
    }

    /** "dance-pop" as "Dance-Pop", "hip hop" as "Hip Hop". */
    fun tidyGenre(name: String): String =
        name.trim().split(" ").joinToString(" ") { w -> w.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercaseChar() } } }

    /** Every kind of music in [albums], each with its albums (an album with songs of two kinds is under both), A to Z. */
    fun genres(albums: List<Album>, d: LibraryData): List<Pair<String, List<Album>>> {
        val byGenre = LinkedHashMap<String, MutableList<Album>>()
        for (al in albums) {
            val kinds = al.tracks.map { genreOf(it, d) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            for (k in kinds) {
                val key = byGenre.keys.firstOrNull { it.equals(k, ignoreCase = true) } ?: k
                byGenre.getOrPut(key) { mutableListOf() }.add(al)
            }
        }
        return byGenre.entries.sortedBy { sortName(it.key) }.map { it.key to it.value.toList() }
    }

    /** Every writer named in the songs' files, each with their songs, A to Z. */
    fun composers(tracks: List<Track>): List<Pair<String, List<Track>>> =
        tracks.filter { !it.composer.isNullOrBlank() }.groupBy { it.composer!!.trim() }.entries
            .sortedBy { sortName(it.key) }.map { it.key to it.value.sortedBy { t -> sortName(t.title) } }

    /** "1994-11-14" as "14 November 1994"; "1994-11" as "November 1994". */
    fun date(text: String): String {
        val bits = text.split("-")
        val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val month = bits.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 }?.let { months[it - 1] }
        val day = bits.getOrNull(2)?.toIntOrNull()
        return listOfNotNull(day?.toString(), month, bits.firstOrNull()?.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    const val UNKNOWN_ARTIST = "Unknown artist"
    const val UNKNOWN_ALBUM = "Unknown album"

    /** The kinds of music file the player can play. */
    val musicEndings = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "aif", "aiff", "mka", "3ga", "amr", "m4b", "mp4a", "alac", "wma")

    fun ending(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Whether a Drive file is music, from its kind and its name. */
    fun isMusic(name: String, mime: String): Boolean {
        val end = ending(name)
        if (mime.startsWith("audio/")) return true
        if (mime == "application/ogg" || mime == "application/x-flac") return true
        return end in musicEndings && end != ""
    }

    private val leadingNumber = Regex("""^\s*(?:(\d{1,2})[-.](\d{1,3})|(\d{1,3}))\s*(?:[-._)]\s*|\s+)(.+)$""")

    /**
     * A first guess at a song's details from its file name and folders, used until its own details are
     * read. "03 - Song.mp3" in "Artist/Album" gives track 3, "Song", "Album", "Artist".
     */
    fun guess(fileName: String, folder: Folder?, parentOfFolder: Folder?): Guess {
        var stem = fileName.substringBeforeLast('.', fileName).trim().replace('_', ' ')
        var trackNo = 0
        var discNo = 0
        leadingNumber.find(stem)?.let { m ->
            val rest = m.groupValues[4].trim()
            // A name that is only a number ("1999.mp3") keeps the number as its title.
            if (rest.isNotEmpty() && rest.any { it.isLetter() }) {
                if (m.groupValues[1].isNotEmpty()) {
                    discNo = m.groupValues[1].toInt()
                    trackNo = m.groupValues[2].toInt()
                } else {
                    trackNo = m.groupValues[3].toInt()
                }
                stem = rest
            }
        }
        var artist = parentOfFolder?.name?.trim().orEmpty()
        val album = folder?.name?.let { cleanAlbum(it, artist).first }.orEmpty()
        if (folder != null) discNo = discNo.takeIf { it > 0 } ?: cleanAlbum(folder.name, artist).second
        var title = stem
        // Loose files named "Artist - Song" give their artist when the folders don't.
        if (artist.isEmpty() && " - " in stem) {
            val bits = stem.split(" - ", limit = 2)
            if (bits[0].isNotBlank() && bits[1].isNotBlank() && !bits[0].trim().equals(album, ignoreCase = true)) {
                artist = bits[0].trim()
                title = bits[1].trim()
            }
        }
        return Guess(
            title = title.ifBlank { fileName },
            artist = artist.ifBlank { UNKNOWN_ARTIST },
            album = album.ifBlank { UNKNOWN_ALBUM },
            trackNo = trackNo,
            discNo = discNo,
        )
    }

    data class Guess(val title: String, val artist: String, val album: String, val trackNo: Int, val discNo: Int)

    /** Reads "3", "3/12" or "03 of 12" as 3. */
    fun number(text: String?): Int = text?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /** Reads "2004", "2004-05-01" or "2004/05" as 2004. Zero when it isn't a plausible year. */
    fun year(text: String?): Int {
        val y = Regex("""(1[89]\d\d|20\d\d)""").find(text.orEmpty())?.value?.toIntOrNull() ?: 0
        return y
    }

    /**
     * The name an artist or album is grouped and sorted by: case, accents, curly quotes, leading
     * punctuation and a leading "The" set aside.
     */
    fun sortName(name: String): String = sortCache.getOrPut(name) { makeSortName(name) }

    /** Sort names are worked out once per name: sorting thousands of songs asks for each many times. */
    private val sortCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun makeSortName(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("""\p{M}"""), "")
            .replace('\u2019', '\'').replace('\u2018', '\'').lowercase(Locale.ROOT).trim()
        return plain.trimStart { !it.isLetterOrDigit() }.removePrefix("the ").trim()
    }

    private val discMark = Regex("""[\[(\-\s]*\b(?:disc|disk|cd)\s*(\d{1,2})\b\s*[\])]?""", RegexOption.IGNORE_CASE)

    /**
     * Tidies an album name taken from a folder or a tag: "1 - Garth Brooks - Ropin' the Wind [Disc 2] (1991)"
     * becomes "Ropin' the Wind", disc 2. Gives back the tidy name and the disc number (0 when none).
     */
    fun cleanAlbum(raw: String, artist: String): Pair<String, Int> {
        var t = raw.replace('_', ' ').trim()
        var disc = 0
        discMark.find(t)?.let {
            disc = it.groupValues[1].toInt()
            t = t.removeRange(it.range)
        }
        t = t.replace(Regex("""^\s*\d{1,3}\s*[-.)]\s+"""), "")
        if (artist.isNotBlank() && artist != UNKNOWN_ARTIST) {
            t = t.replace(Regex("^\\s*" + Regex.escape(artist) + "\\s*[-\u2013:]\\s*", RegexOption.IGNORE_CASE), "")
        }
        t = t.replace(Regex("""\s*[\[(](?:19|20)\d\d[\])]\s*$"""), "")
        // Words about the edition, not the album's name (Kevin's report, 25 November 2026: "Kite (Deluxe Edition)",
        // "Driven By You (2011 Remaster)", "Breathe [Bonus Track]", "Give It Up [Single]" all found nothing in the catalogue).
        t = t.replace(editionPart, "").replace(Regex("""\s+-\s+(?:single|ep)\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+remaster(?:ed)?(?:\s+(?:19|20)\d\d)?\s*$""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""^(?:19|20)\d\d\s*-\s+"""), "")
        t = t.trim().trimStart('-', '+', '_', '.', '~', '*', ' ').trimEnd('-', '+', '_', '~', '*', ' ')
        t = t.replace(Regex("""\s{2,}"""), " ").trim()
        return t.ifBlank { raw.trim() } to disc
    }

    /** A part in brackets that only says which edition it is: remastered, deluxe, expanded, bonus tracks, a single. */
    private val editionPart = Regex(
        """\s*[\[(][^\])]*\b(?:remaster(?:ed)?|deluxe|expanded|edition|bonus|single|anniversary|special|legacy|reissue)\b[^\])]*[\])]""",
        RegexOption.IGNORE_CASE,
    )

    /** An artist without anyone featured ("Jay-Z feat. Alicia Keys" is "jay-z"), for telling artists apart. */
    fun mainArtist(artist: String): String =
        sortName(artist).split(Regex(""" (?:feat\.?|ft\.?|featuring|with|&|and|x|vs\.?) |, """)).first().trim()

    /**
     * Songs that sit on a compilation, though each is in a folder of its own (so they don't group as one
     * album of mixed artists): an album name shared by 3 or more artists, where an artist has only one or
     * two songs under it. An artist with more songs under the name ("Greatest Hits" by Queen) keeps them
     * as their own album.
     */
    fun compilationSongs(tracks: List<Track>): Set<String> {
        val out = HashSet<String>()
        for ((title, list) in tracks.groupBy { matchName(it.album) }) {
            if (title.isEmpty() || title == matchName(UNKNOWN_ALBUM)) continue
            val byArtist = list.groupBy { mainArtist(it.artist) }
            if (byArtist.size < 3) continue
            for ((_, songs) in byArtist) if (songs.size <= 2) songs.forEach { out += it.id }
        }
        return out
    }

    /** A song name without anything in brackets: "Take My Breath Away (Love Theme From Top Gun)" is "Take My Breath Away". */
    fun withoutBrackets(title: String): String =
        title.replace(Regex("""\s*[\[(][^\])]*[\])]"""), " ").replace(Regex("\\s+"), " ").trim().ifBlank { title }

    /**
     * A song off a greatest hits or compilation that the catalogue has no album or single for, filed as a single
     * of its own: the song's name (without the bracketed part) as the album, its own artist as the album artist.
     * Kevin, 24 November 2026: "Every song has come from a particular named album or if that fails a single in
     * its own right." A song whose album changes is fixed again, so the compilation's name and cover come out of the file.
     */
    fun asOwnSingle(t: Track): Track {
        val title = withoutBrackets(t.title)
        val artist = t.artist.ifBlank { UNKNOWN_ARTIST }
        val same = title == t.album && artist == t.albumArtist
        return t.copy(album = title, albumArtist = artist, trackNo = 1, discNo = 0, checked = true, fixed = t.fixed && same)
    }

    /**
     * An album's key: its artist and name with the punctuation left out, so "A Mother's Son" and "A Mothers Son" by
     * the same artist are one album (Kevin, 25 November 2026). "&" counts as "and".
     */
    fun albumKey(artist: String, album: String): String = matchName(artist) + "\u0001" + matchName(album)

    /** A name as albums are told apart: [sortName] with no punctuation, "&" as "and", one space between words. */
    fun matchName(name: String): String = squash(sortName(name))

    /** [matchName] from a name already put through [sortName]: kept libraries' keys are brought up to date with it. */
    fun squash(sorted: String): String =
        sorted.replace("&", " and ").replace(Regex("""[^\p{L}\p{N} ]+"""), "").replace(Regex("""\s+"""), " ").trim().ifEmpty { sorted }

    /** An album key from before 25 November 2026 (punctuation kept) as it is now. Other keys are left as they are. */
    fun newKey(old: String): String {
        val parts = old.split('\u0001')
        return if (parts.size == 2) squash(parts[0]) + "\u0001" + squash(parts[1]) else old
    }

    /** The artist an album belongs to: the album artist when the songs name one. */
    fun albumArtistOf(t: Track): String = t.albumArtist.ifBlank { t.artist }.ifBlank { UNKNOWN_ARTIST }

    /** The songs grouped into albums, each album's songs in disc and track order. Albums in name order. */
    fun albums(tracks: List<Track>): List<Album> {
        // Songs in one folder with one album name are one album, even if their artists differ
        // (a compilation without an album artist).
        val byFolderAlbum = tracks.groupBy { (it.folderId ?: "") + "\u0001" + matchName(it.album) }
        val groups = mutableMapOf<String, MutableList<Track>>()
        for ((_, list) in byFolderAlbum) {
            val artists = list.map { matchName(albumArtistOf(it)) }.distinct()
            val artist = if (artists.size == 1) albumArtistOf(list.first()) else "Various artists"
            val key = albumKey(artist, list.first().album)
            groups.getOrPut(key) { mutableListOf() }.addAll(list)
        }
        return groups.map { (key, list) ->
            val first = list.first()
            val artists = list.map { matchName(albumArtistOf(it)) }.distinct()
            val artist = if (artists.size == 1) albumArtistOf(first) else "Various artists"
            Album(
                key = key,
                title = first.album,
                artist = artist,
                year = list.map { it.year }.filter { it > 0 }.minOrNull() ?: 0,
                tracks = list.sortedWith(compareBy<Track>({ it.discNo }, { if (it.trackNo == 0) Int.MAX_VALUE else it.trackNo }, { sortName(it.title) })),
            )
        }.sortedWith(compareBy({ it.title == UNKNOWN_ALBUM }, { sortName(it.title) }, { sortName(it.artist) }))
    }

    /** Artists with their albums, in name order. */
    fun artists(albums: List<Album>): List<Artist> =
        albums.groupBy { matchName(it.artist) }.map { (_, list) ->
            Artist(list.first().artist, list.sortedWith(compareBy({ it.year == 0 }, { it.year }, { sortName(it.title) })), list.sumOf { it.tracks.size })
        }.sortedWith(compareBy({ it.name == UNKNOWN_ARTIST }, { sortName(it.name) }))

    /** Every song in title order. */
    fun songs(tracks: List<Track>): List<Track> = tracks.sortedWith(compareBy({ sortName(it.title) }, { sortName(it.artist) }))

    /** Songs, albums and artists whose names hold every word typed. */
    fun search(query: String, tracks: List<Track>, albums: List<Album>, artists: List<Artist>): Results {
        val words = sortName(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Results(emptyList(), emptyList(), emptyList())
        fun hit(vararg text: String): Boolean {
            val all = text.joinToString(" ") { sortName(it) }
            return words.all { it in all }
        }
        return Results(
            artists = artists.filter { hit(it.name) }.take(20),
            albums = albums.filter { hit(it.title, it.artist) }.take(40),
            songs = songs(tracks.filter { hit(it.title, it.artist, it.album) }).take(200),
        )
    }

    data class Results(val artists: List<Artist>, val albums: List<Album>, val songs: List<Track>)

    /** "3:42" or "1:02:05". */
    fun clock(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    /**
     * Brings a fresh listing from the Drive together with what was known before: songs already read keep
     * their details unless the file has changed, new songs get a first guess, and songs gone from the
     * Drive are dropped.
     */
    fun merge(old: LibraryData, files: List<Drive.FileInfo>, folders: List<Folder>, folderCovers: Map<String, String>, now: Long): LibraryData {
        val known = old.tracks.associateBy { it.id }
        val folderById = folders.associateBy { it.id }
        val tracks = files.filter { isMusic(it.name, it.mime) }.map { f ->
            val before = known[f.id]
            if (before != null && before.tagged && before.modified == f.modified && before.size == f.size) {
                before
            } else {
                val folder = f.parent?.let { folderById[it] }
                val parent = folder?.parent?.let { folderById[it] }
                val g = guess(f.name, folder, parent)
                Track(
                    id = f.id, fileName = f.name, mime = f.mime, size = f.size, folderId = f.parent, modified = f.modified,
                    title = g.title, artist = g.artist, album = g.album, albumArtist = "", trackNo = g.trackNo, discNo = g.discNo,
                    year = 0, durationMs = 0, genre = "", tagged = false,
                )
            }
        }
        return old.copy(tracks = tracks, folders = folders, folderCovers = folderCovers, scannedAt = now)
    }

    private val gson = Gson()

    fun save(file: File, data: LibraryData) {
        val part = File(file.path + ".part")
        part.writeText(gson.toJson(data))
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
    }

    fun load(file: File): LibraryData =
        runCatching { gson.fromJson(file.readText(), LibraryData::class.java) }.getOrNull()?.let { fixNulls(it) } ?: LibraryData()

    /** Gson can leave a missing list or text empty as null; put them back as empty. */
    private fun fixNulls(d: LibraryData): LibraryData {
        @Suppress("SENSELESS_COMPARISON")
        val tracks = (d.tracks ?: emptyList()).filter { it != null && it.id != null }.map { t ->
            @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
            t.copy(
                fileName = t.fileName ?: "", mime = t.mime ?: "", modified = t.modified ?: "",
                title = t.title ?: (t.fileName ?: ""), artist = t.artist ?: UNKNOWN_ARTIST, album = t.album ?: UNKNOWN_ALBUM,
                albumArtist = t.albumArtist ?: "", genre = t.genre ?: "",
            )
        }
        @Suppress("USELESS_ELVIS")
        return LibraryData(
            tracks, d.folders ?: emptyList(), d.art ?: emptyMap(), d.folderCovers ?: emptyMap(), d.scannedAt,
            d.releases ?: emptyMap(), d.catalogueCovers ?: emptySet(), d.version,
            d.singleTitles ?: emptyMap(), d.singleArt ?: emptyMap(), d.collections ?: emptySet(), d.ownSingles ?: emptySet(),
            d.fixFailures ?: emptyMap(), d.fixProblems ?: emptyMap(), d.soundChecked ?: emptySet(),
        )
    }

    /** The folders inside [parent] (null: the top of the Drive), in name order. */
    fun foldersIn(parent: String?, folders: List<Folder>, tracks: List<Track>): List<Folder> {
        val ids = folders.map { it.id }.toSet()
        // Only folders with music somewhere below them are shown.
        val withMusic = mutableSetOf<String>()
        val byId = folders.associateBy { it.id }
        for (t in tracks) {
            var at = t.folderId
            var guard = 0
            while (at != null && at in byId && guard++ < 64) {
                if (!withMusic.add(at)) break
                at = byId[at]?.parent
            }
        }
        return folders.filter { f ->
            f.id in withMusic && (if (parent == null) f.parent == null || f.parent !in ids else f.parent == parent)
        }.sortedBy { sortName(it.name) }
    }

    /** The songs directly in [folder] (null: loose at the top of the Drive), in track then name order. */
    fun songsIn(folder: String?, folders: List<Folder>, tracks: List<Track>): List<Track> {
        val ids = folders.map { it.id }.toSet()
        return tracks.filter { if (folder == null) it.folderId == null || it.folderId !in ids else it.folderId == folder }
            .sortedWith(compareBy({ it.discNo }, { if (it.trackNo == 0) Int.MAX_VALUE else it.trackNo }, { sortName(it.fileName) }))
    }
}
