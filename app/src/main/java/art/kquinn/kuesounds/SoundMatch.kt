package art.kquinn.kuesounds

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

/**
 * Match by sound, like Shazam (Kevin, 25 November 2026): KueSounds listens to the first two minutes of a song's file,
 * makes its own fingerprint of the sound ([Fingerprint]), and asks AcoustID's free song database which recording it
 * is. AcoustID's answers are MusicBrainz recordings, the same catalogue KueSounds already uses. Free for an app that
 * isn't sold; it needs the app key from acoustid.org (Settings › KueSounds › Match by sound).
 */
object SoundMatch {
    /** What a song sounds like: its name, artist, the albums it is on, and how sure the match is (0 to 1). */
    data class Found(val title: String, val artist: String, val albums: List<String>, val single: String?, val score: Double, val recordingId: String)

    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("soundmatch", Context.MODE_PRIVATE)

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    /** The AcoustID app key, typed in Settings › KueSounds › Match by sound. */
    var key: String
        get() = if (::app.isInitialized) prefs().getString("key", "").orEmpty() else ""
        set(v) = prefs().edit().putString("key", v.trim()).apply()

    private var lastAsked = 0L

    /** Listens to a song and asks what it is. Call off the main thread. Throws with a plain reason when it can't. */
    fun identify(t: Track): List<Found> {
        if (key.isBlank()) throw IOException("Match by sound needs the AcoustID key, in Settings › KueSounds › Match by sound.")
        val (fp, seconds) = listen(t)
        if (fp.size < 20) throw IOException("There wasn't enough sound to go on.")
        val duration = (if (t.durationMs > 0) t.durationMs / 1000 else seconds.toLong()).coerceAtLeast(1)
        return ask(Fingerprint.encode(fp), duration.toInt())
    }

    /** The song's first two minutes, from the phone if it is saved there, otherwise straight from the Drive. */
    private fun listen(t: Track): Pair<IntArray, Int> {
        val ex = MediaExtractor()
        val local = Offline.fileFor(t)
        if (local != null) ex.setDataSource(local.path)
        else ex.setDataSource(Scanner.DriveSource(t.id, t.size, Google.tokenBlocking() ?: throw Drive.SignedOut()))
        try {
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("No sound found in the file.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcm = ShortArray(rate * channels * Fingerprint.SECONDS)
            var have = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0 || ex.sampleTime > Fingerprint.SECONDS * 1_000_000L) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (pcm.size < rate * channels * Fingerprint.SECONDS) pcm = pcm.copyOf(rate * channels * Fingerprint.SECONDS)
                    } else if (o >= 0) {
                        val out = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                        val n = minOf(out.remaining(), pcm.size - have)
                        out.get(pcm, have, n)
                        have += n
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || have >= pcm.size) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val mono = Fingerprint.toRate(pcm, have, rate, channels)
            return Fingerprint.of(mono) to (have / channels / maxOf(rate, 1))
        } finally {
            ex.release()
        }
    }

    /** Asks AcoustID, at most 3 times a second as it asks. */
    @Synchronized
    private fun ask(fingerprint: String, seconds: Int): List<Found> {
        val wait = 400 - (System.currentTimeMillis() - lastAsked)
        if (wait > 0) Thread.sleep(wait)
        lastAsked = System.currentTimeMillis()
        val body = FormBody.Builder()
            .add("client", key).add("duration", seconds.toString()).add("fingerprint", fingerprint)
            .add("meta", "recordings releasegroups compress").add("format", "json")
            .build()
        val request = Request.Builder().url("https://api.acoustid.org/v2/lookup").post(body).header("User-Agent", "KueSounds/" + BuildConfig.VERSION_NAME).build()
        val text = Drive.http.newCall(request).execute().use { r -> r.body.string() }
        return parse(text)
    }

    /** Reads AcoustID's answer: each recording found, best first, with its albums and single. */
    internal fun parse(text: String): List<Found> {
        val root = JsonParser.parseString(text).asJsonObject
        if (root.get("status")?.asString != "ok") {
            val said = root.getAsJsonObject("error")?.get("message")?.asString.orEmpty()
            throw IOException(if ("api key" in said.lowercase()) "AcoustID didn't accept the key. Check it in Settings › KueSounds › Match by sound." else "AcoustID said: $said")
        }
        val out = ArrayList<Found>()
        for (r in root.getAsJsonArray("results") ?: return emptyList()) {
            val res = r.asJsonObject
            val score = res.get("score")?.asDouble ?: 0.0
            for (rec in res.getAsJsonArray("recordings") ?: continue) {
                val o = rec.asJsonObject
                val title = o.get("title")?.asString ?: continue
                val artist = o.getAsJsonArray("artists")?.joinToString("") { a ->
                    val ao = a.asJsonObject
                    ao.get("name")?.asString.orEmpty() + (ao.get("joinphrase")?.asString.orEmpty())
                }.orEmpty()
                val groups = o.getAsJsonArray("releasegroups")?.map { it.asJsonObject }.orEmpty()
                fun plain(g: JsonObject) = g.getAsJsonArray("secondarytypes") == null || g.getAsJsonArray("secondarytypes").size() == 0
                val albums = groups.filter { it.get("type")?.asString == "Album" && plain(it) }.mapNotNull { it.get("title")?.asString }.distinct()
                val single = groups.firstOrNull { it.get("type")?.asString == "Single" }?.get("title")?.asString
                out += Found(title.replace('’', '\''), artist.replace('’', '\''), albums.map { it.replace('’', '\'') }, single?.replace('’', '\''), score, o.get("id")?.asString.orEmpty())
            }
        }
        // The most sure first; the same song named twice only once.
        return out.sortedByDescending { it.score }.distinctBy { Library.matchName(it.artist) + "\u0001" + Library.matchName(it.title) }
    }
}
