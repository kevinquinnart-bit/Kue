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
        val app = context.applicationContext
        if (Google.canEdit) start(app, albums) else askPermission { start(app, albums) }
    }

    /**
     * Whether an album can be fixed: the catalogue matched it and gave it a cover, or it is a song made a single of
     * its own (written with no cover, so the compilation's picture comes out of the file).
     */
    fun canFix(al: Album, d: LibraryData = Scanner.data): Boolean =
        ((al.key in d.releases && al.key in d.catalogueCovers) || al.key in d.ownSingles) && al.tracks.any { Tagger.canFix(it.fileName) }

    /** Fixed the full way (song name and artist put right, the rest taken out, album and cover written). */
    fun isFixed(al: Album): Boolean = al.tracks.filter { Tagger.canFix(it.fileName) }.all { it.fixed && it.fixedFully }

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

    /** Adds albums to fix and starts the work, which carries on with the screens closed. */
    fun start(context: Context, albums: List<Album>) {
        synchronized(waiting) { albums.filter { canFix(it) && !isFixed(it) }.forEach { waiting += it.key } }
        WorkService.start(context)
    }

    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() }

    private fun next(): String? = synchronized(waiting) { waiting.firstOrNull()?.also { waiting.remove(it) } }

    /** Works through the waiting albums. Called by [WorkService]. */
    suspend fun run(context: Context, progress: (String) -> Unit) {
        running = true
        var fixedCount = 0
        var failed = 0
        var notLookedUp = 0
        try {
            // A search of the Drive going on is let finish first, so the two don't change the library at once.
            while (Scanner.running) delay(2000)
            while (true) {
                val key = next() ?: break
                val al = Library.albums(Scanner.data.tracks).firstOrNull { it.key == key } ?: continue  // fresh, not the screens' copy
                // A single of its own has no catalogue record: no list of songs, and no cover (the compilation's comes out).
                val own = key in Scanner.data.ownSingles && key !in Scanner.data.releases
                val cover = if (own) null else Scanner.data.art[key]?.let { File(it) }?.takeIf { it.exists() && key in Scanner.data.catalogueCovers } ?: continue
                val songs = al.tracks.filter { Tagger.canFix(it.fileName) && !(it.fixed && it.fixedFully) }
                // 1 and 2: the album's own list of songs, with each one's proper name and artist.
                status = "Fixing files · " + al.title + " · finding its songs' proper names"
                progress(status)
                val releaseId = Scanner.data.releases[key]?.substringBefore('|').orEmpty()
                val listed = if (own) emptyList() else if (releaseId.isEmpty()) null else runCatching { Catalogue.releaseSongs(releaseId) }
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
        } finally {
            status = ""
            running = false
            lastResult = "Last time: " + count(fixedCount, "song") + " fixed" + (if (failed > 0) ", " + failed + " couldn't be" else "") +
                (if (notLookedUp > 0) ". The music catalogue was busy for " + count(notLookedUp, "album") + ": press Fix again later." else ".")
            Scanner.note("fixing finished: $fixedCount fixed, $failed not fixed, $notLookedUp albums not looked up")
        }
    }

    /**
     * A song's name and artist put right by hand, and written into its file in the Drive with the rest of what
     * is inside taken out: only its name, artist, album, year, place and cover stay. The cover is the album's from
     * the music catalogue, or its single's; any other picture inside is taken out. The answer is what to say.
     */
    suspend fun rename(context: Context, t: Track, title: String, artist: String): String {
        if (!Tagger.canFix(t.fileName)) return "KueSounds can't change this kind of file (" + Library.ending(t.fileName) + ")."
        val key = Library.albumKey(Library.albumArtistOf(t), t.album)
        val cover = Scanner.data.art[key]?.let { File(it) }?.takeIf { it.exists() && key in Scanner.data.catalogueCovers }
            ?: Scanner.singleArtFor(t)
        val work = File(context.cacheDir, "fix").apply { mkdirs() }
        val file = File(work, t.id + "." + Library.ending(t.fileName))
        return try {
            val token = Google.editToken() ?: throw Drive.SignedOut()
            Drive.downloadWhole(t.id, file, token)
            if (t.size > 0 && file.length() != t.size) throw java.io.IOException("the download was cut short")
            val albumArtist = Library.albumArtistOf(t)
            Tagger.fix(file, Tagger.Details(title, artist, t.album, albumArtist, t.year, t.trackNo, t.discNo, cover), (t.durationMs / 1000).toInt()) { from, to -> rebuildMp4(from, to) }
            val (size, modified) = Drive.uploadNewVersion(t.id, file, t.mime, Google.editToken() ?: token)
            Scanner.changeTracks(setOf(t.id)) {
                it.copy(size = size, modified = modified.ifEmpty { it.modified }, title = title, artist = artist, albumArtist = albumArtist, named = true)
            }
            Scanner.note("renamed by hand: " + t.fileName + " to " + artist + " / " + title)
            "Saved in your Drive."
        } catch (e: Drive.SignedOut) {
            "Not saved: Google needs the edit permission. Press Fix on an album once, then try again."
        } catch (e: Exception) {
            Scanner.note("not renamed: " + t.fileName + ": " + e.javaClass.simpleName + " " + e.message)
            "Not saved: " + (e.message ?: e.javaClass.simpleName)
        } finally {
            file.delete()
            File(work, t.id + ".rebuilt." + Library.ending(t.fileName)).delete()
            File(work, t.id + ".original." + Library.ending(t.fileName)).delete()
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
        val file = File(work, t.id + "." + Library.ending(t.fileName))
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
            Tagger.fix(file, Tagger.Details(title, singer, al.title, artist, al.year, trackNo, discNo, cover), (t.durationMs / 1000).toInt()) { from, to -> rebuildMp4(from, to) }
            val (size, modified) = Drive.uploadNewVersion(t.id, file, t.mime, Google.editToken() ?: token)
            Scanner.changeTracks(setOf(t.id)) {
                it.copy(
                    size = size, modified = modified.ifEmpty { it.modified }, title = title, artist = singer, trackNo = trackNo, discNo = discNo,
                    albumArtist = artist, fixed = true, fixedFully = true,
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
            File(work, t.id + ".rebuilt." + Library.ending(t.fileName)).delete()
            File(work, t.id + ".original." + Library.ending(t.fileName)).delete()
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
                try {
                    do {
                        awake.acquire(6 * 60 * 60_000L)
                        // Looking through the music: the notice follows how far it has got.
                        var shown = ""
                        while (Scanner.running) {
                            val now = Scanner.status.ifEmpty { "Looking through your music" }
                            if (now != shown) { shown = now; show(now) }
                            kotlinx.coroutines.delay(2000)
                        }
                        if (Offline.hasWork) Offline.run(show)
                        if (Fixer.hasWork) Fixer.run(this@WorkService, show)
                    } while (Scanner.running || Offline.hasWork || Fixer.hasWork)
                } finally {
                    if (awake.isHeld) awake.release()
                }
                started = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
