package art.kquinn.kuesounds

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Covers, and the album a song is on, from Deezer and Apple's music shop (Kevin, 26 November 2026: "piles of empty
 * album covers"). The MusicBrainz cover library has no picture for a great many records (Derek Ryan's "A Mother's
 * Son" has none there), while Deezer and Apple have them: "Live At The Marquee Cork", "Haunted", "A Mother's Son"
 * were all checked live on 26 November. Both are free and need no key. A find is only taken when its artist and name
 * match the song's, letters and signs aside, so a wrong record is never put on.
 */
object Covers {
    /** A record found: its name, artist, its cover's address (large) and where it came from. */
    data class Hit(val album: String, val artist: String, val cover: String, val from: String)

    private fun get(url: okhttp3.HttpUrl): String? = runCatching {
        Drive.http.newCall(Request.Builder().url(url).header("User-Agent", Catalogue.WHO).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
    }.getOrNull()

    /** Whether two names are the same: equal once spaces, signs and "the" are left out, or a letter or two off in a long one. */
    fun same(a: String, b: String): Boolean {
        val x = Catalogue.compact(Library.withoutBrackets(a)).removePrefix("the")
        val y = Catalogue.compact(Library.withoutBrackets(b)).removePrefix("the")
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val n = maxOf(x.length, y.length)
        return n >= 6 && Catalogue.edits(x, y) <= (if (n >= 12) 2 else 1)
    }

    /** Whether [found] is by [artist]: the same, or one holds the other ("Shane MacGowan" in "Shane MacGowan & Sinead O'Connor"). */
    fun sameArtist(found: String, artist: String): Boolean {
        if (same(found, artist)) return true
        // Each named artist on its own: "The Pogues & Sinead O'Connor" shares Sinead O'Connor with
        // "Shane MacGowan & Sinead O'Connor".
        val split = Regex("\\s*(?:&|,|/|\\+|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)
        val fs = found.split(split).filter { it.isNotBlank() }
        val al = artist.split(split).filter { it.isNotBlank() }
        return fs.any { f -> al.any { a -> same(f, a) } }
    }

    /** An album's cover: Deezer first, then Apple. Null when neither has that album by that artist. Off the main thread. */
    fun album(artist: String, album: String): Hit? {
        for (name in names(artist, album)) {
            val words = (if (artist == Library.UNKNOWN_ARTIST) "" else "$artist ") + Library.withoutBrackets(name)
            val deezer = get("https://api.deezer.com/search/album".toHttpUrl().newBuilder().addQueryParameter("q", words).addQueryParameter("limit", "10").build())
            parseDeezerAlbums(deezer).firstOrNull { same(it.album, name) && sameArtist(it.artist, artist) }?.let { return it }
            val apple = get("https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("term", words).addQueryParameter("entity", "album").addQueryParameter("limit", "10").addQueryParameter("country", "gb").build())
            parseItunes(apple).firstOrNull { same(it.album, name) && sameArtist(it.artist, artist) }?.let { return it }
        }
        return null
    }

    /**
     * The names an album may be sold under: as it is; a single's first side ("Am I Losing You / Waitin' For A Train" is
     * sold as "Am I Losing You"); the name without its artist in front ("Best collection - Survivor").
     */
    fun names(artist: String, album: String): List<String> {
        if (album.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out += album
        if (" / " in album) out += album.substringBefore(" / ").trim()
        if (" - " in album) {
            val (a, b) = album.substringBefore(" - ").trim() to album.substringAfter(" - ").trim()
            if (same(a, artist)) out += b
            if (same(b, artist)) out += a
        }
        return out.filter { it.isNotBlank() }
    }

    /**
     * The album a song is on, with its cover: Deezer's song search, then Apple's. A greatest hits or collection is
     * only taken when nothing else has the song (Kevin: every song on its own named album, or a single of its own).
     */
    fun song(artist: String, title: String): Hit? {
        if (title.isBlank() || artist.isBlank() || artist == Library.UNKNOWN_ARTIST) return null
        val name = Library.withoutBrackets(title)
        val deezer = get("https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "$artist $name").addQueryParameter("limit", "15").build())
        val d = parseDeezerSongs(deezer).filter { same(it.first, name) && sameArtist(it.second.artist, artist) }.map { it.second }
        pick(d)?.let { return it }
        val apple = get("https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("term", "$artist $name").addQueryParameter("entity", "song").addQueryParameter("limit", "15").addQueryParameter("country", "gb").build())
        return pick(parseItunesSongs(apple).filter { same(it.first, name) && sameArtist(it.second.artist, artist) }.map { it.second })
    }

    private fun pick(found: List<Hit>): Hit? = found.firstOrNull { !Catalogue.looksLikeHits(it.album) } ?: found.firstOrNull()

    fun parseDeezerAlbums(json: String?): List<Hit> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val big = o.get("cover_xl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            Hit(o.get("title")?.asString.orEmpty(), o.getAsJsonObject("artist")?.get("name")?.asString.orEmpty(), big, "Deezer")
        }
    }.getOrDefault(emptyList())

    /** Deezer's songs: each song's name with its album. */
    fun parseDeezerSongs(json: String?): List<Pair<String, Hit>> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val al = o.getAsJsonObject("album") ?: return@mapNotNull null
            val big = al.get("cover_xl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            o.get("title")?.asString.orEmpty() to Hit(al.get("title")?.asString.orEmpty(), o.getAsJsonObject("artist")?.get("name")?.asString.orEmpty(), big, "Deezer")
        }
    }.getOrDefault(emptyList())

    fun parseItunes(json: String?): List<Hit> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            val art = o.get("artworkUrl100")?.asString ?: return@mapNotNull null
            Hit(o.get("collectionName")?.asString.orEmpty(), o.get("artistName")?.asString.orEmpty(), art.replace("100x100bb", "1000x1000bb"), "Apple Music")
        }
    }.getOrDefault(emptyList())

    fun parseItunesSongs(json: String?): List<Pair<String, Hit>> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            val art = o.get("artworkUrl100")?.asString ?: return@mapNotNull null
            o.get("trackName")?.asString.orEmpty() to Hit(
                o.get("collectionName")?.asString.orEmpty().removeSuffix(" - Single").removeSuffix(" - EP"),
                o.get("artistName")?.asString.orEmpty(), art.replace("100x100bb", "1000x1000bb"), "Apple Music",
            )
        }
    }.getOrDefault(emptyList())

    /**
     * A song's kind of music, for Follow the kind of music when neither its file nor the catalogue says (Kevin, 26
     * November 2026: the equaliser didn't change with the song, as most songs had no kind known yet). Apple's song
     * search gives it straight away; Deezer's album gives it as a second try. Off the main thread.
     */
    fun genre(artist: String, title: String): String? {
        if (title.isBlank() || artist.isBlank() || artist == Library.UNKNOWN_ARTIST) return null
        val name = Library.withoutBrackets(title)
        val apple = get("https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("term", "$artist $name").addQueryParameter("entity", "song").addQueryParameter("limit", "10").addQueryParameter("country", "gb").build())
        runCatching {
            JsonParser.parseString(apple ?: "").asJsonObject.getAsJsonArray("results").map { it.asJsonObject }
                .firstOrNull { same(it.get("trackName")?.asString.orEmpty(), name) && sameArtist(it.get("artistName")?.asString.orEmpty(), artist) }
                ?.get("primaryGenreName")?.asString?.takeIf { it.isNotBlank() && it != "Music" }
        }.getOrNull()?.let { return it }
        val deezer = get("https://api.deezer.com/search".toHttpUrl().newBuilder().addQueryParameter("q", "$artist $name").addQueryParameter("limit", "10").build())
        val albumId = runCatching {
            JsonParser.parseString(deezer ?: "").asJsonObject.getAsJsonArray("data").map { it.asJsonObject }
                .firstOrNull { same(it.get("title")?.asString.orEmpty(), name) && sameArtist(it.getAsJsonObject("artist")?.get("name")?.asString.orEmpty(), artist) }
                ?.getAsJsonObject("album")?.get("id")?.asLong
        }.getOrNull() ?: return null
        val album = get(("https://api.deezer.com/album/$albumId").toHttpUrl())
        return runCatching {
            JsonParser.parseString(album ?: "").asJsonObject.getAsJsonObject("genres")?.getAsJsonArray("data")?.firstOrNull()?.asJsonObject?.get("name")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * A find for Fix it by hand (Kevin, 27 November 2026: "find out what the best name and artwork to attach"): a song
     * or album with everything the shop knows of it, to put into the file in one tap.
     */
    data class Lookup(
        val title: String, val artist: String, val album: String, val albumArtist: String, val year: String,
        val trackNo: Int, val discNo: Int, val genre: String, val small: String, val big: String, val from: String,
        /** True for a find that only gives a picture (Wikipedia): using it changes the cover, not the names. */
        val coverOnly: Boolean = false,
        /** What kind of record it is, when the source says: "studio album", "compilation", "live album", "single". */
        val kind: String = "",
        /**
         * The file's own artist, when the record's page names them though the record is credited to another (Kevin, 28
         * November 2026: old songs credited to a band's lead singer, as Luke Kelly for The Dubliners).
         */
        val credits: String = "",
    )

    fun parseItunesLookups(json: String?): List<Lookup> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            fun t(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            fun n(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val art = t("artworkUrl100").ifEmpty { return@mapNotNull null }
            val song = o.get("wrapperType")?.asString == "track"
            Lookup(
                title = if (song) t("trackName") else "", artist = t("artistName"),
                album = t("collectionName").removeSuffix(" - Single").removeSuffix(" - EP"),
                albumArtist = t("collectionArtistName").ifEmpty { t("artistName") }, year = t("releaseDate").take(4),
                trackNo = if (song) n("trackNumber") else 0, discNo = if (song) n("discNumber") else 0,
                genre = t("primaryGenreName").takeIf { it != "Music" }.orEmpty(),
                small = art.replace("100x100bb", "300x300bb"), big = art.replace("100x100bb", "1000x1000bb"), from = "Apple Music",
            )
        }
    }.getOrDefault(emptyList())

    fun parseDeezerLookups(json: String?): List<Lookup> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("data").mapNotNull { e ->
            val o = e.asJsonObject
            val song = o.get("type")?.asString == "track"
            val al = if (song) o.getAsJsonObject("album") else o
            val big = al?.get("cover_xl")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            val artist = o.getAsJsonObject("artist")?.get("name")?.asString.orEmpty()
            Lookup(
                title = if (song) o.get("title")?.asString.orEmpty() else "", artist = artist, album = al.get("title")?.asString.orEmpty(),
                albumArtist = artist, year = "", trackNo = 0, discNo = 0, genre = "",
                small = al.get("cover_medium")?.takeIf { !it.isJsonNull }?.asString ?: big, big = big, from = "Deezer",
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Wikipedia's pages for [words], each with its main picture (Kevin, 27 November 2026: "Wikipedia has the correct
     * details and artwork"). The page's name and one-line description show; using one takes only its picture.
     */
    fun parseWikipedia(json: String?): List<Lookup> = runCatching {
        val pages = JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonObject("query")?.getAsJsonArray("pages") ?: return emptyList()
        pages.map { it.asJsonObject }.sortedBy { it.get("index")?.asInt ?: 99 }.mapNotNull { o ->
            val src = o.getAsJsonObject("original")?.get("source")?.asString ?: return@mapNotNull null
            if (!Regex("\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE).containsMatchIn(src.substringBefore('?'))) return@mapNotNull null
            Lookup(
                title = o.get("title")?.asString.orEmpty(), artist = o.get("description")?.asString.orEmpty(), album = "", albumArtist = "",
                year = "", trackNo = 0, discNo = 0, genre = "", small = src, big = src, from = "Wikipedia", coverOnly = true,
            )
        }
    }.getOrDefault(emptyList())

    private fun wikipedia(words: String, songs: Boolean): List<Lookup> = parseWikipedia(
        get("https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").addQueryParameter("generator", "search").addQueryParameter("gsrsearch", words + if (songs) " song" else " album")
            .addQueryParameter("gsrlimit", "5").addQueryParameter("prop", "pageimages|description").addQueryParameter("piprop", "original").build()),
    )

    /**
     * The picture a link points to (Kevin, 27 November 2026: the right cover was on Wikipedia): a picture's own address,
     * or a web page, whose main picture is taken (its og:image, which Wikipedia, shops and most music sites give, else
     * the first full-size Wikimedia picture on it). The answer is the picture's address.
     */
    fun pictureAddress(html: String, page: String): String? {
        fun clean(u: String) = u.replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
        val og = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        if (og != null) return clean(og)
        Regex("(?:https:)?//upload\\.wikimedia\\.org/wikipedia/[^\"'<> ]+\\.(?:jpe?g|png)", RegexOption.IGNORE_CASE).find(html)?.value?.let { return clean(it) }
        return null
    }

    /** The picture behind [link] (a picture or a page), ready to use. Off the main thread. */
    fun pictureFromLink(link: String): ByteArray? {
        val url = Regex("https?://\\S+").find(link)?.value?.trimEnd('.', ',', ')') ?: return null
        // A Discogs page: its site turns phones' apps away, so the picture comes from its own API instead.
        Regex("discogs\\.com/(?:[a-z]{2}/)?(release|master)/(\\d+)").find(url)?.let { m ->
            val kind = m.groupValues[1]
            val json = get(("https://api.discogs.com/" + kind + "s/" + m.groupValues[2]).toHttpUrl())
            val address = if (kind == "release") parseDiscogsRelease(json)?.big
            else runCatching { JsonParser.parseString(json ?: "").asJsonObject.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.get("uri")?.asString }.getOrNull()
            return address?.let { download(it) }
        }
        val (type, bytes) = runCatching {
            Drive.http.newCall(Request.Builder().url(url).header("User-Agent", Catalogue.WHO).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.header("Content-Type").orEmpty() to r.body.bytes()
            }
        }.getOrNull() ?: return null
        if (type.startsWith("image/")) return bytes
        val address = pictureAddress(bytes.decodeToString(), url) ?: return null
        return download(address)
    }

    /**
     * Discogs (Kevin, 27 November 2026: "most of the songs which couldn't be found I could find via a search on
     * discogs.com; it gave all the album details and artwork"). Its search and its releases need no key: checked live
     * that day, A Drop of Irish by The Fureys (1999) came back with its cover. The search gives the release; the release
     * gives its cover, year, styles and track list.
     */
    data class DiscogsFind(val id: Long, val artist: String, val album: String, val year: String, val genre: String)

    private fun discogsName(n: String) = n.replace(Regex("\\s*\\(\\d+\\)$"), "").replace(Regex("\\*$"), "").trim()

    fun parseDiscogsSearch(json: String?): List<DiscogsFind> = runCatching {
        JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonArray("results").mapNotNull { e ->
            val o = e.asJsonObject
            if (o.get("type")?.asString != "release") return@mapNotNull null
            val whole = o.get("title")?.asString.orEmpty()
            val artist = discogsName(whole.substringBefore(" - ", ""))
            val album = whole.substringAfter(" - ", whole).trim()
            fun first(k: String) = o.getAsJsonArray(k)?.firstOrNull()?.asString.orEmpty()
            DiscogsFind(o.get("id")?.asLong ?: return@mapNotNull null, artist, album, o.get("year")?.asString.orEmpty(), first("style").ifBlank { first("genre") })
        }
    }.getOrDefault(emptyList())

    /** A Discogs release as a find; [song] (when given) is looked for in its track list for the track number. */
    fun parseDiscogsRelease(json: String?, song: String? = null): Lookup? = runCatching {
        val o = JsonParser.parseString(json ?: return null).asJsonObject
        val images = o.getAsJsonArray("images")?.map { it.asJsonObject }.orEmpty()
        val img = images.firstOrNull { it.get("type")?.asString == "primary" } ?: images.firstOrNull() ?: return null
        val big = img.get("uri")?.asString.orEmpty().ifBlank { return null }
        val artist = o.getAsJsonArray("artists")?.firstOrNull()?.asJsonObject?.get("name")?.asString?.let { discogsName(it) }.orEmpty()
        val styles = o.getAsJsonArray("styles")?.map { it.asString }.orEmpty()
        val genres = o.getAsJsonArray("genres")?.map { it.asString }.orEmpty()
        val tracks = o.getAsJsonArray("tracklist")?.map { it.asJsonObject }.orEmpty().filter { it.get("type_")?.asString != "heading" }
        val at = if (song.isNullOrBlank()) -1 else tracks.indexOfFirst { same(Library.withoutBrackets(it.get("title")?.asString.orEmpty()), Library.withoutBrackets(song)) }
        Lookup(
            title = if (at >= 0) tracks[at].get("title")?.asString.orEmpty() else "", artist = artist,
            album = o.get("title")?.asString.orEmpty(), albumArtist = artist,
            year = o.get("year")?.asInt?.takeIf { it > 0 }?.toString().orEmpty(), trackNo = if (at >= 0) at + 1 else 0, discNo = 0,
            genre = styles.firstOrNull() ?: genres.firstOrNull().orEmpty(),
            small = img.get("uri150")?.asString ?: big, big = big, from = "Discogs",
            kind = o.getAsJsonArray("formats")?.flatMap { f -> f.asJsonObject.getAsJsonArray("descriptions")?.map { it.asString }.orEmpty() }.orEmpty()
                .let { d -> when { d.any { it.equals("Compilation", true) } -> "compilation"; d.any { it.equals("Single", true) } -> "single"; d.any { it.equals("Album", true) || it.equals("LP", true) } -> "album"; else -> "" } },
        )
    }.getOrNull()

    /** A song's genre from Discogs' search (its style, else its genre), for the genre check. Off the main thread. */
    fun discogsGenre(artist: String, title: String): String? {
        if (artist.isBlank() || artist == Library.UNKNOWN_ARTIST) return null
        return parseDiscogsSearch(get("https://api.discogs.com/database/search".toHttpUrl().newBuilder()
            .addQueryParameter("artist", artist).addQueryParameter("track", Library.withoutBrackets(title)).addQueryParameter("type", "release").addQueryParameter("per_page", "5").build()))
            .firstOrNull { it.genre.isNotBlank() && sameArtist(it.artist, artist) }?.genre
    }

    /** Discogs' best releases for [words], each with its cover and details (three at most, as each is one more ask). */
    fun discogs(words: String, song: String? = null): List<Lookup> {
        val found = parseDiscogsSearch(get("https://api.discogs.com/database/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", words).addQueryParameter("type", "release").addQueryParameter("per_page", "8").build()))
        return found.distinctBy { Library.sortName(it.artist) + "|" + Library.sortName(it.album) }.take(3).mapNotNull { f ->
            parseDiscogsRelease(get(("https://api.discogs.com/releases/" + f.id).toHttpUrl()), song)
        }
    }

    /** Songs (or albums) for [words] from Apple, Deezer and Discogs, one of each in turn, the same record only once. Off the main thread. */
    fun lookUp(words: String, songs: Boolean, song: String? = null): List<Lookup> {
        val apple = get("https://itunes.apple.com/search".toHttpUrl().newBuilder().addQueryParameter("term", words)
            .addQueryParameter("entity", if (songs) "song" else "album").addQueryParameter("limit", "15").addQueryParameter("country", "gb").build())
            .let { parseItunesLookups(it) }
        val deezer = get(("https://api.deezer.com/search" + if (songs) "" else "/album").toHttpUrl().newBuilder().addQueryParameter("q", words).addQueryParameter("limit", "15").build())
            .let { parseDeezerLookups(it) }
        // Discogs in place of Wikipedia, whose pages were seldom the record (Kevin, 27 November 2026).
        val dg = runCatching { discogs(words, if (songs) song else null) }.getOrDefault(emptyList())
        val out = ArrayList<Lookup>()
        val seen = HashSet<String>()
        for (i in 0 until maxOf(apple.size, deezer.size, dg.size)) {
            for (x in listOfNotNull(apple.getOrNull(i), deezer.getOrNull(i), dg.getOrNull(i))) {
                val key = Library.sortName(x.artist) + "|" + Library.sortName(x.album) + "|" + Library.sortName(x.title)
                if (seen.add(key)) out += x
            }
        }
        return out
    }

    /**
     * Wikipedia's album and song pages for [words] (Kevin, 28 November 2026: Wikipedia gives the original album, its
     * year, label and track list, as for "My Little Son" on The Dubliners' Drinkin' and Courtin', 1968). Each page's
     * infobox gives the record's name, artist, year, genre and kind (studio, compilation, live, single); its cover is the
     * page's picture. With [song], only pages whose track list has the song are kept, with the track's name and number.
     */
    fun parseWikiRecords(json: String?, song: String? = null, person: String? = null): List<Lookup> = runCatching {
        val pages = JsonParser.parseString(json ?: return emptyList()).asJsonObject.getAsJsonObject("query")?.getAsJsonArray("pages") ?: return emptyList()
        pages.map { it.asJsonObject }.sortedBy { it.get("index")?.asInt ?: 99 }.mapNotNull { o ->
            val text = o.getAsJsonArray("revisions")?.firstOrNull()?.asJsonObject?.getAsJsonObject("slots")?.getAsJsonObject("main")?.get("content")?.asString ?: return@mapNotNull null
            val at = Regex("\\{\\{\\s*Infobox (album|song|single)", RegexOption.IGNORE_CASE).find(text) ?: return@mapNotNull null
            val box = text.substring(at.range.first, minOf(text.length, at.range.first + 4000))
            fun field(k: String) = Regex("\\n\\s*\\|\\s*$k\\s*=\\s*([^\\n]*)", RegexOption.IGNORE_CASE).find(box)?.groupValues?.get(1)?.trim().orEmpty()
            val isAlbum = at.groupValues[1].equals("album", true)
            val type = wikiPlain(field("type")).lowercase()
            val fromAlbum = if (isAlbum) "" else wikiPlain(field("album"))
            val kind = when {
                !isAlbum && fromAlbum.isNotBlank() -> "song page: it is from the album $fromAlbum"
                !isAlbum -> "single"
                Regex("compil|greatest|best|hits").containsMatchIn(type) -> "compilation"
                "live" in type -> "live album"
                "studio" in type -> "studio album"
                type.isNotBlank() -> type
                else -> "album"
            }
            val name = wikiPlain(field("name")).ifBlank { o.get("title")?.asString.orEmpty().replace(Regex("\\s*\\((album|song|single)[^)]*\\)$", RegexOption.IGNORE_CASE), "") }
            val artist = wikiPlain(field("artist"))
            val year = Regex("(1[89]|20)\\d\\d").find(field("released"))?.value.orEmpty()
            val genreRaw = field("genre")
            val genre = Regex("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)]]").find(genreRaw)?.groupValues?.get(1)?.trim()
                ?: wikiPlain(genreRaw).split(',', '*', '•').map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
            // A song page's picture is its single's sleeve: not used when the song is from an album (its album's page has that cover).
            val picture = if (fromAlbum.isNotBlank()) "" else o.getAsJsonObject("original")?.get("source")?.asString?.substringBefore("?").orEmpty()
            var title = ""
            var number = 0
            var album = if (isAlbum) name else wikiPlain(field("album"))
            if (!song.isNullOrBlank()) {
                val tracks = wikiTracks(text)
                val want = Library.withoutBrackets(song)
                val hit = tracks.withIndex().firstOrNull { (_, t) -> same(t.second, want) }
                    ?: tracks.withIndex().firstOrNull { (_, t) -> t.second.length >= 6 && (Library.sortName(want).contains(Library.sortName(t.second)) || Library.sortName(t.second).contains(Library.sortName(want))) }
                if (hit != null) { title = hit.value.second; number = hit.value.first.takeIf { it > 0 } ?: (hit.index + 1) }
                else if (!isAlbum && same(name, want)) title = name
                else return@mapNotNull null
            }
            if (album.isBlank() && isAlbum) album = name
            // The file's artist named on a page credited to someone else: a band's member (the lead singer).
            val named = person?.takeIf { p -> p.isNotBlank() && !sameArtist(artist, p) && text.contains(p, ignoreCase = true) }.orEmpty()
            Lookup(title, artist, album, artist, year, if (isAlbum) number else 0, 0, genre, picture, picture, "Wikipedia", kind = kind, credits = named)
        }
    }.getOrDefault(emptyList())

    /** Wikipedia markup made plain: links to their words, templates and notes out. */
    fun wikiPlain(s: String): String = s
        .replace(Regex("<ref[^>]*/>|<ref[^>]*>.*?</ref>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\[\\[(?:[^|\\]]*\\|)?([^\\]]+)]]"), "$1")
        .replace(Regex("\\{\\{[^{}]*}}"), "")
        .replace(Regex("<[^>]+>"), " ").replace("'''", "").replace("''", "")
        .replace(Regex("\\s+"), " ").trim()

    /** A page's track list: (number on the record if it says, else 0, name), from "titleN = Name" or "# "Name"" lines. */
    fun wikiTracks(text: String): List<Pair<Int, String>> {
        val numbered = Regex("\\|\\s*title(\\d+)\\s*=\\s*((?:\\[\\[[^\\]]*]]|[^\\n|\\[])+)").findAll(text).map { it.groupValues[1].toInt() to wikiPlain(it.groupValues[2]).trim('"', ' ') }.toList()
        if (numbered.isNotEmpty()) return numbered
        return Regex("(?m)^#\\s*\"?(\\[\\[[^\\]]+]]|[^\"\\n(]+)").findAll(text).map { 0 to wikiPlain(it.groupValues[1]).trim('"', ' ') }.filter { it.second.isNotBlank() }.toList()
    }

    /** Wikipedia's records for [words] (see parseWikiRecords). Off the main thread. */
    fun wikiRecords(words: String, song: String? = null, person: String? = null): List<Lookup> = parseWikiRecords(
        get("https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2").addQueryParameter("generator", "search").addQueryParameter("gsrsearch", words).addQueryParameter("gsrlimit", "6")
            .addQueryParameter("prop", "pageimages|revisions").addQueryParameter("piprop", "original").addQueryParameter("pilicense", "any")
            .addQueryParameter("rvprop", "content").addQueryParameter("rvslots", "main").build()),
        song, person,
    )

    /** A cover's picture. Off the main thread. */
    fun download(url: String): ByteArray? = Smart.download(url)
}
