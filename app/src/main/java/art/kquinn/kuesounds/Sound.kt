package art.kquinn.kuesounds

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln

/**
 * How KueSounds sounds (Kevin, 25 November 2026): a graphic equaliser with ready-made settings and one of your own,
 * deep bass, surround and louder, kept as two profiles: one for music, one for podcasts (speech wants different
 * settings). The player switches profile by itself as it moves between a song and an episode.
 */
object Sound {
    enum class Kind(val label: String) { MUSIC("Music"), PODCASTS("Podcasts and radio") }

    /** The five bands shown, in hertz; the phone's own bands are set from them, in between where they differ. */
    val bands = intArrayOf(60, 230, 910, 3_600, 14_000)
    val bandNames = listOf("Bass", "Low middle", "Middle", "High middle", "Treble")

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
        Preset("Your own", floatArrayOf(0f, 0f, 0f, 0f, 0f)),
    )
    val OWN = presets.size - 1

    /** One profile: whether the equaliser is on, which setting, your own bands, and the three extras (0 to 100). */
    data class Profile(
        val eqOn: Boolean = false,
        val preset: Int = 0,
        val own: List<Float> = List(5) { 0f },
        val bass: Int = 0,
        val surround: Int = 0,
        val louder: Int = 0,
        /** Podcasts: silences in an episode skipped over. */
        val skipSilence: Boolean = false,
    ) {
        /** The five bands as they play now. */
        val levels: List<Float> get() = if (!eqOn) List(5) { 0f } else if (preset == OWN) own else presets[preset].db.toList()
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
            preset = p.getInt(k + "preset", if (kind == Kind.PODCASTS) 3 else 0).coerceIn(0, OWN),
            own = (0 until 5).map { p.getFloat(k + "own" + it, 0f) },
            bass = p.getInt(k + "bass", 0),
            surround = p.getInt(k + "surround", 0),
            louder = p.getInt(k + "louder", 0),
            skipSilence = p.getBoolean(k + "skip", false),
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
    fun gainAt(hz: Double, levels: List<Float>): Float {
        if (hz <= bands.first()) return levels.first()
        if (hz >= bands.last()) return levels.last()
        val i = bands.indexOfLast { it <= hz }
        val a = ln(bands[i].toDouble())
        val b = ln(bands[i + 1].toDouble())
        val t = ((ln(hz) - a) / (b - a)).toFloat()
        return levels[i] + (levels[i + 1] - levels[i]) * t
    }

    /** The phone's own sound effects on the player's sound, made again when the player's sound changes. */
    class Effects(session: Int) {
        private val eq = runCatching { Equalizer(0, session) }.getOrNull()
        private val bass = runCatching { BassBoost(0, session) }.getOrNull()
        private val surround = runCatching { Virtualizer(0, session) }.getOrNull()
        private val louder = runCatching { LoudnessEnhancer(session) }.getOrNull()

        /** Which of them this phone has, for the settings to say. */
        val missing: List<String> = listOfNotNull(
            "the equaliser".takeIf { eq == null }, "deep bass".takeIf { bass == null },
            "surround".takeIf { surround == null }, "louder".takeIf { louder == null },
        )

        fun apply(p: Profile) {
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
