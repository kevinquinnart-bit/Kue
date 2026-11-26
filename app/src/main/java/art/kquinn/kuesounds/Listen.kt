package art.kquinn.kuesounds

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.io.IOException

/**
 * Name that song (Kevin, 26 November 2026: "hear songs played over the phone or picked up by the speaker"):
 *  - Around me: the phone's microphone hears music playing in the room, the car, a shop.
 *  - On this phone: what another app on the phone is playing, heard straight from the phone's sound (Android asks
 *    once each time). Some apps don't let others hear them; Android lets them refuse.
 * KueSounds listens for up to 15 seconds, and asks Shazam's song finder after 5, 9 and 15 seconds, stopping as soon as
 * it is named. The songs named are kept in a list, newest first.
 */
object Listen {
    enum class From { AROUND, PHONE }

    var listening by mutableStateOf(false)
        private set
    var from by mutableStateOf(From.AROUND)
        private set
    var heard by mutableIntStateOf(0)
        private set
    var asking by mutableStateOf(false)
        private set
    var found by mutableStateOf<SoundMatch.Found?>(null)
    var message by mutableStateOf("")
        private set
    var named by mutableStateOf<List<Named>>(emptyList())
        private set

    /** A song named, and when. */
    data class Named(val song: SoundMatch.Found, val at: Long, val from: String)

    private const val MOST = 15
    private val tries = intArrayOf(5, 9, MOST)
    @Volatile private var stopWanted = false
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        named = runCatching {
            Gson().fromJson(prefs().getString("named", "[]"), Array<Named>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    private fun prefs() = app.getSharedPreferences("listen", Context.MODE_PRIVATE)

    fun canHear(context: Context) = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun stop() { stopWanted = true }

    fun forget(n: Named) {
        named = named - n
        save()
    }

    private fun save() = prefs().edit().putString("named", Gson().toJson(named.take(50))).apply()

    /** Listens through the microphone. The microphone permission must already be given. */
    @SuppressLint("MissingPermission")
    fun aroundMe(context: Context) {
        if (listening) return
        init(context)
        if (!canHear(context)) { message = "KueSounds needs the microphone for this."; return }
        val size = maxOf(AudioRecord.getMinBufferSize(ShazamSig.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), ShazamSig.RATE)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, ShazamSig.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) { record?.release(); message = "The microphone couldn't be opened."; return }
        begin(From.AROUND)
        Thread({ run(record, From.AROUND) {} }, "listen").start()
    }

    /** Listening couldn't start. */
    internal fun fail(text: String) {
        message = text
        listening = false
    }

    /** Ready for listening: the screen shows it straight away, before the sound starts. */
    internal fun begin(f: From) {
        from = f
        listening = true
        stopWanted = false
        heard = 0
        found = null
        message = ""
    }

    /** Hears up to 15 seconds, asking Shazam as it goes. Runs on its own thread; [end] is called when it stops. */
    internal fun run(record: AudioRecord, f: From, end: () -> Unit) {
        val total = ShazamSig.RATE * MOST
        val sound = ShortArray(total)
        val chunk = ShortArray(ShazamSig.RATE / 10)
        var have = 0
        var loudest = 0
        var next = 0
        try {
            record.startRecording()
            while (!stopWanted && have < total) {
                val n = record.read(chunk, 0, minOf(chunk.size, total - have))
                if (n < 0) throw IOException(if (f == From.PHONE) "The phone stopped sharing its sound." else "The microphone stopped.")
                if (n == 0) continue
                System.arraycopy(chunk, 0, sound, have, n)
                for (i in 0 until n) { val a = kotlin.math.abs(chunk[i].toInt()); if (a > loudest) loudest = a }
                have += n
                heard = have / ShazamSig.RATE
                if (next < tries.size && have >= tries[next] * ShazamSig.RATE) {
                    next++
                    if (loudest < 300) continue   // nothing to go on yet
                    asking = true
                    val got = runCatching { Shazam.name(sound, have) }
                    asking = false
                    // Each try in the report, so a miss can be put right from facts (26 November 2026).
                    Scanner.note(
                        "name that song (" + (if (f == From.PHONE) "this phone" else "around me") + ", " + (have / ShazamSig.RATE) + " s, loudest " + loudest + "): " +
                            (got.exceptionOrNull()?.let { "failed " + it.javaClass.simpleName + " " + it.message } ?: got.getOrNull()?.let { it.artist + " / " + it.title } ?: "not known"),
                    )
                    got.exceptionOrNull()?.let { e ->
                        message = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) "No internet. Try again when the phone is online." else e.message ?: "Shazam didn't answer."
                        return
                    }
                    got.getOrNull()?.let { song ->
                        found = song
                        named = listOf(Named(song, System.currentTimeMillis(), if (f == From.PHONE) "On this phone" else "Around me")) + named
                        save()
                        return
                    }
                }
            }
            if (stopWanted) return
            message = when {
                loudest < 300 && f == From.PHONE -> "No sound came through. The app playing may not let other apps hear it."
                loudest < 300 -> "Nothing heard. Hold the phone nearer the music."
                else -> "Couldn't name it. Try again nearer the music, or when the singing starts."
            }
        } catch (e: Exception) {
            message = e.message ?: "Listening stopped."
        } finally {
            asking = false
            runCatching { record.stop() }
            record.release()
            listening = false
            end()
        }
    }
}

/**
 * Hears what the phone is playing (Android 10 and later). Android only lets an app hear the phone's sound from a
 * foreground service of this kind, started straight after the person says yes on Android's own question.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notice = notice()
        if (Build.VERSION.SDK_INT >= 29) startForeground(31, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(31, notice)
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        var projection: MediaProjection? = null
        val record = runCatching {
            if (Build.VERSION.SDK_INT < 29) throw IOException("This needs Android 10 or later.")
            if (!Listen.canHear(this)) throw IOException("KueSounds needs the microphone permission for this too.")
            val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data ?: throw IOException("Android didn't share the sound."))
                ?: throw IOException("Android didn't share the sound.")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { Listen.stop() } }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val size = maxOf(AudioRecord.getMinBufferSize(ShazamSig.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), ShazamSig.RATE)
            AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(ShazamSig.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(size * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        }
        val r = record.getOrNull()
        if (r == null) {
            Listen.fail(record.exceptionOrNull()?.message ?: "The phone's sound couldn't be heard.")
            projection?.stop()
            finish()
            return START_NOT_STICKY
        }
        Thread({ Listen.run(r, Listen.From.PHONE) { projection?.stop(); finish() } }, "listen-phone").start()
        return START_NOT_STICKY
    }

    private fun finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun notice(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("listen", "Naming a song", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "listen")
            .setSmallIcon(R.drawable.kue_status)
            .setContentTitle("KueSounds")
            .setContentText("Listening to name the song…")
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Starts hearing the phone's sound, with Android's yes ([code] and [data] from its question). */
        fun start(context: Context, code: Int, data: Intent) {
            Listen.init(context)
            Listen.begin(Listen.From.PHONE)
            val i = Intent(context, CaptureService::class.java).putExtra("code", code).putExtra("data", data)
            context.startForegroundService(i)
        }

        /** Android's question, "Start sharing?", set to the whole phone so its sound can be heard. */
        fun question(context: Context): Intent {
            val m = context.getSystemService(MediaProjectionManager::class.java)
            return if (Build.VERSION.SDK_INT >= 34) m.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
            else m.createScreenCaptureIntent()
        }
    }
}
