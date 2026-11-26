package art.kquinn.kuesounds

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Songs saved on the phone, to play with no internet. Saving downloads each song file whole into
 * KueSounds' own space on the phone; the player then plays that copy instead of the Drive's. Saving
 * carries on with the screens closed. Each device keeps its own choice of saved songs.
 */
object Offline {
    private lateinit var folder: File

    /** The songs saved on this phone, by song id. */
    var saved by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Songs waiting to be saved. */
    private val waiting = LinkedHashSet<String>()

    var status by mutableStateOf("")
        private set

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
        folder = File(context.filesDir, "offline").apply { mkdirs() }
        folder.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        saved = folder.listFiles()?.map { it.nameWithoutExtension }?.toSet().orEmpty()
        loadRules()
    }

    private fun fileName(t: Track) = t.id + "." + Library.ending(Library.realName(t)).ifEmpty { "audio" }

    /** The saved copy of a song, if it is on the phone. */
    fun fileFor(t: Track): File? = if (t.id in saved) File(folder, fileName(t)).takeIf { it.exists() } ?: File(folder, t.id + ".m4a").takeIf { it.exists() } else null

    fun isSaved(al: Album): Boolean = al.tracks.all { it.id in saved }

    fun isWaiting(al: Album): Boolean = synchronized(waiting) { al.tracks.any { it.id in waiting } }

    /** How much room the saved songs take, in bytes. */
    fun bytesUsed(): Long = folder.listFiles()?.sumOf { it.length() } ?: 0L

    /** Saves these songs on the phone. */
    fun save(context: Context, tracks: List<Track>) {
        synchronized(waiting) { tracks.filter { it.id !in saved }.forEach { waiting += it.id } }
        WorkService.start(context)
    }

    /**
     * What to keep on the phone by itself (Kevin, 26 November 2026: choose what to keep offline, with one list holding
     * all the music): every song, the favourites, and playlists chosen one by one. New songs that fall under a choice
     * are saved the next time KueSounds looks; nothing is taken off by this.
     */
    private fun rules() = ctx.getSharedPreferences("offline", Context.MODE_PRIVATE)
    var keepAll by mutableStateOf(false)
        private set
    var keepFavourites by mutableStateOf(false)
        private set
    var keepLists by mutableStateOf<Set<String>>(emptySet())
        private set

    fun loadRules() {
        val r = rules()
        keepAll = r.getBoolean("all", false)
        keepFavourites = r.getBoolean("favourites", false)
        keepLists = r.getStringSet("lists", emptySet()).orEmpty().toSet()
    }

    fun setKeepAll(context: Context, on: Boolean) { keepAll = on; rules().edit().putBoolean("all", on).apply(); keepUp(context) }
    fun setKeepFavourites(context: Context, on: Boolean) { keepFavourites = on; rules().edit().putBoolean("favourites", on).apply(); keepUp(context) }
    fun setKeepList(context: Context, id: String, on: Boolean) {
        keepLists = if (on) keepLists + id else keepLists - id
        rules().edit().putStringSet("lists", keepLists).apply()
        keepUp(context)
    }

    /** The songs the choices above want on the phone. */
    fun wanted(): List<Track> {
        val all = Scanner.data.tracks
        if (keepAll) return all
        val ids = HashSet<String>()
        if (keepFavourites) ids += Sync.favouriteIds()
        for (id in keepLists) Playlists.get(id)?.ids?.let { ids += it }
        return all.filter { it.id in ids }
    }

    /** A favourite or a playlist changed: keep the phone up to date if that one is kept. */
    fun changed(key: String) {
        if (!::ctx.isInitialized) return
        val counts = keepAll || (key.startsWith("fav:") && keepFavourites) || (key.startsWith("pl:") && key.removePrefix("pl:") in keepLists)
        if (counts) keepUp(ctx)
    }

    /** Saves whatever the choices want that isn't on the phone yet. */
    fun keepUp(context: Context) {
        val missing = wanted().filter { it.id !in saved }
        if (missing.isNotEmpty()) {
            Scanner.note("keeping on the phone: " + missing.size + " songs to save")
            save(context, missing)
        }
    }

    /** Takes these songs off the phone. They still play from the Drive. */
    fun remove(tracks: List<Track>) {
        synchronized(waiting) { tracks.forEach { waiting -= it.id } }
        for (t in tracks) { File(folder, fileName(t)).delete(); File(folder, t.id + ".m4a").delete() }
        saved = saved - tracks.map { it.id }.toSet()
    }

    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() }

    private fun next(): String? = synchronized(waiting) { waiting.firstOrNull()?.also { waiting.remove(it) } }

    /** Saves the waiting songs one by one. Called by [WorkService]. */
    suspend fun run(progress: (String) -> Unit) {
        var done = 0
        try {
            while (true) {
                val id = next() ?: break
                val t = Scanner.data.tracks.firstOrNull { it.id == id } ?: continue
                val left = synchronized(waiting) { waiting.size }
                status = "Saving to phone · " + t.title + if (left > 0) " · $left more" else ""
                progress(status)
                // The room set aside for saved songs (Settings): full means saving stops, saying so.
                val limit = PlayPrefs.saveLimitMb * 1_000_000L
                if (limit > 0 && bytesUsed() + t.size / (if (PlayPrefs.saveSize == 0) 1 else 3) > limit) {
                    synchronized(waiting) { waiting.clear() }
                    Scanner.note("saving to phone stopped: the room set aside (" + PlayPrefs.saveLimitMb + " MB) is full")
                    status = "The room set aside for saved songs is full. Change it in Settings › Privacy and storage."
                    progress(status)
                    kotlinx.coroutines.delay(4_000)
                    break
                }
                val small = PlayPrefs.saveSize > 0
                val target = if (small) File(folder, t.id + ".m4a") else File(folder, fileName(t))
                val part = File(folder, fileName(t) + ".part")
                try {
                    val token = Google.token() ?: throw Drive.SignedOut()
                    Drive.downloadWhole(t.id, part, token)
                    if (t.size > 0 && part.length() != t.size) throw java.io.IOException("the download was cut short")
                    target.delete()
                    if (small) {
                        // A smaller copy made on the phone; the download goes once it is made.
                        Shrink.toAac(part, target, if (PlayPrefs.saveSize == 1) 96_000 else 48_000)
                        part.delete()
                    } else if (!part.renameTo(target)) throw java.io.IOException("the saved song couldn't be kept")
                    saved = saved + t.id
                    done++
                } catch (e: Exception) {
                    part.delete()
                    target.delete()
                    Scanner.note("not saved to phone: " + t.fileName + ": " + e.javaClass.simpleName + " " + e.message)
                    if (e is Drive.SignedOut) synchronized(waiting) { waiting.clear() }
                }
            }
        } finally {
            status = ""
            if (done > 0) Scanner.note("saved $done songs to the phone")
        }
    }
}

/** Whether the phone can reach the internet right now. */
object Net {
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** On Wi-Fi (or another connection that isn't paid for by the amount used). */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * Makes a smaller copy of a song for the phone (Kevin, 26 November 2026): the sound re-made as AAC at a low rate, on the
 * phone, with Android's own encoder. A 4-minute song of 9 MB becomes about 3 MB small, or 1.5 MB smallest.
 */
object Shrink {
    /** Turns [from] into an AAC file [to] at [bitrate] bits a second. Waits until done; throws when it can't. */
    suspend fun toAac(from: File, to: File, bitrate: Int) {
        val thread = android.os.HandlerThread("shrink").apply { start() }
        try {
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            android.os.Handler(thread.looper).post {
                val transformer = androidx.media3.transformer.Transformer.Builder(appContext())
                    .setLooper(thread.looper)
                    .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        androidx.media3.transformer.DefaultEncoderFactory.Builder(appContext())
                            .setRequestedAudioEncoderSettings(androidx.media3.transformer.AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                            .build(),
                    )
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException,
                        ) {
                            done.completeExceptionally(exportException)
                        }
                    })
                    .build()
                val item = androidx.media3.transformer.EditedMediaItem.Builder(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(from)))
                    .setRemoveVideo(true).build()
                runCatching { transformer.start(item, to.path) }.onFailure { done.completeExceptionally(it) }
            }
            done.await()
            if (!to.exists() || to.length() == 0L) throw java.io.IOException("the smaller copy came out empty")
        } finally {
            thread.quitSafely()
        }
    }

    private fun appContext(): Context = Scanner.appContext
}
