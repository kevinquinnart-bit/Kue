package art.kquinn.kuesounds

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player itself. It runs on its own, so music carries on with the screen off or another app open,
 * shows on the lock screen and in the pull-down, answers headphone and car buttons, and pauses when
 * headphones are pulled out. Each song plays straight from the Drive with the current sign-in.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retried = ""

    override fun onCreate() {
        super.onCreate()
        Google.init(this)
        Scanner.init(this)
        Offline.init(this)
        Podcasts.init(this)
        PlayPrefs.init(this)
        val upstream = OkHttpDataSource.Factory(Drive.http)
        // Every request for a song carries the current Drive pass, fetched fresh when the old one has run out.
        // Only requests to Google's Drive: a podcast comes from its own website, which must never see the pass.
        val withPass = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.host?.endsWith("googleapis.com") != true) return@Factory spec
            val token = Google.tokenBlocking().orEmpty()
            spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
        }
        // The sound goes through Sing along on its way out, so the singer can be turned down (Kevin, 25 November 2026).
        val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(SingAlong, Leveller)).build()
        val renderers = androidx.media3.exoplayer.RenderersFactory { handler, _, audioListener, _, metadataOutput ->
            arrayOf(
                androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(this, androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT, handler, audioListener, sink),
                // Radio stations' "now playing" titles.
                androidx.media3.exoplayer.metadata.MetadataRenderer(metadataOutput, handler.looper),
            )
        }
        val player = ExoPlayer.Builder(this, renderers)
            // Songs saved on the phone are read from there; the rest come from the Drive with the pass.
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, keptAsPlayed(withPass))))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                val id = player.currentMediaItem?.mediaId.orEmpty()
                // The pass ran out: drop it and try the same song once more with a fresh one.
                if (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 401 && retried != id) {
                    retried = id
                    val at = player.currentPosition
                    scope.launch {
                        withContext(Dispatchers.IO) { Google.forget() }
                        player.seekTo(at)
                        player.prepare()
                        player.play()
                    }
                    return
                }
                // A song that won't play is skipped for the next one.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        listenToPodcasts(player)
        listenForSound(player)
        listenForPlaces(player)
        listenForHeadphones(player)
        // Settings › KueSounds › Playing: pausing for other apps, even loudness (Kevin, 25 November 2026).
        val prefsApply = {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), PlayPrefs.audioFocus)
            Leveller.on = PlayPrefs.evenLoudness && Sound.kindOf(player.currentMediaItem?.mediaId.orEmpty()) == Sound.Kind.MUSIC
        }
        prefsApply()
        PlayPrefs.onChange = { prefsApply() }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { prefsApply() }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    private var effects: Sound.Effects? = null

    /**
     * The equaliser and the rest (Settings › KueSounds › Sound): put on the player's sound, with the music profile for
     * songs and the podcast one for episodes and radio, changed over as the player moves between them.
     */
    private fun listenForSound(player: ExoPlayer) {
        Sound.init(this)
        val apply = {
            val kind = Sound.kindOf(player.currentMediaItem?.mediaId.orEmpty())
            val p = Sound.profile(kind)
            effects?.apply(p)
            player.skipSilenceEnabled = kind == Sound.Kind.PODCASTS && p.skipSilence
        }
        val make = { session: Int ->
            effects?.release()
            effects = if (session == C.AUDIO_SESSION_ID_UNSET) null else Sound.Effects(session).also { Sound.missing = it.missing }
            apply()
        }
        make(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = make(audioSessionId)
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = apply()
        })
        Sound.onChange = { apply() }
    }

    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    /**
     * Songs played lately kept on the phone (Settings › KueSounds › Playing, 500 MB unless changed), so playing one
     * again needs no downloading. Live radio isn't kept: it never ends. The size takes effect when KueSounds next starts.
     */
    private fun keptAsPlayed(upstream: androidx.media3.datasource.DataSource.Factory): androidx.media3.datasource.DataSource.Factory {
        val made = runCatching {
            androidx.media3.datasource.cache.SimpleCache(
                java.io.File(cacheDir, "played"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(PlayPrefs.cacheMb.coerceAtLeast(50) * 1_000_000L),
                androidx.media3.database.StandaloneDatabaseProvider(this),
            )
        }.getOrNull() ?: return upstream
        cache = made
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(made)
            .setUpstreamDataSourceFactory(upstream)
            // The Drive's address for a song carries no pass, so it is the same every time: kept by it.
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS)
    }

    /**
     * Long songs carry on where they were left (Settings › KueSounds › Playing; CloudPlayer's "bookmarking"): a song of
     * 10 minutes or more has its place kept every 10 seconds and on pause, starts there next time, and loses it once
     * played to the end.
     */
    private fun listenForPlaces(player: ExoPlayer) {
        fun long(): Boolean {
            val id = player.currentMediaItem?.mediaId.orEmpty()
            return PlayPrefs.keepPlace && id.isNotEmpty() && !id.startsWith("pod:") && !id.startsWith("radio:") &&
                player.duration >= PlayPrefs.keepPlaceMinutes * 60_000L
        }
        val keep = object : Runnable {
            override fun run() {
                if (player.isPlaying && long()) PlayPrefs.keepPlaceOf(player.currentMediaItem!!.mediaId, player.currentPosition)
                tick.postDelayed(this, 10_000)
            }
        }
        tick.postDelayed(keep, 10_000)
        var current = ""
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && long() && player.playbackState != Player.STATE_ENDED) PlayPrefs.keepPlaceOf(player.currentMediaItem!!.mediaId, player.currentPosition)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // The song before played to its end: its place goes.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && current.isNotEmpty()) PlayPrefs.keepPlaceOf(current, 0)
                current = mediaItem?.mediaId.orEmpty()
                if (!PlayPrefs.keepPlace || current.startsWith("pod:") || current.startsWith("radio:")) return
                val at = PlayPrefs.place(current)
                if (at > 5_000) player.seekTo(at)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && current.isNotEmpty()) PlayPrefs.keepPlaceOf(current, 0)
            }
        })
    }

    private var devices: android.media.AudioDeviceCallback? = null

    /**
     * Plays when headphones are plugged in, or Bluetooth headphones, a speaker or a car connect (Settings › KueSounds ›
     * Playing; each off until turned on). Works while KueSounds' player is running: after anything has been played.
     */
    private fun listenForHeadphones(player: ExoPlayer) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val wired = setOf(
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val bluetooth = setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET, android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER)
        var first = true
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) {
                // The first call lists what is already connected: not a new arrival.
                if (first) { first = false; return }
                val outs = added.filter { it.isSink }
                val go = (PlayPrefs.headsetPlay && outs.any { it.type in wired }) || (PlayPrefs.bluetoothPlay && outs.any { it.type in bluetooth })
                if (go && player.mediaItemCount > 0 && !player.isPlaying) {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
        }
        devices = callback
        audio.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticks = 0

    /**
     * Podcasts: where each episode has got to is kept every 10 seconds (and in the Drive on pause and every two
     * minutes), an episode played to the end is marked played, and moving on to an episode starts it where it was left.
     */
    private fun listenToPodcasts(player: ExoPlayer) {
        var current = ""
        val keep = object : Runnable {
            override fun run() {
                val id = player.currentMediaItem?.mediaId.orEmpty()
                if (id.startsWith("pod:") && player.isPlaying) {
                    ticks++
                    Podcasts.keepPosition(id.removePrefix("pod:"), player.currentPosition, ticks % 12 == 0)
                }
                tick.postDelayed(this, 10_000)
            }
        }
        tick.postDelayed(keep, 10_000)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val id = player.currentMediaItem?.mediaId.orEmpty()
                if (!isPlaying && id.startsWith("pod:") && player.playbackState != Player.STATE_ENDED) {
                    Podcasts.keepPosition(id.removePrefix("pod:"), player.currentPosition, true)
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // The episode before played to its end.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && current.startsWith("pod:")) Podcasts.setPlayed(current.removePrefix("pod:"), true)
                current = mediaItem?.mediaId.orEmpty()
                // Moved on to another episode: it starts where it was left (the first one is started there already).
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && current.startsWith("pod:")) {
                    val id = current.removePrefix("pod:")
                    val at = Podcasts.position(id)
                    if (at > 5_000 && !Podcasts.isPlayed(id)) player.seekTo(at)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && current.startsWith("pod:")) Podcasts.setPlayed(current.removePrefix("pod:"), true)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping KueSounds away stops it only when nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        tick.removeCallbacksAndMessages(null)
        Sound.onChange = null
        PlayPrefs.onChange = null
        devices?.let { d -> runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(d) } }
        runCatching { cache?.release() }
        cache = null
        effects?.release()
        effects = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
