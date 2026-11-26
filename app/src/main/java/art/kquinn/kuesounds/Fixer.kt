package art.kquinn.kuesounds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Fixing the files: for each album the catalogue has matched, its list of songs is fetched, then every song
 * file is downloaded and fixed in this order: its own name and artist put right from that list, everything
 * else written inside it taken out, then the album's proper name, artist, year and the catalogue's cover
 * written in. The file is checked, and it goes back to the Drive as a new version of the same file.
 * Google keeps the old version for 30 days. A file that fails any step is left exactly as it was.
 */
object Fixer {
    /** Albums waiting to be fixed, by album key. */
    private val waiting = LinkedHashSet<String>()

    var status by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var lastResult by mutableStateOf("")
        private set
    /** How far through the albums waiting it is, 0 to 1; -1 when not working. For the bar on screen (Kevin: vital). */
    var progress by mutableStateOf(-1f)
        private set

    /** Why asking for the edit permission failed, shown under the button. */
    var permissionProblem by mutableStateOf("")

    /** Asks Google for the edit permission, then does what is given. Set by the main screen. */
    var askPermission: (then: () -> Unit) -> Unit = { it() }

    /**
     * Asks to show notices, so the fixing's and saving's progress shows in the pull-down. Only from
     * Settings › Notifications: fixing and saving work the same without them. Set by the main screen.
     */
    var askNotices: () -> Unit = {}

    /** Whether KueSounds may show notices. Kept up to date by the main screen. */
    var noticesOn by androidx.compose.runtime.mutableStateOf(false)

    /** Fixes [albums], asking for the edit permission first when it hasn't been given. */
    fun fix(context: Context, albums: List<Album>) {
        // Fixed or stuck, every song is accounted for; Fix only starts on albums with something left to do.
        val app = context.applicationContext
        if (Google.canEdit) start(app, albums) else askPermission { start(app, albums) }
    }

    /**
     * Whether an album can be fixed: the catalogue matched it and gave it a cover, or it is a song made a single of
     * its own (written with no cover, so the compilation's picture comes out of the file).
     */
    fun canFix(al: Album, d: LibraryData = Scanner.data): Boolean =
        // Known to MusicBrainz, or given a cover from Deezer, Apple, Shazam or by hand, or a single of its own
        // (26 November 2026: an album MusicBrainz had no cover for was never written at all).
        (al.key in d.releases || al.key in d.catalogueCovers || al.key in d.ownSingles) && al.tracks.any { Tagger.canFix(Library.realName(it)) }

    /** Fixed the full way (song name and artist put right, the rest taken out, album and cover written). */
    fun isFixed(al: Album): Boolean = al.tracks.filter { Tagger.canFix(Library.realName(it)) }.all { it.fixed && it.fixedFully }

    /** Why fixing a file failed, in plain words for the list in Settings (Kevin, 25 November 2026). */
    private fun plain(e: Exception): String {
        val said = (e.javaClass.simpleName + " " + e.message).lowercase()
        return when {
            "offsets" in said || "atom" in said || "identifier is invalid" in said -> "Its file is laid out in a way that couldn't be written."
            "length changed" in said -> "It read as a different length after the change, so it was left as it was."
            "cut short" in said -> "The download from your Drive was cut short."
            "didn't stick" in said -> "The new details didn't stay in the file."
            "pictures" in said -> "The cover didn't go in properly."
            else -> "It didn't work (" + (e.message ?: e.javaClass.simpleName) + ")."
        }
    }

    fun isWaiting(al: Album): Boolean = synchronized(waiting) { al.key in waiting }

    /**
     * A song whose file got stuck last time (Kevin, 25 November 2026: "skip but record any it gets stuck on and do
     * the rest"): skipped by Fix and listed in Settings with why. Tried again by itself once its file has changed.
     */
    // Kevin, 26 November 2026: "not sure constantly redoing is the answer if nothing has changed in the file". A stuck
    // song is tried again by itself once its file has changed (its change time differs from when it failed).
    fun stuck(t: Track, d: LibraryData = Scanner.data): Boolean = t.id in d.fixProblems && d.fixFailedAt[t.id] == t.modified

    /** The album's songs still to fix, the stuck ones apart. */
    fun toDo(al: Album, d: LibraryData = Scanner.data): List<Track> =
        al.tracks.filter { Tagger.canFix(Library.realName(it)) && needsWrite(it, al, d) && !stuck(it, d) }

    /**
     * Whether a song's file still needs writing: never written, or written before its album had a cover and it has
     * one now. A song written with no cover anywhere is not written again until one turns up (no redoing).
     */
    fun needsWrite(t: Track, al: Album, d: LibraryData = Scanner.data): Boolean =
        !t.fixed || (!t.fixedFully && al.key in d.catalogueCovers)

    /** Whether Fix has anything to do on this album: matched, and a song left that isn't stuck. */
    fun hasWork(al: Album, d: LibraryData = Scanner.data): Boolean = canFix(al, d) && toDo(al, d).isNotEmpty()

    /** True for a run started by "Try the stuck ones again": the stuck songs are tried too. */
    @Volatile private var retrying = false

    /** Adds albums to fix and starts the work, which carries on with the screens closed. */
    fun start(context: Context, albums: List<Album>, retry: Boolean = false) {
        if (retry) retrying = true
        synchronized(waiting) { albums.filter { canFix(it) && (if (retry) !isFixed(it) else hasWork(it)) }.forEach { waiting += it.key } }
        WorkService.start(context)
    }

    /** Every album with something to write, queued for writing straight after the lookups. */
    fun queueAll(albums: List<Album>) = synchronized(waiting) { albums.filter { hasWork(it) }.forEach { waiting += it.key } }

    /** Tries the stuck songs once more. */
    fun retryStuck(context: Context) {
        val d = Scanner.data
        val albums = Library.albums(d.tracks).filter { al -> al.tracks.any { stuck(it, d) } }
        val app = context.applicationContext
        if (Google.canEdit) start(app, albums, retry = true) else askPermission { start(app, albums, retry = true) }
    }

    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() } || renameWanted

    /**
     * File names to match the songs (Kevin, 26 November 2026: "Steve Winwood - Higher Love (Official Music Video).m4a"
     * should be the song's name). A fixed file is named "Artist - Song name", with its own ending. Signs a computer
     * can't have in a file name are left out.
     */
    fun fileNameFor(t: Track, title: String = t.title, artist: String = t.artist): String {
        val ending = Library.ending(Library.realName(t))
        val who = artist.takeIf { it.isNotBlank() && it != Library.UNKNOWN_ARTIST }
        val stem = listOfNotNull(who, title.ifBlank { null }).joinToString(" - ")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(150)
        if (stem.isEmpty()) return t.fileName
        return if (ending.isEmpty()) stem else "$stem.$ending"
    }

    /** Fixed songs whose file name doesn't match the song yet. */
    fun namesToTidy(d: LibraryData = Scanner.data): List<Track> =
        d.tracks.filter { it.fixed && Tagger.canFix(Library.realName(it)) && it.fileName != fileNameFor(it) }

    @Volatile private var renameWanted = false

    /** Gives the fixed songs' files their songs' names. Carries on with the screens closed. */
    fun tidyNames(context: Context) {
        val app = context.applicationContext
        val go = { renameWanted = true; WorkService.start(app) }
        if (Google.canEdit) go() else askPermission { go() }
    }

    /** Renames each file still named otherwise; only the name changes, so it is quick. */
    private suspend fun renameFiles(progress: (String) -> Unit): Int {
        val todo = namesToTidy()
        var done = 0
        for ((i, t) in todo.withIndex()) {
            status = "Naming files · " + (i + 1) + " of " + todo.size
            this.progress = i.toFloat() / todo.size
            progress(status)
            val name = fileNameFor(t)
            try {
                val token = Google.editToken() ?: throw Drive.SignedOut()
                val modified = Drive.setName(t.id, name, token)
                Scanner.changeTracks(setOf(t.id)) { it.copy(fileName = name, modified = modified.ifEmpty { it.modified }) }
                done++
            } catch (e: Drive.SignedOut) {
                break
            } catch (e: Exception) {
                Scanner.note("not renamed: " + t.fileName + ": " + e.javaClass.simpleName + " " + e.message)
            }
        }
        if (todo.isNotEmpty()) Scanner.note("file names matched to songs: $done of " + todo.size)
        return done
    }

    private fun next(): String? = synchronized(waiting) { waiting.firstOrNull()?.also { waiting.remove(it) } }

    /** Works through the waiting albums. Called by [WorkService]. */
    suspend fun run(context: Context, progress: (String) -> Unit, inline: Boolean = false) {
        running = true
        var fixedCount = 0
        var failed = 0
        var notLookedUp = 0
        var renamed = 0
        var albumsDone = 0
        try {
            // A search of the Drive going on is let finish first, so the two don't change the library at once.
            // (Not when the search itself hands over to writing, straight after its lookups.)
            if (!inline) while (Scanner.running) delay(2000)
            while (true) {
                val total = synchronized(waiting) { waiting.size } + albumsDone
                this.progress = if (total > 0) albumsDone.toFloat() / total else -1f
                val key = next() ?: break
                albumsDone++
                val al = Library.albums(Scanner.data.tracks).firstOrNull { it.key == key } ?: continue  // fresh, not the screens' copy
                // A single of its own has no catalogue record: no list of songs, and no cover (the compilation's comes out).
                val own = key in Scanner.data.ownSingles && key !in Scanner.data.releases
                val songs = al.tracks.filter { Tagger.canFix(Library.realName(it)) && needsWrite(it, al) && (retrying || !stuck(it)) }
                var cover = if (own) null else Scanner.data.art[key]?.let { File(it) }?.takeIf { it.exists() && key in Scanner.data.catalogueCovers }
                if (!own && cover == null) {
                    // The album's cover has gone from the phone: fetched again from the catalogue here and now (it used to
                    // leave the songs stuck with "Look for new music fetches it again").
                    status = "Fixing files · " + al.title + " · fetching its cover again"
                    progress(status)
                    if (Scanner.coverAgain(key)) cover = Scanner.data.art[key]?.let { File(it) }?.takeIf { it.exists() }
                }
                // Still no cover anywhere: the names are written without one, and the song is listed as having no cover.
                if (!own && cover == null) Scanner.note("no cover found anywhere for " + al.artist + " / " + al.title + ": names written without one")
                // 1 and 2: the album's own list of songs, with each one's proper name and artist.
                status = "Fixing files · " + al.title + " · finding its songs' proper names"
                progress(status)
                val releaseId = Scanner.data.releases[key]?.substringBefore('|').orEmpty()
                // Known only by its cover (Deezer, Apple, Shazam, by hand): the files' own song names are kept.
                val listed = if (own || releaseId.isEmpty()) emptyList() else runCatching { Catalogue.releaseSongs(releaseId) }
                    .onFailure { Scanner.note("couldn't list the songs of " + al.artist + " / " + al.title + ": " + it.javaClass.simpleName + " " + it.message) }
                    .getOrNull()
                if (listed == null) { notLookedUp++; continue }  // left to fix, for the next Fix
                songs.forEachIndexed { i, t ->
                    status = "Fixing files · " + al.title + " · " + (i + 1) + " of " + songs.size
                    progress(status)
                    val ok = fixOne(context, t, al, cover, Catalogue.songFor(listed, t.title))
                    if (ok) fixedCount++ else failed++
                }
            }
            // Then every fixed song's file gets its song's name.
            renameWanted = false
            renamed = renameFiles(progress)
        } finally {
            status = ""
            this.progress = -1f
            running = false
            retrying = false
            renameWanted = false
            lastResult = "Last time: " + count(fixedCount, "song") + " fixed" + (if (renamed > 0) ", " + count(renamed, "file") + " renamed" else "") + (if (failed > 0) ", " + failed + " stuck and skipped" else "") +
                (if (notLookedUp > 0) ". The music catalogue was busy for " + count(notLookedUp, "album") + ": press Fix again later." else ".")
            Scanner.note("fixing finished: $fixedCount fixed, $failed not fixed, $notLookedUp albums not looked up")
        }
    }

    /** A song's details as edited by hand in Song info (Kevin, 26 November 2026: one table that writes to the file). */
    data class Edit(val title: String, val artist: String, val album: String, val albumArtist: String, val year: Int, val trackNo: Int, val discNo: Int)

    /** A song's name and artist put right by hand; the rest as it is. */
    suspend fun rename(context: Context, t: Track, title: String, artist: String): String =
        saveDetails(context, t, Edit(title, artist, t.album, Library.albumArtistOf(t), t.year, t.trackNo, t.discNo))

    /**
     * A song's details put right by hand and written into its file in the Drive, with everything else inside taken
     * out; the file is named after the song. The cover is its album's (the new album's when the album changed) or
     * its single's. The answer is what to say.
     */
    suspend fun saveDetails(context: Context, t: Track, e: Edit): String {
        if (!Tagger.canFix(Library.realName(t))) return "KueSounds can't write into this kind of file (" + Library.ending(Library.realName(t)) + ")."
        val newKey = Library.albumKey(e.albumArtist.ifBlank { e.artist }, e.album)
        val cover = Scanner.data.art[newKey]?.let { File(it) }?.takeIf { it.exists() && newKey in Scanner.data.catalogueCovers }
            ?: Scanner.singleArtFor(t).takeIf { e.album == t.album }
        val work = File(context.cacheDir, "fix").apply { mkdirs() }
        val file = File(work, t.id + "." + Library.ending(Library.realName(t)))
        val albumChanged = e.album != t.album || e.albumArtist != Library.albumArtistOf(t)
        return try {
            val token = Google.editToken() ?: throw Drive.SignedOut()
            Drive.downloadWhole(t.id, file, token)
            if (t.size > 0 && file.length() != t.size) throw java.io.IOException("the download was cut short")
            writeDetails(file, t, Tagger.Details(e.title, e.artist, e.album, e.albumArtist, e.year, e.trackNo, e.discNo, cover), token)
            val named = t.copy(title = e.title, artist = e.artist)
            val newName = fileNameFor(named, e.title, e.artist)
            val (size, modified) = Drive.uploadNewVersion(t.id, file, t.mime, Google.editToken() ?: token, newName)
            Scanner.changeTracks(setOf(t.id)) {
                it.copy(
                    size = size, modified = modified.ifEmpty { it.modified }, title = e.title, artist = e.artist, album = e.album,
                    albumArtist = e.albumArtist, year = e.year, trackNo = e.trackNo, discNo = e.discNo, named = true, fileName = newName,
                    // Kept on the album given by hand; looked up again for its cover when the album changed and has none.
                    ownLook = true, checked = if (albumChanged && cover == null) false else it.checked,
                    fixed = true, fixedFully = cover != null,
                )
            }
            Scanner.fixTried(t.id, null)
            Scanner.note("saved by hand: " + t.fileName + " → " + e.artist + " / " + e.title + " / " + e.album)
            "Saved into the file in your Drive."
        } catch (x: Drive.SignedOut) {
            "Not saved: Google needs the edit permission. Press Write to the files once, then try again."
        } catch (x: Exception) {
            Scanner.note("not saved by hand: " + t.fileName + ": " + x.javaClass.simpleName + " " + x.message)
            "Not saved: " + (x.message ?: x.javaClass.simpleName)
        } finally {
            file.delete()
            File(work, t.id + ".rebuilt." + Library.ending(Library.realName(t))).delete()
            File(work, t.id + ".original." + Library.ending(Library.realName(t))).delete()
        }
    }

    /**
     * Writes [d] into [file]. An M4A that can't be written even after its sound is copied into a fresh file (Gordon
     * Lightfoot's "Live in Concert", 25-26 November 2026) has its sound made again as AAC at the same quality, once,
     * and is then written: the one way left to get its details in.
     */
    private suspend fun writeDetails(file: File, t: Track, d: Tagger.Details, token: String) {
        val seconds = (t.durationMs / 1000).toInt()
        try {
            Tagger.fix(file, d, seconds) { from, to -> rebuildMp4(from, to) }
        } catch (first: Exception) {
            if (Library.ending(file.name) !in setOf("m4a", "m4b", "mp4")) throw first
            Drive.downloadWhole(t.id, file, token)
            val again = File(file.parentFile, t.id + ".again.m4a")
            try {
                val bps = if (t.durationMs > 0 && t.size > 0) (t.size * 8 / (t.durationMs / 1000.0)).toInt() else 192_000
                Shrink.toAac(file, again, bps.coerceIn(128_000, 320_000))
                again.copyTo(file, overwrite = true)
            } finally {
                again.delete()
            }
            Tagger.fix(file, d, 0)
            Scanner.note("made again as AAC so its details could be written: " + t.fileName)
        }
    }

    /**
     * One song. Google can refuse a pass the phone handed over as still good (it came from the phone's store, so its
     * age isn't known): on 24 Nov 2026 Kevin's phone was refused for at least 17 minutes in a row while KueSounds
     * still counted its pass as fresh. A refusal now drops that pass and the song is tried once more with a new one;
     * only a second refusal stops the fixing, which is when Google really does want the permission asked for again.
     */
    private suspend fun fixOne(context: Context, t: Track, al: Album, cover: File?, song: Catalogue.Song?): Boolean {
        try {
            return fixOnce(context, t, al, cover, song)
        } catch (e: Drive.SignedOut) {
            Scanner.note("Google refused the pass while fixing " + t.fileName + ": trying again with a new one")
        }
        return try {
            fixOnce(context, t, al, cover, song)
        } catch (e: Drive.SignedOut) {
            Scanner.note("fixing stopped: Google needs the edit permission again")
            synchronized(waiting) { waiting.clear() }
            false
        }
    }

    private fun count(n: Int, word: String) = n.toString() + " " + word + if (n == 1) "" else "s"

    /**
     * An M4A whose inside the tag library can't rewrite ("incorrect offsets", "identifier is invalid", seen on 4 of
     * Kevin's files 24-25 November 2026): its sound is copied, untouched, into a fresh file of the same kind, which
     * the tag library can then write. The sound isn't re-encoded, so it is exactly as it was. Android does the copy.
     */
    private fun rebuildMp4(from: File, to: File): Boolean = runCatching {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(from.path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); return false }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val size = if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val buffer = java.nio.ByteBuffer.allocate(maxOf(size, 1 shl 20))
        val mux = android.media.MediaMuxer(to.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val out = mux.addTrack(format)
            mux.start()
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                info.offset = 0
                info.size = ex.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                mux.writeSampleData(out, buffer, info)
                ex.advance()
            }
            mux.stop()
        } finally {
            runCatching { mux.release() }
            ex.release()
        }
        to.length() > 0
    }.getOrDefault(false)

    /** One try at one song. Throws [Drive.SignedOut] when Google refuses the pass; any other failure is noted. */
    private suspend fun fixOnce(context: Context, t: Track, al: Album, cover: File?, song: Catalogue.Song?): Boolean {
        val work = File(context.cacheDir, "fix").apply { mkdirs() }
        val file = File(work, t.id + "." + Library.ending(Library.realName(t)))
        return try {
            val token = Google.editToken() ?: throw Drive.SignedOut()
            Drive.downloadWhole(t.id, file, token)
            if (t.size > 0 && file.length() != t.size) throw java.io.IOException("the download was cut short")
            val artist = t.albumArtist.ifBlank { al.artist }
            // The catalogue's name and artist for this song when the album lists it; otherwise the file's own.
            // A name put right by hand is kept.
            val title = if (t.named) t.title else song?.title ?: t.title
            val singer = if (t.named) t.artist else song?.artist?.ifBlank { null } ?: t.artist
            val trackNo = song?.position?.takeIf { it > 0 } ?: t.trackNo
            val discNo = song?.disc?.takeIf { it > 0 } ?: t.discNo
            writeDetails(file, t, Tagger.Details(title, singer, al.title, artist, al.year, trackNo, discNo, cover), token)
            val newName = fileNameFor(t, title, singer)
            val (size, modified) = Drive.uploadNewVersion(t.id, file, t.mime, Google.editToken() ?: token, newName)
            Scanner.changeTracks(setOf(t.id)) {
                it.copy(
                    size = size, modified = modified.ifEmpty { it.modified }, title = title, artist = singer, trackNo = trackNo, discNo = discNo,
                    albumArtist = artist, fixed = true, fixedFully = cover != null || al.key in Scanner.data.ownSingles, fileName = newName,
                )
            }
            Scanner.fixTried(t.id, null)
            true
        } catch (e: Drive.SignedOut) {
            throw e
        } catch (e: Exception) {
            Scanner.note("not fixed: " + t.fileName + ": " + e.javaClass.simpleName + " " + e.message)
            Scanner.fixTried(t.id, plain(e))
            false
        } finally {
            file.delete()
            File(work, t.id + ".rebuilt." + Library.ending(Library.realName(t))).delete()
            File(work, t.id + ".original." + Library.ending(Library.realName(t))).delete()
        }
    }
}

/**
 * Keeps the long jobs going with the screens closed or the phone locked (looking through the music,
 * saving songs to the phone, fixing files), with a notice showing how far they have got.
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WorkService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notice(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("work", "Looking through, saving and fixing music", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, "work")
            .setSmallIcon(R.drawable.kue_status)
            .setContentTitle("KueSounds")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, 7, notice("Working on your music"),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!started) {
            started = true
            scope.launch {
                val show: (String) -> Unit = { text -> getSystemService(NotificationManager::class.java).notify(7, notice(text)) }
                // The phone stays awake enough to keep working with the screen off, for at most 6 hours at a time.
                val awake = getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KueSounds:work").apply { setReferenceCounted(false) }
                // And the Wi-Fi with it (Kevin, 26 November 2026: the clean-up stopped when the screen went off). The work
                // kept the phone awake but not its Wi-Fi, which Android lets doze with the screen off; matching by sound
                // then waited for Wi-Fi that wasn't coming back.
                val wifi = runCatching {
                    @Suppress("DEPRECATION")
                    val mode = if (android.os.Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).createWifiLock(mode, "KueSounds:work").apply { setReferenceCounted(false) }
                }.getOrNull()
                // The screen going off and on, in the report, so a stop can be matched to it.
                val screen = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        Scanner.note((if (i.action == Intent.ACTION_SCREEN_OFF) "screen off" else "screen on") + " while working: " + Scanner.status.ifBlank { Fixer.status })
                    }
                }
                runCatching { registerReceiver(screen, android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }) }
                try {
                    do {
                        awake.acquire(6 * 60 * 60_000L)
                        runCatching { wifi?.acquire() }
                        // Looking through the music: the notice follows how far it has got.
                        var shown = ""
                        var beat = 0
                        while (Scanner.running) {
                            val now = Scanner.status.ifEmpty { "Looking through your music" }
                            if (now != shown) { shown = now; show(now) }
                            // Every 5 minutes, where it has got to, so the report shows whether it carried on.
                            if (++beat % 150 == 0) Scanner.note("still working: $now")
                            kotlinx.coroutines.delay(2000)
                        }
                        if (Offline.hasWork) Offline.run(show)
                        if (Fixer.hasWork) Fixer.run(this@WorkService, show)
                    } while (Scanner.running || Offline.hasWork || Fixer.hasWork)
                } finally {
                    if (awake.isHeld) awake.release()
                    runCatching { if (wifi?.isHeld == true) wifi.release() }
                    runCatching { unregisterReceiver(screen) }
                }
                started = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 and later stop this kind of background work after 6 hours in a day. Said in the report and stopped
     * cleanly (otherwise Android closes KueSounds); the work carries on from where it was at the next look.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Scanner.note("Android's 6-hour daily limit for background work reached: stopped, carries on at the next look")
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
