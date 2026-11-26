package art.kquinn.kuesounds

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln

/**
 * How KueSounds sounds (Kevin, 25 November 2026): a graphic equaliser with ready-made settings and one of your own,
 * deep bass, surround and louder, kept as two profiles: one for music, one for podcasts (speech wants different
 * settings). The player switches profile by itself as it moves between a song and an episode.
 */
object Sound {
    enum class Kind(val label: String) { MUSIC("Music"), PODCASTS("Podcasts and radio") }

    /**
     * The ten bands of the graphic equaliser, in hertz (Kevin, 25 November 2026: CloudPlayer's 10-band equaliser as the
     * least). Set exactly on phones with Android 9 or later; on older ones the phone's own bands follow them.
     */
    val bands = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    val bandNames = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    /** The five points the ready-made settings were first written for; spread over the ten bands by [spread]. */
    private val fivePoints = intArrayOf(60, 230, 910, 3_600, 14_000)

    /** A five-point setting as ten bands, each band taking the level of the curve at its own pitch. */
    fun spread(five: List<Float>): List<Float> = bands.map { hz -> curve(hz.toDouble(), fivePoints, five) }

    /** The most each band goes up or down, in decibels. */
    const val RANGE = 12f

    class Preset(val name: String, val db: FloatArray)

    val presets = listOf(
        Preset("Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
        Preset("Bass boost", floatArrayOf(6f, 4f, 0f, 0f, 0f)),
        Preset("Treble boost", floatArrayOf(0f, 0f, 0f, 3f, 6f)),
        Preset("Clear voices", floatArrayOf(-4f, -2f, 3f, 5f, 3f)),
        Preset("Vocal", floatArrayOf(-2f, -1f, 2f, 4f, 2f)),
        Preset("Rock", floatArrayOf(4f, 2f, -1f, 2f, 4f)),
        Preset("Pop", floatArrayOf(-1f, 2f, 4f, 2f, -1f)),
        Preset("Dance", floatArrayOf(5f, 3f, 0f, 2f, 3f)),
        Preset("Jazz", floatArrayOf(3f, 1f, -1f, 1f, 3f)),
        Preset("Classical", floatArrayOf(4f, 2f, -1f, 2f, 3f)),
        Preset("Acoustic", floatArrayOf(3f, 2f, 1f, 2f, 2f)),
        Preset("Country and folk", floatArrayOf(2f, 1f, 2f, 3f, 2f)),
        Preset("Late night, softer", floatArrayOf(-2f, 0f, 2f, 1f, -2f)),
        // Added 26 November 2026 for following the kind of music: warm low end, smooth voice.
        Preset("Soul and R&B", floatArrayOf(4f, 3f, 1f, 2f, 1f)),
        Preset("Your own", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    )
    val OWN = presets.size - 1

    private fun named(name: String) = presets.indexOfFirst { it.name == name }

    /**
     * The ready-made setting for a kind of music (Kevin, 26 November 2026: "a soul or classical file can have the
     * correct sound profile switch for playback as the song comes on"). Null when the kind isn't one of these.
     */
    fun presetFor(kind: String): Int? {
        val g = kind.lowercase()
        fun has(vararg w: String) = w.any { it in g }
        val name = when {
            has("classical", "opera", "baroque", "orchestra", "symphon", "chamber", "choral", "concerto") -> "Classical"
            has("soul", "r&b", "rnb", "rhythm and blues", "funk", "gospel", "motown") -> "Soul and R&B"
            has("jazz", "blues", "swing", "big band") -> "Jazz"
            has("hip hop", "hip-hop", "rap", "reggae", "dub", "grime", "trap", "dancehall") -> "Bass boost"
            has("dance", "house", "techno", "trance", "edm", "electronic", "disco", "garage", "drum and bass", "electro") -> "Dance"
            has("rock", "metal", "punk", "grunge", "indie", "alternative") -> "Rock"
            has("folk", "country", "irish", "celtic", "bluegrass", "americana", "traditional") -> "Country and folk"
            has("acoustic", "singer-songwriter", "singer/songwriter") -> "Acoustic"
            has("spoken", "comedy", "audiobook") -> "Clear voices"
            has("pop") -> "Pop"
            else -> return null
        }
        return named(name).takeIf { it >= 0 }
    }

    /** What the music profile plays for a song of [kind]: its kind's setting when following, else the one chosen. */
    fun forKind(p: Profile, kind: String): Profile {
        if (!p.followKind) return p
        val preset = presetFor(kind) ?: return p
        return p.copy(eqOn = true, preset = preset)
    }

    /** The setting the playing song took by its kind, for the screen ("Soul and R&B, for Soul"); "" when none. */
    var following by mutableStateOf("")

    /** One profile: whether the equaliser is on, which setting, your own bands, and the three extras (0 to 100). */
    data class Profile(
        val eqOn: Boolean = false,
        val preset: Int = 0,
        val own: List<Float> = List(10) { 0f },
        val bass: Int = 0,
        val surround: Int = 0,
        val louder: Int = 0,
        /** Podcasts: silences in an episode skipped over. */
        val skipSilence: Boolean = false,
        /** Music: each song takes the setting made for its kind of music as it comes on. */
        val followKind: Boolean = false,
    ) {
        /** The ten bands as they play now. */
        val levels: List<Float> get() = if (!eqOn) List(10) { 0f } else if (preset == OWN) own else spread(presets[preset].db.toList())
    }

    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("sound", Context.MODE_PRIVATE)

    /** Goes up by one on every change, so the screens and the player see it. */
    var changes by mutableIntStateOf(0)
        private set

    /** What the player does on a change: put the settings on the sound now. Set by [PlaybackService]. */
    var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun profile(kind: Kind): Profile {
        if (!::app.isInitialized) return Profile()
        val p = prefs()
        val k = kind.name + "."
        return Profile(
            // Off until chosen: nothing sounds different until the person turns it on.
            eqOn = p.getBoolean(k + "eq", false),
            // "Your own" moved from 13 to 14 when Soul and R&B came in (26 November 2026).
            preset = p.getInt(k + "preset", if (kind == Kind.PODCASTS) 3 else 0).let { if (it == 13 && !p.contains(k + "presets2")) OWN else it }.coerceIn(0, OWN),
            // Your own setting, kept as ten bands since 25 November 2026; one kept as five before that is spread over ten.
            own = if (p.contains(k + "own9")) (0 until 10).map { p.getFloat(k + "own" + it, 0f) }
            else spread((0 until 5).map { p.getFloat(k + "own" + it, 0f) }),
            bass = p.getInt(k + "bass", 0),
            surround = p.getInt(k + "surround", 0),
            louder = p.getInt(k + "louder", 0),
            skipSilence = p.getBoolean(k + "skip", false),
            followKind = p.getBoolean(k + "follow", false),
        )
    }

    fun save(kind: Kind, profile: Profile) {
        val k = kind.name + "."
        prefs().edit().apply {
            putBoolean(k + "eq", profile.eqOn)
            putInt(k + "preset", profile.preset)
            profile.own.forEachIndexed { i, v -> putFloat(k + "own" + i, v) }
            putInt(k + "bass", profile.bass)
            putInt(k + "surround", profile.surround)
            putInt(k + "louder", profile.louder)
            putBoolean(k + "skip", profile.skipSilence)
            putBoolean(k + "follow", profile.followKind)
            putBoolean(k + "presets2", true)
        }.apply()
        changes++
        onChange?.invoke()
    }

    fun change(kind: Kind, edit: (Profile) -> Profile) = save(kind, edit(profile(kind)))

    /** Which profile a playing item takes: an episode or a radio station the podcast one, a song the music one. */
    fun kindOf(mediaId: String): Kind = if (mediaId.startsWith("pod:") || mediaId.startsWith("radio:")) Kind.PODCASTS else Kind.MUSIC

    /**
     * The gain for a frequency, in decibels, from the five bands: in a straight line between the two either side,
     * measured on the scale the ear hears (each doubling of pitch the same step).
     */
    fun gainAt(hz: Double, levels: List<Float>): Float = curve(hz, bands, levels)

    private fun curve(hz: Double, at: IntArray, levels: List<Float>): Float {
        if (hz <= at.first()) return levels.first()
        if (hz >= at.last()) return levels.last()
        val i = at.indexOfLast { it <= hz }
        val a = ln(at[i].toDouble())
        val b = ln(at[i + 1].toDouble())
        val t = ((ln(hz) - a) / (b - a)).toFloat()
        return levels[i] + (levels[i + 1] - levels[i]) * t
    }

    /** The phone's own sound effects on the player's sound, made again when the player's sound changes. */
    class Effects(session: Int) {
        // Android 9 and later: the ten bands exactly, as a graphic equaliser of KueSounds' own on the phone's sound.
        private val exact: android.media.audiofx.DynamicsProcessing? = if (android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            val config = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, bands.size, false, 0, false, 0, false,
            ).build()
            android.media.audiofx.DynamicsProcessing(0, session, config)
        }.getOrNull() else null
        private val eq = if (exact != null) null else runCatching { Equalizer(0, session) }.getOrNull()
        private val bass = runCatching { BassBoost(0, session) }.getOrNull()
        private val surround = runCatching { Virtualizer(0, session) }.getOrNull()
        private val louder = runCatching { LoudnessEnhancer(session) }.getOrNull()

        /** Which of them this phone has, for the settings to say. */
        val missing: List<String> = listOfNotNull(
            "the equaliser".takeIf { eq == null && exact == null }, "deep bass".takeIf { bass == null },
            "surround".takeIf { surround == null }, "louder".takeIf { louder == null },
        )

        fun apply(p: Profile) {
            exact?.let { d ->
                runCatching {
                    val levels = p.levels
                    for (ch in 0 until 2) {
                        val eqs = d.getPreEqByChannelIndex(ch)
                        for (b in bands.indices) {
                            // Each band reaches up to halfway (on the ear's scale) to the next one.
                            val top = if (b == bands.lastIndex) 20_000f else kotlin.math.sqrt(bands[b].toFloat() * bands[b + 1])
                            eqs.setBand(b, android.media.audiofx.DynamicsProcessing.EqBand(true, top, levels[b]))
                        }
                        eqs.isEnabled = p.eqOn
                        d.setPreEqByChannelIndex(ch, eqs)
                    }
                    d.enabled = p.eqOn
                }
            }
            eq?.let { e ->
                runCatching {
                    val range = e.bandLevelRange
                    for (b in 0 until e.numberOfBands) {
                        val hz = e.getCenterFreq(b.toShort()) / 1000.0
                        val mb = (gainAt(hz, p.levels) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                        e.setBandLevel(b.toShort(), mb.toShort())
                    }
                    e.enabled = p.eqOn
                }
            }
            bass?.let { b -> runCatching { if (b.strengthSupported) b.setStrength((p.bass * 10).toShort()); b.enabled = p.bass > 0 } }
            surround?.let { v -> runCatching { if (v.strengthSupported) v.setStrength((p.surround * 10).toShort()); v.enabled = p.surround > 0 } }
            // Up to 8 decibels louder: more than that and loud songs break up.
            louder?.let { l -> runCatching { l.setTargetGain(p.louder * 8); l.enabled = p.louder > 0 } }
        }

        fun release() {
            runCatching { exact?.release() }
            runCatching { eq?.release() }
            runCatching { bass?.release() }
            runCatching { surround?.release() }
            runCatching { louder?.release() }
        }
    }

    /** What the phone lacks of the four, said by the player once it has tried them. */
    var missing: List<String> = emptyList()
        internal set
}

/**
 * Even loudness (Kevin, 25 November 2026, from CloudPlayer's "loudness normalization"): a slow, gentle volume hand that
 * brings quiet songs up and loud ones down to the same level, so the volume needn't be touched between them. It listens
 * over about three seconds, so it doesn't pump with the beat, and never lifts a song more than 9 decibels.
 */
object Leveller : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile var on = false

    /** The level aimed for (as a share of full scale, measured as the average power over a few seconds). */
    private const val TARGET = 0.16
    private const val MOST_UP = 2.8
    private const val MOST_DOWN = 0.4
    private var power = TARGET * TARGET
    private var gain = 1.0
    private var rate = 44_100
    private var channels = 2

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (!on) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val start = inputBuffer.position()
        val samples = size / 2
        var sum = 0.0
        for (i in 0 until samples) {
            val v = inputBuffer.getShort(start + i * 2) / 32768.0
            sum += v * v
        }
        val frames = (samples / channels).coerceAtLeast(1)
        // About three seconds to follow a change.
        val keep = Math.exp(-frames.toDouble() / (rate * 3.0))
        power = power * keep + (sum / samples) * (1 - keep)
        val wanted = (TARGET / Math.sqrt(power.coerceAtLeast(1e-7))).coerceIn(MOST_DOWN, MOST_UP)
        // The change spread across this piece of sound, so it is never heard as a step.
        val from = gain
        for (i in 0 until samples) {
            val g = from + (wanted - from) * i / samples
            var v = inputBuffer.getShort(start + i * 2) * g
            // Peaks rounded off softly rather than clipped.
            if (v > 24_000) v = 24_000 + (v - 24_000) / (1 + (v - 24_000) / 8_767)
            if (v < -24_000) v = -24_000 + (v + 24_000) / (1 - (v + 24_000) / 8_767)
            out.putShort(v.toInt().coerceIn(-32768, 32767).toShort())
        }
        gain = wanted
        inputBuffer.position(start + samples * 2)
        if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        // A new song starts from where the last one's level was: the hand moves on from there.
    }
}
