package art.kquinn.kuesounds

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Looking songs and albums up in MusicBrainz, the free, open catalogue of the world's music, to put
 * the right album name, artist and year on them, and fetching each album's cover from its picture
 * library (the Cover Art Archive). No account or key is needed. MusicBrainz asks for no more than one
 * question a second, so the lookups are spaced out.
 */
object Catalogue {
    private const val SEARCH = "https://musicbrainz.org/ws/2/"
    private const val COVERS = "https://coverartarchive.org/"
    /** MusicBrainz asks every app to say who it is. */
    const val WHO = "KueSounds/1.0 ( https://github.com/kevinquinnart-bit/Kue )"

    /** One release (an edition of an album) found in the catalogue. */
    data class Release(
        val id: String,
        val groupId: String,
        val score: Int,
        val title: String,
        val artist: String,
        val date: String,
        val trackCount: Int,
        val type: String,
        val otherTypes: List<String>,
        val status: String,
    ) {
        val year: Int get() = Library.year(date)
    }

    /** What a lookup decided: the album's proper name, artist and year, and where its cover is. */
    /** [compilation]: the catalogue files the record as a compilation (a greatest hits, a best of, a hits collection). */
    data class Match(val title: String, val artist: String, val year: Int, val releaseId: String, val groupId: String, val compilation: Boolean = false)

    private var lastAsked = 0L

    /** A search answers in a second or two; one taking over 25 seconds is asked again rather than waited on. */
    private val quick by lazy { Drive.http.newBuilder().readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build() }

    /** Writes a value into a search so its punctuation is read as words, not as search commands. */
    fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun ask(path: String, query: String, limit: Int = 10): String =
        askAt(
            (SEARCH + path).toHttpUrl().newBuilder().addQueryParameter("query", query).addQueryParameter("fmt", "json")
                .addQueryParameter("limit", limit.toString()).build(),
        )

    /**
     * Asks MusicBrainz at [url], at most once a second, waiting and asking again while it is busy.
     * [patient]: wait as long as it takes (fixing, where a song left out means pressing Fix again). Otherwise
     * (looking up while scanning) give up on this one after about half a minute: it is looked up next time, and
     * the scan's own pause after several failures in a row covers the catalogue being down. Measured on Kevin's
     * phone 23-24 Nov 2026: every busy answer cost 7 to 8 minutes on the patient waits, about 60 of them across
     * the three days of looking up.
     */
    @Synchronized
    private fun askAt(url: okhttp3.HttpUrl, patient: Boolean = false): String {
        // One question a second at most, as MusicBrainz asks.
        val wait = lastAsked + 1100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastAsked = System.currentTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", WHO).header("Accept", "application/json").build()
        // Busy or not answering: wait longer each time (3, 6, 12, 24, 45 seconds, then a minute and two), or as
        // long as MusicBrainz says to. Not answering in time counts as busy: on 23 November 2026 eight searches
        // in a row went unanswered for a minute each, and the looking up stopped.
        val waits = if (patient) longArrayOf(3_000, 6_000, 12_000, 24_000, 45_000, 60_000, 120_000) else longArrayOf(3_000, 8_000, 20_000)
        var why = "stayed busy"
        for (attempt in waits.indices) {
            var told = 0L
            try {
                quick.newCall(request).execute().use { r ->
                    if (r.isSuccessful) return r.body.string()
                    if (r.code != 503 && r.code != 429 && r.code < 500) throw IOException("MusicBrainz answered " + r.code)
                    why = "answered " + r.code
                    told = (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
                }
            } catch (e: java.io.InterruptedIOException) {
                why = "didn't answer in time"
            } catch (e: java.net.ConnectException) {
                why = "couldn't be reached"
            } catch (e: java.net.UnknownHostException) {
                why = "couldn't be reached"
            }
            Thread.sleep(maxOf(waits[attempt], minOf(told, if (patient) 120_000L else 20_000L)))
            lastAsked = System.currentTimeMillis()
        }
        throw IOException("MusicBrainz " + why)
    }

    private fun creditOf(o: JsonObject): String =
        o.getAsJsonArray("artist-credit")?.joinToString("") { el ->
            val c = el.asJsonObject
            (c.get("name")?.asString ?: c.getAsJsonObject("artist")?.get("name")?.asString.orEmpty()) + (c.get("joinphrase")?.asString.orEmpty())
        }.orEmpty()

    /**
     * [songArtist]: the found song's own artist. In a song search MusicBrainz leaves the artist off each of the
     * artist's own releases (it only names one when it differs, such as Various Artists), so a release with no
     * artist is the song's artist's (seen 23 November 2026: every Go West release of "We Close Our Eyes").
     */
    private fun releaseOf(o: JsonObject, score: Int, songArtist: String = ""): Release? {
        val id = o.get("id")?.asString ?: return null
        val group = o.getAsJsonObject("release-group")
        return Release(
            id = id,
            groupId = group?.get("id")?.asString.orEmpty(),
            score = o.get("score")?.asInt ?: score,
            title = o.get("title")?.asString.orEmpty().replace('’', '\''),
            artist = creditOf(o).ifBlank { songArtist }.replace('’', '\''),
            date = o.get("date")?.asString.orEmpty(),
            trackCount = o.get("track-count")?.asInt ?: 0,
            type = group?.get("primary-type")?.asString.orEmpty(),
            otherTypes = group?.getAsJsonArray("secondary-types")?.map { it.asString }.orEmpty(),
            status = o.get("status")?.asString.orEmpty(),
        )
    }

    /** Reads MusicBrainz's answer to an album search. */
    fun parseReleases(json: String): List<Release> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.getAsJsonArray("releases")?.mapNotNull { releaseOf(it.asJsonObject, 0) }.orEmpty()
    }

    /** Reads MusicBrainz's answer to a song search: the albums each found song is on. */
    fun parseSongReleases(json: String): List<Pair<Int, Release>> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.getAsJsonArray("recordings")?.flatMap { el ->
            val rec = el.asJsonObject
            val score = rec.get("score")?.asInt ?: 0
            val songArtist = creditOf(rec)
            rec.getAsJsonArray("releases")?.mapNotNull { r -> releaseOf(r.asJsonObject, score, songArtist)?.let { score to it } }.orEmpty()
        }.orEmpty()
    }

    private fun sameArtist(a: String, b: String): Boolean {
        val x = Library.sortName(a)
        val y = Library.sortName(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    fun isVarious(name: String) = Library.sortName(name) in setOf("various artists", "various", "various artist", "va")

    /**
     * Picks the right release for one of the person's albums, or none. It must be a close match on
     * name and artist; then a proper album over a single, an official release over a bootleg, the
     * number of songs nearest to the person's, and the earliest date.
     */
    fun choose(found: List<Release>, artist: String, songs: Int): Release? {
        val known = artist.isNotBlank() && artist != Library.UNKNOWN_ARTIST
        val fits = found.filter { r ->
            r.score >= 90 && when {
                !known -> r.trackCount == songs
                isVarious(artist) -> isVarious(r.artist)
                else -> sameArtist(r.artist, artist)
            }
        }
        return fits.minWithOrNull(best(songs))
    }

    /** A proper album over a single, official over a bootleg, the nearest number of songs, then the earliest. */
    private fun best(songs: Int) = compareBy<Release>(
        { if (it.type.equals("Album", true)) 0 else if (it.type.isEmpty()) 1 else 2 },
        { if (it.status.isEmpty() || it.status.equals("Official", true)) 0 else 1 },
        { kotlin.math.abs(it.trackCount - songs) },
        { if (it.year > 0) it.year else 9999 },
    )

    /**
     * The second try, for an album the exact search missed because its name is written a little differently in the
     * files ("A Million Lovesongs" for "A Million Love Songs", "A Mothers Son" for "A Mother's Son"; both found no
     * match on 25 November 2026 and so no cover). The artist's releases with any of the words, kept only when the
     * name is the same once spaces and punctuation are left out, or differs by one or two letters in a long name.
     */
    fun chooseLoose(found: List<Release>, title: String, artist: String, songs: Int): Release? {
        val want = compact(title)
        if (want.length < 3) return null
        return found.filter { r -> sameArtist(r.artist, artist) && nearlySame(compact(r.title), want) }.minWithOrNull(best(songs))
    }

    /** A name with only its letters and numbers, "&" read as "and": "A Million Love-Songs" is "amillionlovesongs". */
    fun compact(name: String): String = Library.sortName(name).replace("&", "and").filter { it.isLetterOrDigit() }

    private fun nearlySame(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 10 || kotlin.math.abs(a.length - b.length) > 2) return false
        // Letters to change, add or take away to make one the other: 2 at most.
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length] <= 2
    }

    /**
     * Picks the album a loose song comes from: the artist's own official album, not a live or
     * compilation record, earliest first.
     */
    fun chooseForSong(found: List<Pair<Int, Release>>, artist: String): Release? =
        found.filter { (score, r) ->
            score >= 95 && sameArtist(r.artist, artist) && r.type.equals("Album", true) && r.otherTypes.isEmpty() &&
                (r.status.isEmpty() || r.status.equals("Official", true))
        }.map { it.second }.minByOrNull { if (it.year > 0) it.year else 9999 }

    /** Looks an album up. Call off the main thread. */
    fun findAlbum(title: String, artist: String, songs: Int): Match? {
        val known = artist.isNotBlank() && artist != Library.UNKNOWN_ARTIST
        val query = "release:" + quote(title) + if (known) " AND artist:" + quote(if (isVarious(artist)) "Various Artists" else artist) else ""
        val r = choose(parseReleases(ask("release/", query)), artist, songs)
            ?: if (known && !isVarious(artist)) {
                // The words loose, not as one phrase; punctuation out, so nothing in the name is read as a search command.
                val words = title.replace(Regex("""[^\p{L}\p{N}\s]+"""), " ").trim()
                if (words.isEmpty()) null
                else chooseLoose(parseReleases(ask("release/", "artist:" + quote(artist) + " AND release:(" + words + ")", 25)), title, artist, songs)
            } else null
        if (r == null) return null
        return Match(r.title, r.artist, r.year, r.id, r.groupId, compilation = r.otherTypes.any { it.equals("Compilation", true) })
    }

    /**
     * Whether an album's name says it is a greatest hits or other collection, for one the catalogue doesn't file
     * as a compilation. Kevin wants none of those: each song goes to the album it first came out on, or its single.
     */
    fun looksLikeHits(title: String): Boolean = hitsWords.containsMatchIn(
        title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim(),
    )

    /**
     * The words that mark a greatest hits or collection. 24 November 2026: more added from the albums on Kevin's
     * report that stayed as collections ("20 Golden Irish Ballads", "25th Ann. Coll.", "Super Top 50",
     * "Welcome to My World [16 CD Set]", "As 20 Mais" and others). "Golden Hour" and "The Best Damn Thing" are albums.
     */
    private val hitsWords = Regex(
        """\b(greatest hits|biggest hits|hits|best of|very best|the best (vol|volume)|collection|coll|anthology|the essential|essentials|""" +
            """definitive|number ones|no 1s|number 1s|mastermix|megamix|anthems|the ultimate|retrospective|the singles|gold|classics|""" +
            """box set|\d+ cd set|top \d+|\d+th anniversary|\d+th ann|tribute to|absolute|love songs|select mix|now that s what i call|""" +
            """\d{2,3} (mais|melhores|great|greatest|golden|best|top|rebel|irish|songs|ballads|classic|classics|memories|favourites|favorites|biggest))\b""",
    )

    /** Looks a loose song up, to find the album it comes from. Call off the main thread. */
    fun findSong(title: String, artist: String): Match? {
        if (artist.isBlank() || artist == Library.UNKNOWN_ARTIST) return null
        val r = chooseForSong(parseSongReleases(ask("recording/", "recording:" + quote(title) + " AND artist:" + quote(artist))), artist) ?: return null
        return Match(r.title, r.artist, r.year, r.id, r.groupId)
    }

    /**
     * Where a song properly belongs: the artist's own studio album it first came out on, and the single
     * it came out as (if it did). Either can be missing.
     */
    data class SongHome(val album: Match?, val single: Match?, val firstOut: Match? = null)

    /**
     * Step 3 (Kevin, 26 November 2026): the catalogue's own spelling of an artist close to [name], or null when none is
     * close. Close: the same once spaces, signs and "the" are left out, or a letter or two off in a longer name.
     */
    fun closestArtist(name: String): String? {
        if (name.isBlank() || name == Library.UNKNOWN_ARTIST || isVarious(name)) return null
        return chooseArtist(parseArtists(ask("artist", quote(name), 5)), name)
    }

    /** Reads an artist search: each artist's name and score. */
    fun parseArtists(json: String): List<Pair<String, Int>> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("artists")?.mapNotNull { e ->
            val o = e.asJsonObject
            val n = o.get("name")?.asString ?: return@mapNotNull null
            n to (o.get("score")?.asInt ?: 0)
        }.orEmpty()

    fun chooseArtist(found: List<Pair<String, Int>>, name: String): String? {
        val want = compact(name).removePrefix("the")
        return found.filter { it.second >= 80 }.firstOrNull { (n, _) ->
            val got = compact(n).removePrefix("the")
            got == want || (want.length >= 6 && edits(got, want) <= (if (want.length >= 12) 2 else 1))
        }?.first?.replace('’', '\'')
    }

    /** How many letters must change to turn one name into the other. */
    fun edits(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val keep = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = keep
            }
        }
        return row[b.length]
    }

    /** Reads a song search: each found release with the found song's own title and its score. */
    fun parseRecordings(json: String): List<Triple<Int, String, Release>> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.getAsJsonArray("recordings")?.flatMap { el ->
            val rec = el.asJsonObject
            val score = rec.get("score")?.asInt ?: 0
            val title = rec.get("title")?.asString.orEmpty().replace('’', '\'')
            val songArtist = creditOf(rec)
            rec.getAsJsonArray("releases")?.mapNotNull { r -> releaseOf(r.asJsonObject, score, songArtist)?.let { Triple(score, title, it) } }.orEmpty()
        }.orEmpty()
    }

    /** A song's name without anything in brackets, for comparing ("Wonderwall (remastered)" is "wonderwall"). */
    private fun bare(title: String): String = Library.sortName(title.replace(Regex("""\s*[\[(][^\])]*[\])]"""), ""))

    /**
     * Picks a song's home from a song search. Only the artist's own official releases count, and only
     * plain ones: no live, compilation, remix or soundtrack records. The album is the earliest such album
     * with the song on it; the single is the earliest single named after the song.
     */
    fun chooseSongHome(found: List<Triple<Int, String, Release>>, title: String, artist: String): SongHome {
        val name = bare(title)
        val plain = found.filter { (score, song, r) ->
            score >= 85 && bare(song) == name && sameArtist(r.artist, artist) && r.otherTypes.isEmpty() &&
                (r.status.isEmpty() || r.status.equals("Official", true))
        }.map { it.third }
        val byYear = compareBy<Release> { if (it.year > 0) it.year else 9999 }
        val album = plain.filter { it.type.equals("Album", true) }.minWithOrNull(byYear)
        val single = plain.filter { it.type.equals("Single", true) && bare(it.title).startsWith(name) }.minWithOrNull(byYear)
        // Where the song first came out at all, a collection included: when it has no album or single of its own,
        // that record's cover is its picture (Kevin, 25 November 2026: George Michael's "A Moment With You" first came
        // out on Ladies & Gentlemen, a best of, so it found no home and no cover).
        val first = found.filter { (score, song, r) ->
            score >= 85 && bare(song) == name && sameArtist(r.artist, artist) && (r.status.isEmpty() || r.status.equals("Official", true)) &&
                r.otherTypes.none { it.equals("Live", true) || it.equals("Remix", true) }
        }.map { it.third }.minWithOrNull(byYear)
        return SongHome(
            album?.let { Match(it.title, it.artist, it.year, it.id, it.groupId) },
            single?.let { Match(it.title, it.artist, it.year, it.id, it.groupId) },
            first?.let { Match(it.title, it.artist, it.year, it.id, it.groupId, compilation = it.otherTypes.any { t -> t.equals("Compilation", true) }) },
        )
    }

    /** Looks a song up, to find the album it first came out on and the single it came out as. Call off the main thread. */
    fun findSongHome(title: String, artist: String): SongHome {
        if (artist.isBlank() || artist == Library.UNKNOWN_ARTIST || isVarious(artist)) return SongHome(null, null)
        return chooseSongHome(parseRecordings(ask("recording/", songQuery(title, artist), 15)), title, artist)
    }

    /**
     * The song search. The name is asked for without anything in brackets: the catalogue's own name for a song
     * often differs inside them, and then the song isn't found at all (23 November 2026: "Take My Breath Away
     * (Love Theme From Top Gun)" by Berlin found one stray copy; "Take My Breath Away" found Count Three & Pray).
     */
    fun songQuery(title: String, artist: String): String {
        val plain = title.replace(Regex("""\s*[\[(][^\])]*[\])]"""), " ").replace(Regex("\\s+"), " ").trim().ifBlank { title }
        return "recording:" + quote(plain) + " AND artist:" + quote(artist) + " AND status:official AND (primarytype:album OR primarytype:single)"
    }

    /** One song on a release, as the catalogue writes it: its disc and place, its proper name and its artist. */
    data class Song(val disc: Int, val position: Int, val title: String, val artist: String)

    /** Reads a release's list of songs. */
    fun parseReleaseSongs(json: String): List<Song> {
        val root = JsonParser.parseString(json).asJsonObject
        return root.getAsJsonArray("media")?.flatMap { m ->
            val medium = m.asJsonObject
            val disc = medium.get("position")?.asInt ?: 1
            medium.getAsJsonArray("tracks")?.mapNotNull { t ->
                val o = t.asJsonObject
                val title = (o.get("title")?.asString ?: o.getAsJsonObject("recording")?.get("title")?.asString).orEmpty().replace('’', '\'')
                if (title.isBlank()) return@mapNotNull null
                val artist = creditOf(o).ifBlank { o.getAsJsonObject("recording")?.let { creditOf(it) }.orEmpty() }.replace('’', '\'')
                Song(disc, o.get("position")?.asInt ?: 0, title, artist)
            }.orEmpty()
        }.orEmpty()
    }

    /** Every song on the release [releaseId], with its proper name and artist. Call off the main thread. */
    fun releaseSongs(releaseId: String): List<Song> =
        parseReleaseSongs(
            askAt(
                (SEARCH + "release/" + releaseId).toHttpUrl().newBuilder().addQueryParameter("inc", "recordings+artist-credits")
                    .addQueryParameter("fmt", "json").build(),
                patient = true,
            ),
        )

    /**
     * Which song on a release a file is: the one with the same name, brackets aside ("Wonderwall (Remastered)"
     * is "Wonderwall"). Null when none has it, so a file is never given another song's name.
     */
    fun songFor(songs: List<Song>, title: String): Song? {
        val name = bare(title)
        if (name.isEmpty()) return null
        return songs.firstOrNull { bare(it.title) == name } ?: songs.firstOrNull { Library.sortName(it.title) == Library.sortName(title) }
    }

    /** Fetches the album's front cover (500 dots across). Null when the catalogue has none. */
    fun cover(m: Match): ByteArray? {
        val tries = listOfNotNull(m.groupId.takeIf { it.isNotEmpty() }?.let { COVERS + "release-group/$it/front-500" }, COVERS + "release/${m.releaseId}/front-500")
        for (url in tries) {
            val request = Request.Builder().url(url).header("User-Agent", WHO).build()
            Drive.http.newCall(request).execute().use { r ->
                if (r.isSuccessful) return r.body.bytes()
                if (r.code != 404) throw IOException("the cover library answered " + r.code)
            }
        }
        return null
    }

    // ---- What the catalogue says about an album, and its artist's picture (Kevin, 25 November 2026) ----------------

    /** An album's kinds of music, first date out, type and main artist, from its release group. Call off the main thread. */
    fun groupFacts(groupId: String): AlbumFacts =
        parseGroupFacts(
            askAt(
                (SEARCH + "release-group/" + groupId).toHttpUrl().newBuilder().addQueryParameter("inc", "genres+tags+artist-credits")
                    .addQueryParameter("fmt", "json").build(),
            ),
        )

    fun parseGroupFacts(json: String): AlbumFacts {
        val o = JsonParser.parseString(json).asJsonObject
        fun named(key: String): List<Pair<String, Int>> = o.getAsJsonArray(key)?.mapNotNull { e ->
            val g = e.asJsonObject
            val name = g.get("name")?.asString?.trim().orEmpty()
            if (name.isEmpty()) null else name to (g.get("count")?.asInt ?: 0)
        }.orEmpty()
        // Genres are MusicBrainz's own list; tags are anyone's words, used only when there are no genres, and only
        // ones more than one person agreed on.
        val genres = named("genres").sortedByDescending { it.second }.map { it.first }
            .ifEmpty { named("tags").filter { it.second >= 2 }.sortedByDescending { it.second }.map { it.first } }
            .take(3)
        val primary = o.get("primary-type")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val secondary = o.getAsJsonArray("secondary-types")?.mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asString }.orEmpty()
        val artistId = o.getAsJsonArray("artist-credit")?.firstOrNull()?.asJsonObject?.getAsJsonObject("artist")?.get("id")?.asString.orEmpty()
        return AlbumFacts(
            genres = genres,
            firstOut = o.get("first-release-date")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            type = (listOf(primary) + secondary).filter { it.isNotBlank() }.joinToString(", "),
            artistId = artistId,
        )
    }

    /** The label, catalogue number, country, barcode and date of the release [releaseId]. Call off the main thread. */
    fun releaseFacts(releaseId: String, into: AlbumFacts): AlbumFacts =
        parseReleaseFacts(
            askAt(
                (SEARCH + "release/" + releaseId).toHttpUrl().newBuilder().addQueryParameter("inc", "labels")
                    .addQueryParameter("fmt", "json").build(),
            ),
            into,
        )

    fun parseReleaseFacts(json: String, into: AlbumFacts): AlbumFacts {
        val o = JsonParser.parseString(json).asJsonObject
        val info = o.getAsJsonArray("label-info")?.firstOrNull()?.asJsonObject
        fun str(x: JsonObject?, key: String) = x?.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return into.copy(
            label = str(info?.getAsJsonObject("label"), "name").takeIf { it != "[no label]" }.orEmpty(),
            catalogueNo = str(info, "catalog-number").takeIf { it != "[none]" }.orEmpty(),
            country = str(o, "country"),
            barcode = str(o, "barcode"),
            released = str(o, "date"),
            detailed = true,
        )
    }

    /**
     * A picture of the artist or group, free to use: the catalogue's link to Wikidata, then the picture Wikidata
     * gives for them, from Wikimedia Commons. Null when there is none. Call off the main thread.
     */
    fun artistPicture(artistId: String): ByteArray? {
        val json = askAt(
            (SEARCH + "artist/" + artistId).toHttpUrl().newBuilder().addQueryParameter("inc", "url-rels").addQueryParameter("fmt", "json").build(),
        )
        val wikidata = wikidataOf(json) ?: return null
        val entity = Request.Builder().url("https://www.wikidata.org/wiki/Special:EntityData/$wikidata.json").header("User-Agent", WHO).build()
        val file = Drive.http.newCall(entity).execute().use { r -> if (r.isSuccessful) pictureNameOf(r.body.string(), wikidata) else null } ?: return null
        val url = "https://commons.wikimedia.org/wiki/Special:FilePath/" + java.net.URLEncoder.encode(file.replace(' ', '_'), "UTF-8").replace("+", "%20") + "?width=500"
        val picture = Request.Builder().url(url).header("User-Agent", WHO).build()
        return Drive.http.newCall(picture).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }

    /** The Wikidata item ("Q123") an artist in the catalogue links to. */
    fun wikidataOf(json: String): String? =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("relations")?.firstNotNullOfOrNull { r ->
            val o = r.asJsonObject
            if (o.get("type")?.asString != "wikidata") null
            else o.getAsJsonObject("url")?.get("resource")?.asString?.substringAfterLast('/')?.takeIf { it.startsWith("Q") }
        }

    /** The Commons file named as the item's picture (its "image", P18). */
    fun pictureNameOf(json: String, item: String): String? = runCatching {
        JsonParser.parseString(json).asJsonObject.getAsJsonObject("entities").getAsJsonObject(item).getAsJsonObject("claims")
            .getAsJsonArray("P18")?.firstOrNull()?.asJsonObject?.getAsJsonObject("mainsnak")?.getAsJsonObject("datavalue")?.get("value")?.asString
    }.getOrNull()
}
