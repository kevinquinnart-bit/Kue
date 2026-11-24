package art.kquinn.kuesounds

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

/**
 * The screens' side of the player: what is playing, and the buttons. The music itself plays in
 * [PlaybackService], which carries on when the screens close.
 */
object Playing {
    private var controller: MediaController? = null

    var currentId by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var album by mutableStateOf("")
        private set
    var artwork by mutableStateOf<Uri?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var problem by mutableStateOf("")
        private set
    var speed by mutableStateOf(1f)
        private set
    /** The sleep timer: when it stops the playing (0 for off), or at the end of this episode. */
    var sleepAt by mutableStateOf(0L)
        private set
    var sleepAtEnd by mutableStateOf(false)
        private set
    private val clock = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepCheck = object : Runnable {
        override fun run() {
            val c = controller
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                c?.pause()
                sleepAt = 0
                return
            }
            if (sleepAtEnd && c != null && c.duration > 0 && c.duration - c.currentPosition < 1_500) {
                c.pause()
                sleepAtEnd = false
                return
            }
            if (sleepAt > 0 || sleepAtEnd) clock.postDelayed(this, 1_000)
        }
    }

    /** A podcast episode is playing (rather than a song). */
    val isPodcast: Boolean get() = currentId.startsWith("pod:")

    /** A radio station is playing. */
    val isRadio: Boolean get() = currentId.startsWith("radio:")

    /** Plays a radio station, live. */
    fun playRadio(s: Radio.Station) {
        val c = controller ?: return
        if (!Net.online(appContext)) { problem = "No internet, so the radio can't play."; return }
        if (SpotifyLink.connected && !SpotifyLink.paused) SpotifyLink.toggle()
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        c.setPlaybackSpeed(1f)
        c.setMediaItem(
            MediaItem.Builder().setMediaId("radio:" + s.id).setUri(s.url)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(s.name).setArtist(s.tags.ifBlank { "Radio" }).setStation(s.name)
                        .setArtworkUri(s.logo.takeIf { it.startsWith("http") }?.let { Uri.parse(it) }).setIsPlayable(true).setIsBrowsable(false).build(),
                ).build(),
        )
        c.prepare()
        c.play()
        Radio.counted(s)
    }

    /** Stops whatever KueSounds is playing (YouTube's player is about to start). */
    fun pause() { controller?.pause() }

    /** Whether anything is loaded to play. */
    val active: Boolean get() = currentId.isNotEmpty()

    private lateinit var appContext: Context

    fun connect(context: Context) {
        appContext = context.applicationContext
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(player)
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    problem = "That song won't play."
                }
            })
            refresh(c)
        }, MoreExecutors.directExecutor())
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        currentId = item?.mediaId.orEmpty()
        val m = p.mediaMetadata
        title = m.title?.toString().orEmpty()
        artist = m.artist?.toString().orEmpty()
        album = m.albumTitle?.toString().orEmpty()
        artwork = m.artworkUri
        isPlaying = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        durationMs = p.duration.takeIf { it > 0 } ?: 0L
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        speed = p.playbackParameters.speed
        if (p.isPlaying) problem = ""
    }

    /** Where the song has got to, read when the screen asks. */
    fun positionMs(): Long = controller?.currentPosition ?: 0L

    fun item(t: Track): MediaItem {
        val art = Scanner.artForTrack(t)
        // A song saved on the phone plays from there; the rest from the Drive.
        val local = Offline.fileFor(t)
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(local?.let { Uri.fromFile(it).toString() } ?: Drive.mediaUrl(t.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setAlbumArtist(t.albumArtist.ifBlank { null })
                    .setTrackNumber(t.trackNo.takeIf { it > 0 })
                    .setArtworkUri(art?.let { Uri.fromFile(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /** Plays [list] from the song at [start], in order, or shuffled. */
    fun play(list: List<Track>, start: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        // With no internet, only the songs saved on the phone can play: the rest are left out.
        if (!Net.online(appContext)) {
            val chosen = list.getOrNull(start)
            val onPhone = list.filter { it.id in Offline.saved }
            if (onPhone.isEmpty()) {
                problem = "No internet, and these songs aren't saved on the phone."
                return
            }
            return play(onPhone, chosen?.let { onPhone.indexOf(it) }?.takeIf { it >= 0 } ?: 0, shuffled)
        }
        c.shuffleModeEnabled = shuffled
        // Music always plays at its own speed: a podcast's faster speed stays with podcasts.
        c.setPlaybackSpeed(1f)
        val first = if (shuffled) list.indices.random() else start.coerceIn(0, list.size - 1)
        c.setMediaItems(list.map { item(it) }, first, 0L)
        c.prepare()
        c.play()
    }

    /** Whether what is playing now is music (not radio or a podcast), so songs can be put with it. */
    private fun playingMusic(c: androidx.media3.session.MediaController): Boolean {
        if (c.mediaItemCount == 0) return false
        val id = c.currentMediaItem?.mediaId ?: return false
        return Scanner.data.tracks.any { it.id == id }
    }

    /** Puts [list] straight after the song playing now; with no music playing, plays them. (Hold menus, 24 Nov 2026.) */
    fun playNext(list: List<Track>) {
        val c = controller ?: return
        if (list.isEmpty()) return
        if (!playingMusic(c)) return play(list, 0)
        c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), list.map { item(it) })
    }

    /** Puts [list] at the end of what is playing; with no music playing, plays them. */
    fun addToQueue(list: List<Track>) {
        val c = controller ?: return
        if (list.isEmpty()) return
        if (!playingMusic(c)) return play(list, 0)
        c.addMediaItems(list.map { item(it) })
    }

    /** A podcast episode to play: from the phone when saved there, otherwise from the show's own website. */
    fun episodeItem(e: Podcasts.Episode): MediaItem {
        val show = Podcasts.showOf(e)
        val local = Podcasts.fileFor(e)
        val art = e.art.ifEmpty { show?.art.orEmpty() }
        return MediaItem.Builder()
            .setMediaId("pod:" + e.id)
            .setUri(local?.let { Uri.fromFile(it).toString() } ?: e.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(show?.title.orEmpty())
                    .setAlbumTitle(show?.title.orEmpty())
                    .setArtworkUri(art.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /** Plays [list] from [start], each episode from where it was left, at the podcast speed. */
    fun playEpisodes(list: List<Podcasts.Episode>, start: Int) {
        val c = controller ?: return
        if (list.isEmpty()) return
        val chosen = list[start.coerceIn(0, list.size - 1)]
        if (!Net.online(appContext) && Podcasts.fileFor(chosen) == null) {
            problem = "No internet, and this episode isn't saved on the phone."
            return
        }
        c.shuffleModeEnabled = false
        c.repeatMode = Player.REPEAT_MODE_OFF
        val at = if (Podcasts.isPlayed(chosen.id)) 0L else Podcasts.position(chosen.id)
        if (Podcasts.isPlayed(chosen.id)) Podcasts.setPlayed(chosen.id, false)
        c.setMediaItems(list.map { episodeItem(it) }, start.coerceIn(0, list.size - 1), at)
        c.setPlaybackSpeed(Podcasts.speed())
        c.prepare()
        c.play()
    }

    /** Forward (or back, below 0) by [ms]. */
    fun skip(ms: Long) {
        val c = controller ?: return
        val to = (c.currentPosition + ms).coerceAtLeast(0L)
        c.seekTo(if (c.duration > 0) to.coerceAtMost(c.duration - 1_000) else to)
    }

    /** The next podcast speed along: 0.8, 1, 1.2, 1.5, 1.75, 2, 2.5, 3, then round again. */
    fun nextSpeed() {
        val c = controller ?: return
        val speeds = Podcasts.SPEEDS.split(',').map { it.toFloat() }
        val at = speeds.indexOfFirst { kotlin.math.abs(it - c.playbackParameters.speed) < 0.01f }
        val next = speeds[(at + 1) % speeds.size]
        c.setPlaybackSpeed(next)
        Podcasts.speed(next)
    }

    /** The sleep timer, along: off, 15, 30, 45, 60 minutes, the end of this episode, then off again. */
    fun nextSleep() {
        val now = System.currentTimeMillis()
        val minutesLeft = if (sleepAt > 0) ((sleepAt - now) / 60_000 + 1).toInt() else 0
        when {
            sleepAtEnd -> { sleepAtEnd = false; sleepAt = 0 }
            sleepAt == 0L -> sleepAt = now + 15 * 60_000L
            minutesLeft <= 15 -> sleepAt = now + 30 * 60_000L
            minutesLeft <= 30 -> sleepAt = now + 45 * 60_000L
            minutesLeft <= 45 -> sleepAt = now + 60 * 60_000L
            else -> { sleepAt = 0; sleepAtEnd = true }
        }
        clock.removeCallbacks(sleepCheck)
        if (sleepAt > 0 || sleepAtEnd) clock.post(sleepCheck)
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    /** Back to the start of the song, or to the song before when it has only just begun. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    fun flipShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off, then the whole list again and again, then this one song again and again. */
    fun nextRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
}
