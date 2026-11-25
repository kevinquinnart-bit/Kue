package art.kquinn.kuesounds

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How KueSounds plays and looks, chosen in Settings (Kevin, 25 November 2026: everything CloudPlayer's settings do, as
 * the least KueSounds should have). Kept on this phone.
 */
object PlayPrefs {
    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("play", Context.MODE_PRIVATE)

    /** Goes up by one on every change, so screens and the player see it. */
    var changes by mutableIntStateOf(0)
        private set

    /** What the player does on a change (audio focus, the leveller): set by [PlaybackService]. */
    var onChange: (() -> Unit)? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private fun flag(key: String, default: Boolean): Boolean = if (::app.isInitialized) prefs().getBoolean(key, default) else default
    private fun number(key: String, default: Int): Int = if (::app.isInitialized) prefs().getInt(key, default) else default

    private fun set(key: String, value: Any) {
        if (!::app.isInitialized) return
        prefs().edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
            }
        }.apply()
        changes++
        onChange?.invoke()
    }

    // ---- Playing ----

    /** Songs from the Drive play over mobile data. Off: on mobile data only songs saved on the phone play. */
    var mobileData: Boolean
        get() = flag("mobileData", true)
        set(v) = set("mobileData", v)

    /** Long songs (mixes, live sets, audiobooks) carry on where they were left. */
    var keepPlace: Boolean
        get() = flag("keepPlace", true)
        set(v) = set("keepPlace", v)

    /** How long a song must be for its place to be kept, in minutes. */
    var keepPlaceMinutes: Int
        get() = number("keepPlaceMinutes", 10)
        set(v) = set("keepPlaceMinutes", v)

    /** Shuffle stays on (or off) from one time to the next. */
    var rememberShuffle: Boolean
        get() = flag("rememberShuffle", false)
        set(v) = set("rememberShuffle", v)

    /** Whether shuffle was on when last changed, for [rememberShuffle]. */
    var lastShuffle: Boolean
        get() = flag("lastShuffle", false)
        set(v) { if (::app.isInitialized) prefs().edit().putBoolean("lastShuffle", v).apply() }

    /** Pause or go quieter when another app plays sound or a call comes. Off: KueSounds keeps playing. */
    var audioFocus: Boolean
        get() = flag("audioFocus", true)
        set(v) = set("audioFocus", v)

    /** Starts playing when headphones are plugged in. */
    var headsetPlay: Boolean
        get() = flag("headsetPlay", false)
        set(v) = set("headsetPlay", v)

    /** Starts playing when Bluetooth headphones, a speaker or the car connect. */
    var bluetoothPlay: Boolean
        get() = flag("bluetoothPlay", false)
        set(v) = set("bluetoothPlay", v)

    /** Evens out loudness from song to song (and within a quiet start), so the volume needn't be touched. */
    var evenLoudness: Boolean
        get() = flag("evenLoudness", false)
        set(v) = set("evenLoudness", v)

    /** How much of the songs played lately is kept to play again with no downloading, in megabytes. */
    var cacheMb: Int
        get() = number("cacheMb", 500)
        set(v) = set("cacheMb", v)

    // ---- Look ----

    /** A black background in place of the dark blue (saves battery on phones with OLED screens). */
    var black: Boolean
        get() = flag("black", false)
        set(v) = set("black", v)

    /** KueSounds stays upright when the phone is turned. */
    var portraitLock: Boolean
        get() = flag("portraitLock", false)
        set(v) = set("portraitLock", v)

    /** Large covers across the screen: 0 lets the screen's width decide. */
    var coverColumns: Int
        get() = number("coverColumns", 0)
        set(v) = set("coverColumns", v)

    /** The part of the music KueSounds opens on. */
    var startPart: String
        get() = if (::app.isInitialized) prefs().getString("startPart", Tab.ALBUMS.name) ?: Tab.ALBUMS.name else Tab.ALBUMS.name
        set(v) = set("startPart", v)

    /** The parts of the music left off the row (Kevin's choice). */
    var hiddenParts: Set<String>
        get() = if (::app.isInitialized) prefs().getStringSet("hiddenParts", emptySet()).orEmpty() else emptySet()
        set(v) {
            if (!::app.isInitialized) return
            prefs().edit().putStringSet("hiddenParts", v).apply()
            changes++
        }

    // ---- Where long songs got to ----

    fun place(id: String): Long = if (::app.isInitialized) app.getSharedPreferences("places", Context.MODE_PRIVATE).getLong(id, 0L) else 0L

    fun keepPlaceOf(id: String, ms: Long) {
        if (!::app.isInitialized) return
        app.getSharedPreferences("places", Context.MODE_PRIVATE).edit().apply { if (ms > 0) putLong(id, ms) else remove(id) }.apply()
    }
}
