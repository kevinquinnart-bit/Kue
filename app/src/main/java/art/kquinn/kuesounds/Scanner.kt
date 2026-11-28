package art.kquinn.kuesounds

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

/**
 * Finding the music and reading each song's details.
 *
 * Step 1 lists every music file and folder in the Drive (a few seconds even for thousands of songs) and
 * gives each song a first guess from its file name and folders, so the library shows straight away.
 * Step 2 reads each song's own details (title, artist, album, track number, length, cover) from the
 * file itself, a small piece of each file at a time, and keeps them on the phone so it is done once.
 */
object Scanner {
    private var current by mutableStateOf(LibraryData())

    /** Everything known about the music. Each change re-sorts the lists in the background (see [view]). */
    var data: LibraryData
        get() = current
        private set(value) {
            current = value
            refreshView()
        }

    /** The library sorted into albums, artists and songs, ready for the screens. */
    data class LibraryView(
        val albums: List<Album> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val songs: List<Track> = emptyList(),
        val withCover: Int = 0,
        val matched: List<Album> = emptyList(),
        val leftToFix: Int = 0,
        /** Albums already looked up in the online catalogue, found there or not. */
        val lookedUp: Int = 0,
    ) {
        val byKey: Map<String, Album> by lazy { albums.associateBy { it.key } }
    }

    /**
     * The sorted lists. Sorting thousands of songs takes a moment, so it is done away from the
     * screens (doing it on the screens' own time froze KueSounds in 0.1.7), and only the newest
     * library is sorted when changes come quickly.
     */
    var view by mutableStateOf(LibraryView())
        private set

    private val viewDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val viewBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun refreshView() {
        viewDirty.set(true)
        if (!viewBusy.compareAndSet(false, true)) return
        viewScope.launch {
            try {
                while (viewDirty.getAndSet(false)) {
                    val d = current
                    val albums = Library.albums(d.tracks)
                    val matched = albums.filter { Fixer.canFix(it, d) }
                    view = LibraryView(
                        albums = albums,
                        artists = Library.artists(albums),
                        songs = Library.songs(d.tracks),
                        withCover = albums.count { d.art[it.key]?.let { p -> File(p).exists() } == true },
                        matched = matched,
                        leftToFix = matched.count { Fixer.hasWork(it, d) },
                        lookedUp = albums.count { al -> al.tracks.all { it.checked } },
                    )
                }
            } finally {
                viewBusy.set(false)
                if (viewDirty.get()) refreshView()
            }
        }
    }

    /** Which step the scan is on, for Settings: 1 finding the music, 2 reading song details, 3 looking up albums. 0 idle. */
    var stage by mutableStateOf(0)
        private set

    /** How far through the part it is on, 0 to 1; below 0 when that can't be said. For the bar in Settings. */
    var progress by mutableStateOf(-1f)
        private set

    /** Says what is being done, and how far through: [done] of [total]. */
    private fun say(text: String, done: Int, total: Int) {
        status = text + " · " + String.format("%,d", done) + " of " + String.format("%,d", total)
        progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
    }

    /** What the scan is doing, in words for the screen. Empty when it is idle. */
    var status by mutableStateOf("")
        private set

    /** How many songs have their own details read, out of how many. */
    var read by mutableStateOf(0)
        private set

    var problem by mutableStateOf("")
        private set

    var signedOut by mutableStateOf(false)

    /** A plain record of what the scan did and what failed, newest last, for the report. */
    private val notes = ArrayDeque<String>()

    fun note(text: String) = synchronized(notes) {
        val time = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.UK).format(java.util.Date())
        notes.addLast("$time $text")
        while (notes.size > 200) notes.removeFirst()
        // Kept on the phone too, so the report still has it after KueSounds has been closed.
        if (::app.isInitialized) runCatching { notesFile().writeText(notes.joinToString("\n")) }
    }

    private fun notesFile() = File(app.filesDir, "notes.txt")

    /** The report: counts and the record, with no addresses or passwords in it. */
    /** The report. Call off the main thread. */
    fun report(): String {
        val d = data
        val albums = view.albums
        val lines = mutableListOf(
            "KueSounds " + BuildConfig.VERSION_NAME,
            "Songs: " + d.tracks.size,
            "Song details read: " + d.tracks.count { it.tagged },
            "Albums: " + albums.size,
            "Albums looked up: " + albums.count { al -> al.tracks.all { it.checked } },
            "Albums with a cover: " + albums.count { artFor(it) != null },
            "Songs made singles of their own: " + d.tracks.count { keyOf(it) in d.ownSingles },
            "Folders: " + d.folders.size + ", folder cover pictures: " + d.folderCovers.size,
            "Now: " + status.ifEmpty { "idle" } + (if (problem.isNotEmpty()) " / problem: $problem" else ""),
            "",
        )
        synchronized(notes) { lines += notes }
        return lines.joinToString("\n")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var app: Context

    /** The app itself, for work done away from the screens (making smaller copies). */
    val appContext: Context get() = app

    private fun libraryFile() = File(app.filesDir, "library.json")
    private fun artFolder() = File(app.filesDir, "art").apply { mkdirs() }

    fun init(context: Context) {
        app = context.applicationContext
        synchronized(notes) {
            if (notes.isEmpty()) runCatching { notesFile().readLines().filter { it.isNotBlank() }.takeLast(200).forEach { notes.addLast(it) } }
        }
        note("KueSounds opened, version " + BuildConfig.VERSION_NAME)
        SoundMatch.init(context)
        if (current.tracks.isEmpty()) data = Library.load(libraryFile())
        // Libraries kept before 0.1.5 had their albums looked up without keeping the catalogue's cover in
        // first place: every album is looked up once more.
        // 25 November 2026: album keys leave punctuation out now ("A Mother's Son" and "A Mothers Son" are one album).
        // Every kept key is brought up to date first, before anything below compares keys.
        if (data.version < 8) rekey()
        if (data.version < 2) {
            data = data.copy(tracks = data.tracks.map { it.copy(checked = false) }, version = 2)
            save()
        }
        // Before 0.1.x of 23 November 2026 a song search read MusicBrainz wrongly (the artist's own releases carry no
        // artist name there), so songs from compilations found no album and kept the compilation's name and cover,
        // and few singles were found. Songs not given an album then, and songs with no single, are looked up again.
        if (data.version < 3) {
            data = data.copy(
                tracks = data.tracks.map { t ->
                    val moved = Library.albumKey(Library.albumArtistOf(t), t.album) in data.releases
                    t.copy(ownLook = t.ownLook && moved, singleChecked = t.singleChecked && t.id in data.singleTitles)
                },
                version = 3,
            )
            save()
        }
        // 23 November 2026: Kevin wants no greatest hits or collections at all. Every album is looked up again, so
        // the ones the catalogue files as compilations are found, and their songs sent to their own albums.
        if (data.version < 4) {
            data = data.copy(tracks = data.tracks.map { it.copy(checked = false) }, version = 4)
            save()
        }
        // 23 November 2026: songs were searched for with the bracketed part of their name, and many weren't found.
        // Songs not given an album, and songs with no single, are looked up again.
        if (data.version < 5) {
            data = data.copy(
                tracks = data.tracks.map { t ->
                    val moved = Library.albumKey(Library.albumArtistOf(t), t.album) in data.releases
                    t.copy(ownLook = t.ownLook && moved, singleChecked = t.singleChecked && t.id in data.singleTitles)
                },
                version = 5,
            )
            save()
        }
        // 24 November 2026: songs off greatest hits and compilations that the catalogue had no album or single for
        // stayed on the collection (761 of 1,645 on Kevin's phone). Kevin: every song goes to its named album or,
        // failing that, a single in its own right. Their albums are looked up once more, so the ones the catalogue
        // files as collections are known; the songs themselves aren't asked about again.
        if (data.version < 6) {
            data = data.copy(
                tracks = data.tracks.map { t -> if (t.ownLook && keyOf(t) !in data.releases) t.copy(checked = false) else t },
                version = 6,
            )
            save()
        }
        // 24 November 2026 (0.1.43): albums matched before 0.1.42 were never checked against the wider list of collection
        // names, so a greatest hits the catalogue had matched as an ordinary album stayed. Each one whose name says it is
        // a collection is now treated as one: its songs are looked up one by one, and any with no home become singles.
        if (data.version < 7) {
            val byKey = data.tracks.groupBy { keyOf(it) }
            val hitKeys = data.releases.keys.filter { key -> byKey[key]?.any { Catalogue.looksLikeHits(it.album) } == true }.toSet()
            data = data.copy(
                tracks = data.tracks.map { t -> if (keyOf(t) in hitKeys) t.copy(ownLook = false, checked = true) else t },
                releases = data.releases - hitKeys,
                catalogueCovers = data.catalogueCovers - hitKeys,
                art = data.art - hitKeys.filter { it in data.catalogueCovers }.toSet(),
                collections = data.collections + hitKeys,
                version = 7,
            )
            save()
            note("found " + hitKeys.size + " matched albums that are greatest hits or collections: their songs are looked up one by one")
        }
        // 25 November 2026 (Kevin: covers still missing for an awful lot): an album whose name in the files is written a
        // little differently from the catalogue's found no match, so no cover. Those are now tried a second way: every
        // album without a match (and not a collection or a single of its own) is looked up once more.
        if (data.version < 8) {
            val again = data.tracks.count { t -> keyOf(t) !in data.releases && keyOf(t) !in data.collections && keyOf(t) !in data.ownSingles && t.checked }
            data = data.copy(
                tracks = data.tracks.map { t ->
                    if (keyOf(t) !in data.releases && keyOf(t) !in data.collections && keyOf(t) !in data.ownSingles) t.copy(checked = false) else t
                },
                version = 8,
            )
            save()
            note("album names now match without punctuation; $again songs on albums with no match are looked up once more")
        }
        // 25 November 2026 (Kevin's report: 121 of 129 albums not found, many with no cover): edition words such as
        // "(Deluxe Edition)" and "(2011 Remaster)" are left out of the search now, and a song with no album or single of
        // its own gets the cover of the record it first came out on. Albums still without a match are asked again,
        // and their songs looked up once more for a cover where the album has none.
        if (data.version < 9) {
            val noArt = { t: Track -> data.art[keyOf(t)]?.let { File(it).exists() } != true }
            val again = data.tracks.filter { t -> keyOf(t) !in data.releases && keyOf(t) !in data.ownSingles }
            val ids = again.map { it.id }.toSet()
            data = data.copy(
                tracks = data.tracks.map { t -> if (t.id in ids) t.copy(checked = false, ownLook = t.ownLook && !noArt(t)) else t },
                version = 9,
            )
            save()
            note(again.size.toString() + " songs on albums with no match are looked up again, for a match or a cover")
        }
        // 26 November 2026 (Kevin: "Match by sound: 0 songs named", "piles of empty album covers"). Every song had been
        // marked as listened to by the AcoustID matching of 25 November, or by a failed try, so Shazam never listened to
        // any. Every song is listened to again; the 77 made singles of their own by step 3 go back to be found by their
        // sound or on Deezer and Apple first; every album with no catalogue cover is looked for on Deezer and Apple.
        if (data.version < 10) {
            val swept = data.tracks.filter { it.id in data.artistSwept }.map { keyOf(it) }.toSet()
            data = data.copy(
                soundChecked = emptySet(), artistSwept = emptySet(), coverTried = emptySet(),
                ownSingles = data.ownSingles - swept,
                version = 10,
            )
            save()
            note("every song to be listened to again; " + swept.size + " songs made singles by step 3 to be found again; covers looked for on Deezer and Apple")
        }
        // 26 November 2026 (evening): albums sold under a shorter name (a single's first side, a name with the artist in
        // front) are looked for once more on Deezer and Apple.
        if (data.version < 11) {
            data = data.copy(coverTried = data.coverTried.filter { it in data.catalogueCovers }.toSet(), version = 11)
            save()
        }
        read = data.tracks.count { it.tagged }
    }

    private fun keyOf(t: Track): String = Library.albumKey(Library.albumArtistOf(t), t.album)

    /** Every album key kept in the library, as [Library.newKey] has it now. Where two old keys become one, a match from the catalogue wins. */
    private fun rekey() {
        val d = data
        val order = { m: Map<String, String> -> m.entries.sortedBy { if (it.key in d.catalogueCovers) 0 else 1 } }
        val remap = { m: Map<String, String> -> LinkedHashMap<String, String>().also { out -> for ((k, v) in order(m)) out.putIfAbsent(Library.newKey(k), v) } }
        data = d.copy(
            art = remap(d.art),
            releases = remap(d.releases),
            catalogueCovers = d.catalogueCovers.mapTo(HashSet()) { Library.newKey(it) },
            collections = d.collections.mapTo(HashSet()) { Library.newKey(it) },
            ownSingles = d.ownSingles.mapTo(HashSet()) { Library.newKey(it) },
        )
    }

    /** Whether a scan is going on. Kept as screen state, so the screens change the moment it starts and ends. */
    var running by mutableStateOf(false)
        private set

    /** Changes some songs and keeps the library at once. Used by the file fixing. */
    fun changeTracks(ids: Set<String>, change: (Track) -> Track) = synchronized(this) {
        data = data.copy(tracks = data.tracks.map { if (it.id in ids) change(it) else it })
        save()
    }

    /** Looks for music in the Drive, then reads the details of any song not yet read. */
    fun scan() {
        // Not while files are being fixed: the two would change the library at once.
        if (job?.isActive == true || Fixer.running) return
        running = true
        // Keeps going with KueSounds closed or the screen off, with its progress in the pull-down.
        runCatching { WorkService.start(app) }.onFailure { note("couldn't keep the search going in the background: " + it.message) }
        job = scope.launch {
            problem = ""
            // A dropped connection (the phone waking up, or the Wi-Fi changing as KueSounds opens) is tried
            // again by itself after 5, 15 then 30 seconds; the work already done is kept, so it carries on.
            val waits = longArrayOf(5_000, 15_000, 30_000)
            var attempt = 0
            try {
                while (true) {
                    try {
                        stage = 1
                        progress = -1f
                        status = "Finding your music"
                        val files = Drive.listAll(Drive.MUSIC_QUERY) { n -> status = "Finding your music · " + String.format("%,d", n) + " files" }
                        val found = String.format("%,d", files.count { Library.isMusic(it.name, it.mime) }) + " songs found"
                        status = "Finding your folders · $found"
                        val folderFiles = Drive.listAll(Drive.FOLDER_QUERY)
                        val folders = folderFiles.map { Folder(it.id, it.name, it.parent) }
                        status = "Finding album covers · $found"
                        val covers = runCatching { Drive.listAll(Drive.COVER_QUERY) }.getOrDefault(emptyList())
                            .filter { Drive.isCoverName(it.name) && it.parent != null && it.size in 1..8_000_000 }
                            .groupBy { it.parent!! }
                            .mapValues { (_, list) -> list.sortedBy { coverRank(it.name) }.first().id }
                        data = Library.merge(data, files, folders, covers, System.currentTimeMillis())
                        save()
                        note("found " + data.tracks.size + " songs in " + folders.size + " folders, " + covers.size + " folder cover pictures")
                        if (Google.email.isBlank()) runCatching { Google.keepEmail(Drive.whoAmI()) }
                        stage = 2
                        readDetails()
                        // The steps of Fix your music files (Kevin, 26 November 2026):
                        // 1 by name in the music catalogue, 2 by sound, 3 the closest artist, then the files are written.
                        stage = 3
                        matchAlbums()
                        // Covers (and albums) MusicBrainz hasn't got, from Deezer and Apple (26 November 2026).
                        findCovers()
                        stage = 4
                        if (matchBySound()) { matchAlbums(); findCovers() }
                        stage = 5
                        sweepArtists()
                        // 4: what was found is written to the files straight away (Kevin, 26 November 2026: "if found,
                        // applied and crossed off as done"), with the edit permission given once. Google keeps each old
                        // version for 30 days.
                        if (Google.canEdit) {
                            stage = 7
                            Fixer.queueAll(Library.albums(data.tracks))
                            if (Fixer.hasWork) Fixer.run(app, { status = it }, inline = true)
                        }
                        // Extra details, after: kinds of music, dates, artist pictures and who wrote each song.
                        stage = 6
                        lookUpFacts()
                        lookUpArtistPictures()
                        readComposers()
                        status = ""
                        finishedAt = System.currentTimeMillis()
                        note("scan finished")
                        // Songs new to a playlist or to the music that are chosen to be kept on the phone.
                        runCatching { Offline.keepUp(app) }
                        break
                    } catch (e: Drive.SignedOut) {
                        note("signed out by Google")
                        signedOut = true
                        status = ""
                        break
                    } catch (e: java.io.IOException) {
                        if (attempt >= waits.size) {
                            note("scan stopped: " + e.javaClass.simpleName + " " + e.message)
                            problem = "Couldn't finish looking through your music. " + (e.message ?: "")
                            status = ""
                            break
                        }
                        val seconds = waits[attempt] / 1000
                        note("connection lost (" + e.javaClass.simpleName + " " + e.message + "): trying again in $seconds seconds")
                        status = "Lost the connection · trying again in $seconds seconds"
                        kotlinx.coroutines.delay(waits[attempt])
                        attempt++
                    } catch (e: Exception) {
                        note("scan stopped: " + e.javaClass.simpleName + " " + e.message)
                        problem = "Couldn't finish looking through your music. " + (e.message ?: "")
                        status = ""
                        break
                    }
                }
            } finally {
                stage = 0
                running = false
            }
        }
    }

    private fun coverRank(name: String): Int = when (name.substringBeforeLast('.').lowercase()) {
        "cover" -> 0
        "folder" -> 1
        "front" -> 2
        else -> 3
    }

    private fun save() = Library.save(libraryFile(), data)

    @Volatile private var failures = 0

    /** Step 2: each song's own details, four at a time, kept every 25 songs. */
    private suspend fun readDetails() {
        val todo = data.tracks.filter { !it.tagged }
        read = data.tracks.size - todo.size
        if (todo.isEmpty()) {
            fillCovers()
            return
        }
        val gate = Semaphore(4)
        var sinceSave = 0
        failures = 0
        val before = read
        todo.chunked(25).forEach { chunk ->
            if (!scope.isActive) return
            say("Reading song details", read, data.tracks.size)
            // A fresh pass for each batch: one that ran out part way was dropped, and this asks for a new one.
            val pass = Google.token() ?: throw Drive.SignedOut()
            val done = coroutineScopeAsync(chunk.map { t -> suspend { gate.withPermit { readOne(t, pass) } } })
            val byId = done.filterNotNull().associateBy { it.first.id }
            data = data.copy(tracks = data.tracks.map { byId[it.id]?.first ?: it }, art = data.art + done.filterNotNull().mapNotNull { it.second })
            read = data.tracks.count { it.tagged }
            sinceSave += chunk.size
            if (sinceSave >= 100) {
                save()
                sinceSave = 0
            }
        }
        save()
        note("song details: " + (read - before) + " read now, " + data.tracks.count { !it.tagged } + " still to read, " + failures + " failed")
        fillCovers()
        save()
    }

    private suspend fun <T> coroutineScopeAsync(work: List<suspend () -> T>): List<T> =
        kotlinx.coroutines.coroutineScope { work.map { w -> async { w() } }.awaitAll() }

    /**
     * Reads one song's details from the file. Gives back the song with its details, and its album's
     * cover picture when the file holds one and the album has none yet.
     */
    private fun readOne(t: Track, token: String): Pair<Track, Pair<String, String>?>? {
        val retriever = MediaMetadataRetriever()
        val source = DriveSource(t.id, t.size, token)
        return try {
            retriever.setDataSource(source)
            fun key(k: Int) = retriever.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val title = key(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val tidy = album?.let { Library.cleanAlbum(it, albumArtist ?: artist.orEmpty()) }
            val updated = t.copy(
                title = title ?: t.title,
                artist = artist ?: albumArtist ?: t.artist,
                album = tidy?.first ?: t.album,
                albumArtist = albumArtist.orEmpty(),
                trackNo = Library.number(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)).takeIf { it > 0 } ?: t.trackNo,
                discNo = Library.number(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)).takeIf { it > 0 }
                    ?: tidy?.second?.takeIf { it > 0 } ?: t.discNo,
                year = Library.year(key(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: key(MediaMetadataRetriever.METADATA_KEY_DATE)),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                composer = key(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty(),
                composerRead = true,
                tagged = true,
                // Fresh details mean the album is looked up again with them.
                checked = false,
            )
            val albumKey = Library.albumKey(Library.albumArtistOf(updated), updated.album)
            var art: Pair<String, String>? = null
            if (albumKey !in data.art && (updated.folderId == null || updated.folderId !in data.folderCovers)) {
                retriever.embeddedPicture?.let { bytes ->
                    keepPicture(bytes, albumKey)?.let { art = albumKey to it }
                }
            }
            updated to art
        } catch (e: Exception) {
            if (failures++ < 15) {
                note("details not read for " + t.fileName + ": " + (source.failure ?: e).let { it.javaClass.simpleName + " " + it.message })
            }
            // The Drive couldn't be reached, or the pass ran out: left as it is, to be read next time.
            if (source.failure != null) return null
            // A file whose details can't be read keeps its first guess, and isn't tried again.
            t.copy(tagged = true) to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Step 4, first part: each matched album's kinds of music, the date it first came out, its type and its main
     * artist, asked of the catalogue once each (one question a second, so about half an hour for 1,700 albums).
     */
    private fun lookUpFacts() {
        val todo = view.albums.mapNotNull { al -> data.releases[al.key]?.split('|')?.getOrNull(1)?.takeIf { it.isNotEmpty() && al.key !in data.facts }?.let { al to it } }
        if (todo.isEmpty()) return
        var done = 0
        var found = 0
        for ((al, group) in todo) {
            if (!scope.isActive) return
            say("Finding each album's genre and date", done, todo.size)
            lookup("facts for " + al.title) { Catalogue.groupFacts(group) }.onSuccess { f ->
                synchronized(this) { data = data.copy(facts = data.facts + (al.key to f)) }
                if (f.genres.isNotEmpty()) found++
            }
            done++
            if (done % 25 == 0) save()
        }
        save()
        note("kinds of music: $done albums asked, $found with a kind of music")
    }

    /**
     * Step 4, second part: a photo of each artist or group (Kevin, 25 November 2026: "ideally an image of the artist"),
     * free from Wikimedia Commons through the catalogue's link to Wikidata. Looked for once each.
     */
    private fun lookUpArtistPictures() {
        val todo = view.artists.mapNotNull { a ->
            if (a.name in data.artistPictures || Catalogue.isVarious(a.name) || a.name == Library.UNKNOWN_ARTIST) return@mapNotNull null
            val id = a.albums.firstNotNullOfOrNull { al -> data.facts[al.key]?.artistId?.takeIf { it.isNotEmpty() } } ?: return@mapNotNull null
            a.name to id
        }
        if (todo.isEmpty()) return
        var done = 0
        var found = 0
        for ((name, id) in todo) {
            if (!scope.isActive) return
            say("Finding artist pictures", done, todo.size)
            lookup("picture of $name") { Catalogue.artistPicture(id) }.onSuccess { bytes ->
                val kept = bytes?.let { keepPicture(it, "artist\u0001$name") }.orEmpty()
                if (kept.isNotEmpty()) found++
                synchronized(this) { data = data.copy(artistPictures = data.artistPictures + (name to kept)) }
            }
            done++
            if (done % 25 == 0) save()
        }
        save()
        note("artist pictures: $done artists asked, $found found")
    }

    /** Step 4, last part: who wrote each song, read from the files kept before KueSounds read it (for Composers). */
    private suspend fun readComposers() {
        val todo = data.tracks.filter { it.tagged && !it.composerRead }
        if (todo.isEmpty()) return
        val gate = Semaphore(4)
        var done = 0
        todo.chunked(25).forEach { chunk ->
            if (!scope.isActive) return
            say("Reading who wrote each song", done, todo.size)
            val pass = Google.token() ?: throw Drive.SignedOut()
            val got = coroutineScopeAsync(chunk.map { t -> suspend { gate.withPermit { t.id to readComposer(t, pass) } } })
                .filter { it.second != null }.associate { it.first to it.second!! }
            synchronized(this) {
                data = data.copy(tracks = data.tracks.map { t -> got[t.id]?.let { t.copy(composer = it, composerRead = true) } ?: t })
            }
            done += chunk.size
            if (done % 200 == 0) save()
        }
        save()
        note("composers: " + data.tracks.count { !it.composer.isNullOrBlank() } + " songs name who wrote them")
    }

    /** Who wrote one song, from its file: "" when it doesn't say; null when the file couldn't be reached. */
    private fun readComposer(t: Track, token: String): String? {
        val retriever = MediaMetadataRetriever()
        val source = DriveSource(t.id, t.size, token)
        return try {
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)?.trim().orEmpty()
        } catch (e: Exception) {
            if (source.failure != null) null else ""
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * The label, catalogue number, country and barcode of an album's release, asked when its info is opened (one
     * question, kept). Call off the main thread.
     */
    fun detailsFor(al: Album): AlbumFacts? {
        val facts = data.facts[al.key]
        if (facts?.detailed == true) return facts
        val release = data.releases[al.key]?.split('|')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return facts
        val group = data.releases[al.key]?.split('|')?.getOrNull(1).orEmpty()
        val base = facts ?: runCatching { if (group.isNotEmpty()) Catalogue.groupFacts(group) else null }.getOrNull() ?: AlbumFacts()
        val full = runCatching { Catalogue.releaseFacts(release, base) }.getOrElse { return base }
        synchronized(this) { data = data.copy(facts = data.facts + (al.key to full)) }
        save()
        return full
    }

    /**
     * Starts the library again from the Drive (Settings › KueSounds › Your music; CloudPlayer's reset): every song's
     * details, covers and lookups are dropped and found again. Favourites, playlists and songs saved on the phone are
     * kept elsewhere and stay.
     */
    fun startAgain() {
        if (job?.isActive == true || Fixer.running) return
        synchronized(this) {
            runCatching { artFolder().deleteRecursively() }
            // At the newest layout, so none of the one-off updates for older libraries runs on the new one.
            data = LibraryData(version = 11)
            save()
        }
        note("library started again by hand")
        scan()
    }

    /**
     * A cover picked by hand for an album (Kevin, 26 November 2026): kept small, shown at once, and written into the
     * files at the next Fix when the album is matched. Gives back false when the picture couldn't be read.
     */
    fun setCover(al: Album, bytes: ByteArray): Boolean = synchronized(this) {
        val path = keepPicture(bytes, al.key + "\u0002hand" + System.currentTimeMillis()) ?: return false
        data = data.copy(art = data.art + (al.key to path), catalogueCovers = data.catalogueCovers + al.key)
        save()
        note("cover picked by hand: " + al.artist + " / " + al.title)
        true
    }

    /** The photo of an artist or group, once found. */
    fun artistPicture(name: String): File? = data.artistPictures[name]?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }

    /** The album a song is on. */
    fun albumOf(t: Track): Album? = view.byKey[keyOf(t)]

    /** Albums whose folder holds a cover picture get it, downloaded once and kept small. */
    private suspend fun fillCovers() {
        val albums = Library.albums(data.tracks)
        val wanted = albums.filter { it.key !in data.art }.mapNotNull { al ->
            val folder = al.tracks.firstNotNullOfOrNull { it.folderId?.takeIf { f -> f in data.folderCovers } } ?: return@mapNotNull null
            al.key to data.folderCovers.getValue(folder)
        }
        if (wanted.isEmpty()) return
        val added = mutableMapOf<String, String>()
        wanted.forEachIndexed { i, (key, fileId) ->
            say("Fetching album covers", i + 1, wanted.size)
            runCatching {
                val raw = File(app.cacheDir, "cover.part")
                Drive.download(fileId, raw)
                keepPicture(raw.readBytes(), key)?.let { added[key] = it }
                raw.delete()
            }
            if (added.size % 20 == 0 && added.isNotEmpty()) {
                data = data.copy(art = data.art + added)
            }
        }
        data = data.copy(art = data.art + added)
    }

    /**
     * Step 3: each album not yet looked up is found in MusicBrainz, which gives it its proper name,
     * artist and year, and its cover. An album that isn't found keeps its tidied name. Each is looked up once.
     *
     * Compilations (albums of mixed artists, such as "Now That's What I Call Music") are not kept: each of
     * their songs is looked up on its own and given the artist's album it first came out on, or its single
     * when it was never on an album (Kevin, 21 November 2026). Songs with no album are looked up the same way.
     * Last, every other song is looked up once to see whether it came out as a single, for the Singles tab.
     */
    /** Lookups that failed one after another; too many and step 3 stops until next time. */
    private var failsInARow = 0

    private class TooManyFailures(message: String) : Exception(message)

    /**
     * One lookup in the catalogue. A failure (the catalogue busy or not answering) is noted, then after a
     * wait the next one is tried: that one is left to be looked up next time. After 8 failures in a row
     * step 3 stops, with the reason shown.
     */
    private fun <T> lookup(what: String, ask: () -> T): Result<T> {
        val r = runCatching(ask)
        if (r.isSuccess) { failsInARow = 0; return r }
        val e = r.exceptionOrNull()!!
        failsInARow++
        note("lookup failed (" + what + "): " + e.javaClass.simpleName + " " + e.message)
        if (failsInARow >= 8) throw TooManyFailures(e.message ?: e.javaClass.simpleName)
        status = "The music catalogue isn't answering. Trying again shortly"
        // One failure: carry on after 10 seconds. Three or more in a row: the catalogue is down, so rest 5 minutes
        // before the next, which keeps a long outage (about three quarters of an hour) from stopping the scan.
        Thread.sleep(if (failsInARow >= 3) 300_000L else 10_000L)
        return r
    }

    /**
     * Match by sound, by itself (Kevin, 25 November 2026: "should find these"): every song on an album the catalogue
     * didn't find, or with no known artist, is listened to once and named by Shazam's song finder (26 November 2026:
     * no key needed), or AcoustID's free database when a key is set and Shazam doesn't know it. A sure match (75% or
     * more) puts its name and artist right here, and the songs of one album on the album most of them are on; the
     * files get it at the next Fix. Only on Wi-Fi. True when any song was changed.
     */
    private fun matchBySound(): Boolean {
        val d = data
        val missed = notFound(view, d).flatMap { it.tracks } +
            d.tracks.filter { Library.sortName(it.artist) == Library.sortName(Library.UNKNOWN_ARTIST) || it.artist.isBlank() }
        val todo = missed.distinctBy { it.id }.filter { it.id !in d.soundChecked }
        if (todo.isEmpty()) return false
        if (!Net.unmetered(app)) { note("matching by sound waits for Wi-Fi: " + todo.size + " songs"); waitingForWifi = true; return false }
        note("matching by sound: " + todo.size + " songs")
        val best = HashMap<String, SoundMatch.Found>()
        var failsInRow = 0
        for ((i, t) in todo.withIndex()) {
            say("Matching by sound", i + 1, todo.size)
            val got = runCatching { SoundMatch.identify(t) }
            // Only a real answer counts as listened to (26 November 2026): a failed try is tried again next time,
            // unless the file itself couldn't be read.
            var answered = got.isSuccess
            got.onFailure { e ->
                note("not matched by sound: " + t.fileName + ": " + e.javaClass.simpleName + " " + (e.message ?: ""))
                val net = e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException ||
                    "Shazam" in (e.message ?: "") || e is Drive.SignedOut
                if (!net) answered = true
                failsInRow++
            }.onSuccess { failsInRow = 0 }
            got.getOrNull()?.firstOrNull()?.takeIf { it.score >= 0.75 }?.let { f ->
                best[t.id] = f
                note("named by sound (" + f.from + "): " + t.fileName + " → " + f.artist + " / " + f.title + " / " + f.albums.firstOrNull().orEmpty())
            }
            if (got.isSuccess && got.getOrNull().isNullOrEmpty()) note("not known by its sound: " + t.fileName)
            if (answered) data = data.copy(soundChecked = data.soundChecked + t.id)
            if (i % 10 == 9) save()
            if (failsInRow >= 8) { note("matching by sound stopped: 8 failures in a row"); break }
        }
        if (best.isEmpty()) { save(); note("matched by sound: none of " + todo.size); return false }
        // Each album's songs go together to the album most of them are on, or each to its own first album or single.
        val byAlbum = todo.filter { it.id in best }.groupBy { Library.albumKey(Library.albumArtistOf(it), it.album) }
        val moves = HashMap<String, Pair<String, String>>()
        for ((_, songs) in byAlbum) {
            val common = songs.mapNotNull { best[it.id]?.albums }.flatten().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            for (t in songs) {
                val f = best.getValue(t.id)
                val album = when { common != null && common in f.albums -> common; f.albums.isNotEmpty() -> f.albums.first(); else -> f.single }
                if (album != null) moves[t.id] = album to f.artist
            }
        }
        data = data.copy(soundMatched = data.soundMatched + best.keys, tracks = data.tracks.map { t ->
            val f = best[t.id] ?: return@map t
            val move = moves[t.id]
            t.copy(
                title = Library.withoutRemaster(f.title), artist = f.artist,
                album = move?.first?.let { Library.withoutRemaster(it) } ?: t.album, albumArtist = move?.second ?: t.albumArtist,
                checked = move == null && t.checked, ownLook = false, fixed = false,
            )
        })
        // The cover Shazam gives, for an album that has none yet.
        for ((id, f) in best) {
            val url = f.cover ?: continue
            val t = data.tracks.firstOrNull { it.id == id } ?: continue
            val key = keyOf(t)
            if (key in data.catalogueCovers) continue
            val path = Covers.download(url)?.let { keepPicture(it, key + "\u0002shazam") } ?: continue
            data = data.copy(art = data.art + (key to path), catalogueCovers = data.catalogueCovers + key)
        }
        save()
        note("matched by sound: " + best.size + " of " + todo.size + " songs, " + moves.size + " put on their album")
        return true
    }

    /**
     * Covers MusicBrainz hasn't got (Kevin, 26 November 2026: "piles of empty album covers"), from Deezer and Apple's
     * music shop. First each album with no catalogue cover, by its own name and artist. Then each song left on an album
     * nobody knows, or a single of its own, by its name and artist: it goes to the album Deezer or Apple has it on, with
     * that cover ("Home To Donegal" to Nathan Carter's "Live At The Marquee Cork"). A find is only taken when the
     * names match. Each album and song is asked once.
     */
    private fun findCovers() {
        val albums = Library.albums(data.tracks).filter { al ->
            al.key !in data.catalogueCovers && al.key !in data.coverTried && !onItsOwn(al) && al.tracks.all { it.checked }
        }
        if (albums.isEmpty()) return
        note("covers: " + albums.size + " albums with no cover, asked of Deezer and Apple")
        var byAlbum = 0
        var bySong = 0
        albums.forEachIndexed { i, al ->
            say("Finding covers", i + 1, albums.size)
            val own = al.key in data.ownSingles
            if (!own) {
                val (clean, _) = Library.cleanAlbum(al.title, al.artist)
                val hit = runCatching { Covers.album(al.artist, clean) }.getOrNull()
                Thread.sleep(250)
                if (hit != null) {
                    val path = Covers.download(hit.cover)?.let { keepPicture(it, al.key + "\u0002" + hit.from) }
                    if (path != null) {
                        data = data.copy(art = data.art + (al.key to path), catalogueCovers = data.catalogueCovers + al.key, coverTried = data.coverTried + al.key)
                        note("cover from " + hit.from + ": " + al.artist + " / " + al.title)
                        byAlbum++
                        if (i % 10 == 9) save()
                        return@forEachIndexed
                    }
                }
            }
            // Not known by its album's name: each song by its own name, when the album isn't one MusicBrainz knows.
            if (al.key !in data.releases) {
                for (t in al.tracks) {
                    val hit = runCatching { Covers.song(t.artist, t.title) }.getOrNull()
                    Thread.sleep(250)
                    if (hit == null) continue
                    val moved = t.copy(album = Library.withoutRemaster(hit.album), albumArtist = hit.artist, checked = true, ownLook = true, fixed = false)
                    val key = keyOf(moved)
                    if (key !in data.catalogueCovers) {
                        val path = Covers.download(hit.cover)?.let { keepPicture(it, key + "\u0002" + hit.from) } ?: continue
                        data = data.copy(art = data.art + (key to path), catalogueCovers = data.catalogueCovers + key)
                    }
                    data = data.copy(tracks = data.tracks.map { if (it.id == t.id) moved else it })
                    note("album and cover from " + hit.from + ": " + t.artist + " / " + t.title + " → " + hit.artist + " / " + hit.album)
                    bySong++
                }
            }
            data = data.copy(coverTried = data.coverTried + al.key)
            if (i % 10 == 9) save()
        }
        save()
        note("covers: " + byAlbum + " albums given a cover by name, " + bySong + " songs put on their album with its cover")
    }

    /**
     * Step 3 (Kevin, 26 November 2026): songs neither the music catalogue nor their sound could place. Each gets the
     * catalogue's spelling of its artist when one is close (its own otherwise) and becomes a single of its own: the
     * song's name as its album and no cover, the rest of the old details taken out when its file is fixed. Once a song.
     */
    private fun sweepArtists(): Boolean {
        val d = data
        val todo = notFound(view, d).flatMap { it.tracks }
            // Only once its sound has been tried (step 2 comes first).
            .filter { it.id !in d.artistSwept && it.id in d.soundChecked && it.artist.isNotBlank() && Library.sortName(it.artist) != Library.sortName(Library.UNKNOWN_ARTIST) }
        if (todo.isEmpty()) return false
        val artists = todo.map { it.artist }.distinct()
        note("step 3: closest artist for " + todo.size + " songs, " + artists.size + " artists")
        val names = HashMap<String, String>()
        try {
            artists.forEachIndexed { i, a ->
                say("Finding the closest artist", i + 1, artists.size)
                val got = lookup("artist $a") { Catalogue.closestArtist(a) }
                if (got.isSuccess) names[a] = got.getOrNull() ?: a   // a failed lookup is asked again next time
            }
        } catch (e: TooManyFailures) {
            note("artist lookups stopped: " + e.message)
        }
        val ids = todo.filter { it.artist in names }.map { it.id }.toSet()
        if (ids.isEmpty()) return false
        val renamed = todo.count { it.id in ids && names[it.artist] != it.artist }
        data = data.copy(
            tracks = data.tracks.map { t -> if (t.id !in ids) t else Library.asOwnSingle(t.copy(artist = names.getValue(t.artist))) },
            artistSwept = data.artistSwept + ids,
        )
        data = data.copy(ownSingles = data.ownSingles + data.tracks.filter { it.id in ids }.map { keyOf(it) })
        save()
        note("step 3: " + ids.size + " songs made singles of their own, " + renamed + " with the catalogue's spelling of their artist")
        return true
    }

    /**
     * An album's catalogue cover fetched again when its kept picture has gone from the phone (Kevin, 26 November 2026:
     * "Its album's cover isn't on this phone" left songs stuck). True when it is back. Call off the main thread.
     */
    fun coverAgain(key: String): Boolean {
        val (release, group) = (data.releases[key] ?: return false).split('|').let { it[0] to it.getOrElse(1) { "" } }
        val bytes = runCatching { Catalogue.cover(Catalogue.Match("", "", 0, release, group)) }.getOrNull() ?: return false
        val path = keepPicture(bytes, key + "\u0002catalogue") ?: return false
        synchronized(this) { data = data.copy(art = data.art + (key to path), catalogueCovers = data.catalogueCovers + key); save() }
        return true
    }

    private fun matchAlbums() {
        waitingForWifi = false
        failsInARow = 0
        val loose = Library.compilationSongs(data.tracks)
        // Songs already looked up with no home are made singles first, so a busy catalogue stopping the lookups
        // below can't leave them on their collection (0.1.42 only did this after every lookup had finished).
        makeOwnSingles(loose)
        val todo = Library.albums(data.tracks).filter { al -> !onItsOwn(al) && al.tracks.any { !it.checked && it.id !in loose } }
        note("step 3: " + todo.size + " albums to look up")
        var found = 0
        var covers = 0
        var notFound = 0
        var bestOfs = 0
        try {
            todo.forEachIndexed { i, al ->
                say("Looking up albums", i + 1, todo.size)
                val (clean, _) = Library.cleanAlbum(al.title, al.artist)
                val asked = lookup(al.artist + " / " + clean) { Catalogue.findAlbum(clean, al.artist, al.tracks.size) }
                if (asked.isFailure) return@forEachIndexed
                val got = asked.getOrNull()
                // A greatest hits or other collection isn't kept as an album: its songs are each looked up below.
                val hits = got?.compilation == true || Catalogue.looksLikeHits(clean) || Catalogue.looksLikeHits(got?.title.orEmpty())
                val m = if (hits) null else got
                if (hits) {
                    bestOfs++
                    val wasCatalogue = al.key in data.catalogueCovers
                    data = data.copy(
                        releases = data.releases - al.key,
                        catalogueCovers = data.catalogueCovers - al.key,
                        art = if (wasCatalogue) data.art - al.key else data.art,
                    )
                } else if (m != null) found++ else {
                    notFound++
                    if (notFound <= 30) note("not in the catalogue: " + al.artist + " / " + clean)
                }
                val ids = al.tracks.map { it.id }.toSet()
                if (apply(ids, al, m, clean)) covers++
                // Kept as a collection, so a song off it that the catalogue can't place becomes a single of its own.
                if (hits) data = data.copy(collections = data.collections + data.tracks.filter { it.id in ids }.map { keyOf(it) })
                if (i % 10 == 9) save()
            }
            save()
            if (todo.isNotEmpty()) note("looked up " + todo.size + " albums: " + found + " found, " + bestOfs + " greatest hits or collections, " + notFound + " not found, " + covers + " covers fetched")

            // Each to the album it first came out on, or failing that its single: songs from compilations and
            // greatest hits, songs with no album, and songs on albums the catalogue doesn't know.
            val songs = Library.albums(data.tracks).flatMap { al ->
                val each = onItsOwn(al) || al.key !in data.releases
                al.tracks.filter { !it.ownLook && (it.id in loose || (each && (it.checked || onItsOwn(al)))) }.map { al to it }
            }
            var moved = 0
            var borrowed = 0
            songs.forEachIndexed { i, (al, t) ->
                say("Finding each song's own album", i + 1, songs.size)
                val home = lookup(t.artist + " / " + t.title) { Catalogue.findSongHome(t.title, t.artist) }.getOrNull() ?: return@forEachIndexed
                val target = home.album ?: home.single
                if (target != null) {
                    apply(setOf(t.id), al, target, target.title, carryCover = false)
                    moved++
                } else if (home.firstOut != null && borrowCover(t, home.firstOut)) borrowed++
                keepSingle(t.id, home.single)
                markSong(t.id) { it.copy(checked = true, ownLook = true, singleChecked = true) }
                if (i % 10 == 9) save()
            }
            save()
            if (songs.isNotEmpty()) note("looked up " + songs.size + " songs from compilations, greatest hits, unknown albums or with no album: " + moved + " given their own album or single, " + borrowed + " covers from the record the song first came out on")

            makeOwnSingles(loose)

            // Every other song: did it come out as a single? One question per song, so only on Wi-Fi.
            val rest = data.tracks.filter { !it.singleChecked }
            var singles = 0
            rest.forEachIndexed { i, t ->
                if (!Net.unmetered(app)) {
                    note("looking for singles waits for Wi-Fi")
                    waitingForWifi = true
                    save()
                    return
                }
                say("Looking for singles", i + 1, rest.size)
                val home = lookup(t.artist + " / " + t.title) { Catalogue.findSongHome(t.title, t.artist) }.getOrNull() ?: return@forEachIndexed
                if (keepSingle(t.id, home.single)) singles++
                markSong(t.id) { it.copy(singleChecked = true) }
                if (i % 20 == 19) save()
            }
            save()
            if (rest.isNotEmpty()) note("looked for singles for " + rest.size + " songs: " + singles + " found")
        } catch (e: TooManyFailures) {
            note("lookups stopped: " + e.message)
            problem = "Looking up stopped: " + (e.message ?: "no reason given") + ". It carries on next time KueSounds opens, or press Look for new music."
            save()
        }
    }

    /** When the last look through the music finished, start to end (0: not since KueSounds opened). */
    var finishedAt by mutableStateOf(0L)

    /** True when the last scan stopped looking for singles because the phone wasn't on Wi-Fi. */
    var waitingForWifi by mutableStateOf(false)
        private set

    /**
     * Songs off a greatest hits or compilation that the catalogue has neither an album nor a single for: each becomes
     * a single of its own, so no collection is left (Kevin, 24 November 2026). Songs on an ordinary album the catalogue
     * doesn't know ("Big Bam Boom") stay on that album. A song whose lookup failed hasn't been looked up yet, so it
     * waits for next time. Needs no lookups, so it never waits on the catalogue.
     */
    private fun makeOwnSingles(loose: Set<String>) {
        val left = Library.albums(data.tracks).flatMap { al ->
            al.tracks.filter { t -> t.ownLook && keyOf(t) !in data.releases && keyOf(t) !in data.ownSingles && fromCollection(t, al, loose) }
        }
        if (left.isEmpty()) return
        val ids = left.map { it.id }.toSet()
        data = data.copy(tracks = data.tracks.map { if (it.id in ids) Library.asOwnSingle(it) else it })
        data = data.copy(ownSingles = data.ownSingles + data.tracks.filter { it.id in ids }.map { keyOf(it) })
        save()
        note(left.size.toString() + " songs off greatest hits or compilations had no album or single in the catalogue: each is now a single of its own")
    }

    /**
     * Whether a song sits on a greatest hits or other collection: one of a compilation's songs, on an album of mixed
     * artists or with no album, on an album the catalogue files as a collection, or on one whose name says so.
     */
    private fun fromCollection(t: Track, al: Album, loose: Set<String>): Boolean =
        t.id in loose || onItsOwn(al) || keyOf(t) in data.collections || Catalogue.looksLikeHits(t.album)

    /** Albums whose songs are looked up one by one: compilations of mixed artists, and songs with no album. */
    private fun onItsOwn(al: Album): Boolean =
        Catalogue.isVarious(al.artist) || Library.sortName(al.title) == Library.sortName(Library.UNKNOWN_ALBUM)

    private fun markSong(id: String, change: (Track) -> Track) {
        data = data.copy(tracks = data.tracks.map { if (it.id == id) change(it) else it })
    }

    /**
     * A song with no album or single of its own in the catalogue keeps its place, and its album (if it has no
     * picture yet) gets the cover of the record the song first came out on, a best of included (Kevin, 25 November
     * 2026: "A Moment With You" is on George Michael's Ladies & Gentlemen, as Google and Shazam show). True when a
     * cover was kept. Never put in place of a cover the album already has.
     */
    private fun borrowCover(t: Track, from: Catalogue.Match): Boolean {
        val key = keyOf(data.tracks.firstOrNull { it.id == t.id } ?: t)
        if (data.art[key]?.let { File(it).exists() } == true) return false
        val path = runCatching { Catalogue.cover(from) }.getOrNull()?.let { keepPicture(it, key + "\u0002firstout") } ?: return false
        data = data.copy(art = data.art + (key to path))
        return true
    }

    /** Keeps the single a song came out as, with its cover. True when there was one. */
    private fun keepSingle(id: String, single: Catalogue.Match?): Boolean {
        if (single == null) return false
        // One cover per single, shared by every copy of the song.
        val key = "\u0003single" + single.groupId.ifEmpty { single.releaseId }
        var path = data.singleArt.values.firstOrNull { File(it).name == pictureName(key) && File(it).exists() }
        if (path == null) path = runCatching { Catalogue.cover(single) }.getOrNull()?.let { keepPicture(it, key) }
        data = data.copy(
            singleTitles = data.singleTitles + (id to single.title),
            singleArt = if (path != null) data.singleArt + (id to path) else data.singleArt,
        )
        return true
    }

    /**
     * Puts what the catalogue found on these songs (or, when nothing was found, the tidied album name),
     * and gives the album the catalogue's cover in place of any picture inside the files. True when a
     * catalogue cover was fetched.
     */
    private fun apply(ids: Set<String>, al: Album, m: Catalogue.Match?, cleanTitle: String, carryCover: Boolean = true): Boolean {
        val oldKey = al.key
        data = data.copy(
            tracks = data.tracks.map { t ->
                if (t.id !in ids) t else {
                    val disc = if (t.discNo > 0) t.discNo else Library.cleanAlbum(t.album, al.artist).second
                    val album = m?.title ?: cleanTitle
                    val albumArtist = m?.artist ?: t.albumArtist
                    t.copy(
                        album = album,
                        albumArtist = albumArtist,
                        year = m?.year?.takeIf { it > 0 } ?: t.year,
                        discNo = disc,
                        checked = true,
                        // A song given another album has its file fixed again, so the new album and cover go in.
                        fixed = t.fixed && album == t.album && albumArtist == t.albumArtist,
                    )
                }
            },
        )
        val sample = data.tracks.first { it.id in ids }
        val newKey = Library.albumKey(Library.albumArtistOf(sample), sample.album)
        if (m != null) data = data.copy(releases = data.releases + (newKey to m.releaseId + "|" + m.groupId))
        if (newKey in data.catalogueCovers) return false
        // The catalogue's cover comes first: a picture inside the files can be the wrong one.
        if (m != null) {
            val bytes = runCatching { Catalogue.cover(m) }.getOrNull()
            val path = bytes?.let { keepPicture(it, newKey + "\u0002catalogue") }
            if (path != null) {
                data = data.copy(art = data.art + (newKey to path), catalogueCovers = data.catalogueCovers + newKey)
                return true
            }
        }
        // No catalogue cover: one already found for the album (in its folder or its files) moves with it.
        // (Not for a song taken out of a compilation: the compilation's cover isn't its album's.)
        if (carryCover && newKey !in data.art) data.art[oldKey]?.let { data = data.copy(art = data.art + (newKey to it)) }
        return false
    }

    /** Shrinks a cover picture to at most 600 dots across and keeps it. Gives back the kept file's path. */
    private fun keepPicture(bytes: ByteArray, albumKey: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val file = File(artFolder(), pictureName(albumKey))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return file.path
    }

    private fun pictureName(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    /** The cover of the single a song came out as, if it did and there is one. */
    /** The catalogue's record a song's album was matched to: its id and its group's id, or null. */
    fun releaseFor(t: Track): Pair<String, String>? = data.releases[keyOf(t)]?.split('|')?.let { it[0] to it.getOrElse(1) { "" } }

    /** Keeps why a song's file couldn't be fixed (null: it was fixed), for the list in Settings. */
    fun fixTried(id: String, problem: String?) = synchronized(this) {
        if (problem == null && id !in data.fixProblems) return@synchronized
        // The file's change time with the failure: it is tried again only once the file has changed.
        val modified = data.tracks.firstOrNull { it.id == id }?.modified.orEmpty()
        data = data.copy(
            fixProblems = if (problem == null) data.fixProblems - id else data.fixProblems + (id to problem),
            fixFailedAt = if (problem == null) data.fixFailedAt - id else data.fixFailedAt + (id to Fixer.failedKey(modified)),
        )
        save()
    }

    /** Puts songs on an album (from Match by sound): kept at once, looked up again; the files get it on the next Fix. */
    fun setSongsAlbum(ids: Set<String>, album: String, albumArtist: String) {
        synchronized(this) {
            data = data.copy(tracks = data.tracks.map { t ->
                if (t.id !in ids) t else t.copy(album = album, albumArtist = albumArtist, checked = false, ownLook = false, fixed = false)
            })
            save()
            note("put on an album by sound: " + ids.size + " songs to " + albumArtist + " / " + album)
        }
        scan()
    }

    /** An album renamed by hand: its old key to one of its songs, so a page open on it follows it to its new name. */
    val renamed = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Albums the music catalogue didn't find (Kevin, 25 November 2026: nothing in his music is unknown, so a miss is
     * a name spelt differently): looked up, not matched, and not a collection or a single of its own.
     */
    fun notFound(view: LibraryView = this.view, d: LibraryData = data): List<Album> =
        view.albums.filter { al ->
            al.key !in d.releases && al.key !in d.ownSingles && al.key !in d.collections && al.key !in d.catalogueCovers && !onItsOwn(al) && al.tracks.all { it.checked } &&
                // Matched by their sound: their names are right, so they drop off the list (Kevin, 26 November 2026).
                !al.tracks.all { it.id in d.soundMatched }
        }

    /**
     * The songs not fixed, each with why in plain words (Kevin, 26 November 2026: the final list, kept short). Songs
     * still to be looked up, or ready to be written, aren't in it: they are on their way.
     */
    fun notFixed(view: LibraryView = this.view, d: LibraryData = data): List<Pair<Track, String>> {
        val missed = notFound(view, d).flatMap { it.tracks }.map { it.id }.toSet()
        val unknown = Library.sortName(Library.UNKNOWN_ARTIST)
        return d.tracks.mapNotNull { t ->
            if ((t.fixed && t.fixedFully) || t.settled) return@mapNotNull null
            val why = when {
                t.id in d.fixProblems && !Fixer.stuck(t, d) -> "Tried again at the next look, with the fixes made since."
                t.id in d.fixProblems -> d.fixProblems.getValue(t.id)
                !Tagger.canFix(Library.realName(t)) -> "KueSounds can't write into this kind of file (" + Library.ending(Library.realName(t)).uppercase() + ")."
                running -> null
                t.id in missed && (t.artist.isBlank() || Library.sortName(t.artist) == unknown) && t.id in d.soundChecked ->
                    "No artist, and its sound wasn't recognised. Tap it to put its name in."
                t.id in missed && t.id in d.soundChecked -> "Not found by its name or its sound. Tap it to put its name right."
                else -> null
            } ?: return@mapNotNull null
            t to why
        }
    }

    /**
     * Everything not fixed, worked out once for each state of the library and kept (Kevin, 27 November 2026: Fix your
     * music files slow to respond; the lists were worked out three times over on every change).
     */
    data class Left(val songs: List<Pair<Track, String>>, val albums: List<Album>) { val total get() = songs.size + albums.size }

    @Volatile private var leftCache: Triple<LibraryView, LibraryData, Boolean>? = null
    @Volatile private var leftValue: Left? = null

    fun left(view: LibraryView = this.view, d: LibraryData = data): Left {
        val key = Triple(view, d, running)
        leftValue?.let { v -> leftCache?.let { c -> if (c.first === view && c.second === d && c.third == running) return v } }
        return Left(notFixed(view, d), noCover(d)).also { leftCache = key; leftValue = it }
    }

    /** Ticks (or unticks) a song as reviewed by Kevin after KueSounds or the AI changed it. */
    fun markReviewed(id: String, on: Boolean) = synchronized(this) {
        data = data.copy(reviewed = if (on) data.reviewed + id else data.reviewed - id)
        save()
    }

    /** Songs Sort with AI wrote into ([on]), or that Kevin then changed by hand (not). */
    fun markAiWritten(ids: Collection<String>, on: Boolean) = synchronized(this) {
        val now = if (on) data.aiWritten + ids else data.aiWritten - ids.toSet()
        if (now != data.aiWritten) { data = data.copy(aiWritten = now); save() }
    }

    /** Whether a song was changed by KueSounds or the AI, not by Kevin's own hand (so it can be ticked as reviewed). */
    fun changedForMe(t: Track, d: LibraryData = data): Boolean = (t.fixed && !t.settled) || t.id in d.aiWritten

    /** Songs KueSounds or the AI changed that Kevin hasn't ticked as reviewed, in album order. */
    fun toReview(d: LibraryData = data): List<Track> = d.tracks.filter { changedForMe(it, d) && it.id !in d.reviewed }
        .sortedWith(compareBy({ Library.sortName(Library.albumArtistOf(it)) }, { Library.sortName(it.album) }, { it.discNo }, { it.trackNo }))

    /** Notes that a song's kind of music came from online, or that it is now written in its file. */
    fun markKind(id: String, fromWeb: Boolean = false, written: Boolean = false, tried: Boolean = false) = synchronized(this) {
        data = data.copy(
            kindFromWeb = if (fromWeb) data.kindFromWeb + id else data.kindFromWeb,
            kindWritten = if (written) data.kindWritten + id else data.kindWritten,
            kindTried = if (tried) data.kindTried + id else data.kindTried,
        )
        save()
    }

    /** Songs whose kind of music KueSounds knows but their file doesn't hold yet (Kevin, 27 November 2026). */
    fun kindsToWrite(d: LibraryData = data): List<Track> = d.tracks.filter { t ->
        t.id !in d.kindWritten && Tagger.canFix(Library.realName(t)) && (t.genre.isBlank() || t.id in d.kindFromWeb) &&
            Library.genreOf(t, d).isNotBlank()
    }

    /** Songs with no genre known at all, not yet looked for online by the genre check. */
    fun kindsUnknown(d: LibraryData = data): List<Track> = d.tracks.filter { t ->
        t.id !in d.kindTried && t.id !in d.kindWritten && Tagger.canFix(Library.realName(t)) && Library.genreOf(t, d).isBlank()
    }

    /** Albums whose songs are written but have no cover from anywhere yet, for the list in Settings. */
    fun noCover(d: LibraryData = data): List<Album> =
        // Every album the list shows with no picture, once its songs have been written (or made singles), unless every
        // song in it was settled by hand. Before, singles of their own and albums written with a cover the list
        // couldn't show were left out, so "every song has its cover" was said over empty pictures (27 November 2026).
        Library.albums(d.tracks).filter { al ->
            (al.tracks.any { it.fixed } || al.key in d.ownSingles) && al.tracks.any { !it.settled } && artFor(al) == null
        }

    /** Songs put right by Match by sound (Use these): their album leaves the not-found list. */
    fun markSoundMatched(ids: Set<String>) = synchronized(this) {
        data = data.copy(soundMatched = data.soundMatched + ids)
        save()
    }

    /**
     * An album's name and artist put right by hand: kept in the library at once and looked up again with the new
     * name. The files get it when they are next fixed.
     */
    fun renameAlbum(al: Album, title: String, artist: String) {
        synchronized(this) {
            val ids = al.tracks.map { it.id }.toSet()
            al.tracks.firstOrNull()?.let { renamed[al.key] = it.id }
            val oldArt = data.art[al.key]
            data = data.copy(tracks = data.tracks.map { t ->
                if (t.id !in ids) t else t.copy(album = title, albumArtist = artist, checked = false, ownLook = false, fixed = false)
            })
            val newKey = Library.albumKey(artist, title)
            if (oldArt != null && al.key !in data.catalogueCovers && newKey !in data.art) data = data.copy(art = data.art + (newKey to oldArt))
            save()
            note("album renamed by hand: " + al.artist + " / " + al.title + " to " + artist + " / " + title)
        }
        scan()
    }

    /** Takes songs out of the library (they were deleted from the Drive). */
    fun removeTracks(ids: Set<String>) = synchronized(this) {
        data = data.copy(tracks = data.tracks.filter { it.id !in ids })
        save()
    }

    fun singleArtFor(t: Track): File? = data.singleArt[t.id]?.let { File(it) }?.takeIf { it.exists() }

    /** The cover picture kept for an album, if there is one. */
    // An album that is a single of its own shows its single's cover (Kevin, 27 November 2026: albums in the list with no
    // picture while Fix your music files said every song had its cover).
    fun artFor(album: Album): File? = data.art[album.key]?.let { File(it) }?.takeIf { it.exists() }
        ?: album.tracks.firstNotNullOfOrNull { singleArtFor(it) }

    fun artForTrack(t: Track): File? = data.art[Library.albumKey(Library.albumArtistOf(t), t.album)]?.let { File(it) }?.takeIf { it.exists() }

    /**
     * A song file read in pieces straight from the Drive, for the phone's own details reader. Pieces of
     * 64 KB are fetched as asked for and the last few kept, so the start and end of a file are read once.
     */
    internal class DriveSource(private val id: String, private val length: Long, private val token: String) : MediaDataSource() {
        private val blockSize = 64 * 1024L
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 48
        }
        private var fetched = 0L

        /** Set when the Drive couldn't be read (as against a file that holds no details). */
        @Volatile var failure: Exception? = null

        override fun getSize(): Long = length

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            // A file that needs much more than its details read (8 MB) is given up on.
            if (fetched > 8L * 1024 * 1024) throw java.io.IOException("too much reading")
            var done = 0
            var pos = position
            while (done < size && pos < length) {
                val index = pos / blockSize
                val block = blocks[index] ?: fetch(index)
                val inBlock = (pos - index * blockSize).toInt()
                val n = minOf(size - done, block.size - inBlock)
                if (n <= 0) break
                System.arraycopy(block, inBlock, buffer, offset + done, n)
                done += n
                pos += n
            }
            return if (done == 0) -1 else done
        }

        private fun fetch(index: Long): ByteArray {
            val from = index * blockSize
            val to = minOf(length - 1, from + blockSize - 1)
            val bytes = try {
                Drive.readRange(id, from, to, token)
            } catch (e: Exception) {
                failure = e
                throw e
            }
            fetched += bytes.size
            blocks[index] = bytes
            return bytes
        }

        override fun close() {
            blocks.clear()
        }
    }
}
