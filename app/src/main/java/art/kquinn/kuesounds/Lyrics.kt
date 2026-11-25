package art.kquinn.kuesounds

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer

/**
 * Lyrics that follow the song, line by line, for singing along (Kevin, 25 November 2026: "enable lyrics so it could
 * play as a karaoke version"). From LRCLIB, a free, open library of song words with their timings: no account, no key
 * and nothing to pay. Kept on the phone once fetched.
 */
object Lyrics {
    /** One line: when it is sung (milliseconds into the song) and its words. */
    data class Line(val at: Long, val words: String)

    /** A song's words: [timed] when LRCLIB has them line by line, else [plain]; [none] when it has neither. */
    data class Words(val timed: List<Line> = emptyList(), val plain: String = "", val instrumental: Boolean = false) {
        val none: Boolean get() = timed.isEmpty() && plain.isBlank() && !instrumental
    }

    private const val WHO = "KueSounds/1.0 (https://github.com/kevinquinnart-bit/Kue)"
    private lateinit var folder: File

    fun init(context: Context) {
        folder = File(context.filesDir, "lyrics").apply { mkdirs() }
    }

    /** Reads "[01:23.45] words" lines: the time each is sung and its words, in order. Lines with no time are left out. */
    fun parse(lrc: String): List<Line> {
        val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        val out = ArrayList<Line>()
        for (raw in lrc.lines()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val words = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val part = m.groupValues[3]
                val ms = when (part.length) { 0 -> 0L; 1 -> part.toLong() * 100; 2 -> part.toLong() * 10; else -> part.take(3).toLong() }
                out += Line(min * 60_000 + sec * 1000 + ms, words)
            }
        }
        return out.sortedBy { it.at }
    }

    /** The line being sung at [ms] into the song: its place in [lines], or -1 before the first. */
    fun lineAt(lines: List<Line>, ms: Long): Int {
        var found = -1
        for ((i, l) in lines.withIndex()) if (l.at <= ms) found = i else break
        return found
    }

    /** One answer from LRCLIB read into words. */
    fun read(json: String): Words {
        val o = JsonParser.parseString(json).let { if (it.isJsonArray) it.asJsonArray.firstOrNull()?.asJsonObject else it.asJsonObject } ?: return Words()
        val timed = o.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return Words(
            timed = parse(timed),
            plain = o.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            instrumental = o.get("instrumental")?.takeIf { !it.isJsonNull }?.asBoolean == true,
        )
    }

    /**
     * A song's words: kept ones, or asked of LRCLIB by its artist, name, album and length; failing that, searched for by
     * artist and name, the first with timings kept. Call off the main thread.
     */
    fun get(id: String, artist: String, title: String, album: String, seconds: Long): Words {
        val kept = if (::folder.isInitialized) File(folder, id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80) + ".json") else null
        if (kept != null && kept.exists()) runCatching { return read(kept.readText()) }
        val name = Library.withoutBrackets(title)
        val exact = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("artist_name", artist).addQueryParameter("track_name", name)
            .apply { if (album.isNotBlank()) addQueryParameter("album_name", album) }
            .apply { if (seconds > 0) addQueryParameter("duration", seconds.toString()) }
            .build()
        var json = ask(exact)
        if (json == null || read(json).none) {
            val search = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("artist_name", artist).addQueryParameter("track_name", name).build()
            json = ask(search)?.let { list ->
                // The first with timings, else the first with words.
                val all = JsonParser.parseString(list).takeIf { it.isJsonArray }?.asJsonArray ?: return@let null
                (all.firstOrNull { it.asJsonObject.get("syncedLyrics")?.isJsonNull == false } ?: all.firstOrNull())?.toString()
            }
        }
        val words = json?.let { read(it) } ?: Words()
        if (json != null && kept != null) runCatching { kept.writeText(json) }
        return words
    }

    private fun ask(url: okhttp3.HttpUrl): String? {
        val request = Request.Builder().url(url).header("User-Agent", WHO).build()
        return Drive.http.newCall(request).execute().use { r ->
            when {
                r.isSuccessful -> r.body.string()
                r.code == 404 -> null
                else -> throw java.io.IOException("the lyrics library answered " + r.code)
            }
        }
    }
}

/**
 * Sing along: the singer turned down (Kevin, 25 November 2026: "a karaoke version of the song"). A singer is nearly
 * always in the middle of a stereo song, the same in both speakers, so taking one side from the other leaves the
 * band and takes away the voice. Some of the bass and drums go with it, which is the usual cost of doing this without
 * a separate backing track; songs recorded in mono can't be changed this way.
 */
object SingAlong : BaseAudioProcessor() {
    /** Turned on and off from the lyrics screen; takes effect straight away. */
    @Volatile var on = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only plain 16-bit stereo; anything else goes through untouched.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!on) {
            out.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 4) {
                val left = inputBuffer.getShort().toInt()
                val right = inputBuffer.getShort().toInt()
                val band = ((left - right) / 2).toShort()
                out.putShort(band)
                out.putShort(band)
            }
            // A stray half sample at the end is passed on as it is.
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        }
        out.flip()
    }
}
