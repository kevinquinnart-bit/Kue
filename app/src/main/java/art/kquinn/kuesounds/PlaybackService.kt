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
        val upstream = OkHttpDataSource.Factory(Drive.http)
        // Every request for a song carries the current Drive pass, fetched fresh when the old one has run out.
        // Only requests to Google's Drive: a podcast comes from its own website, which must never see the pass.
        val withPass = ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.host?.endsWith("googleapis.com") != true) return@Factory spec
            val token = Google.tokenBlocking().orEmpty()
            spec.withAdditionalHeaders(mapOf("Authorization" to "Bearer $token"))
        }
        val player = ExoPlayer.Builder(this)
            // Songs saved on the phone are read from there; the rest come from the Drive with the pass.
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, withPass)))
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
