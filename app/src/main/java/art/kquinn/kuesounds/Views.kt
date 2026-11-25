package art.kquinn.kuesounds

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * How each part of the music section is shown (Kevin, 25 November 2026): a list (always with small pictures; the plain
 * list without them was dropped the same day) or large covers. Kept for each part on its own, on this phone.
 */
object Views {
    enum class Layout(val label: String, val icon: String) {
        LIST("List", KueSettings.Icons.VIEW_PICTURES),
        COVERS("Large covers", KueSettings.Icons.VIEW_COVERS),
    }

    /** The parts that can be shown each way, and how each shows until chosen (as each looked before). */
    val defaults = mapOf(
        Tab.ALBUMS to Layout.COVERS, Tab.SONGS to Layout.LIST, Tab.ARTISTS to Layout.LIST, Tab.GENRES to Layout.LIST, Tab.COMPOSERS to Layout.LIST,
        Tab.PLAYLISTS to Layout.LIST, Tab.FAVOURITES to Layout.LIST, Tab.ON_PHONE to Layout.COVERS,
    )

    private val chosen = mutableStateMapOf<Tab, Layout>()
    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("views", Context.MODE_PRIVATE)

    fun init(context: Context) {
        app = context.applicationContext
        for (t in defaults.keys) prefs().getString(t.name, null)?.let { n -> Layout.entries.firstOrNull { it.name == n } }?.let { chosen[t] = it }
    }

    fun layout(tab: Tab): Layout = chosen[tab] ?: defaults[tab] ?: Layout.LIST

    fun set(tab: Tab, layout: Layout) {
        chosen[tab] = layout
        if (::app.isInitialized) prefs().edit().putString(tab.name, layout.name).apply()
    }

    // ---- Sorting (Kevin, 25 November 2026: CloudPlayer's Sort by and Reverse sort as the least) ----

    val songSorts = listOf("Name", "Artist", "Album", "Year", "Length", "Newest added")
    val albumSorts = listOf("Name", "Artist", "Year", "Newest added")
    private val sorts = mutableStateMapOf<Tab, Int>()
    private val reversed = mutableStateMapOf<Tab, Boolean>()

    fun sortOf(tab: Tab): Int = sorts[tab] ?: if (::app.isInitialized) prefs().getInt("sort." + tab.name, 0) else 0
    fun reversedOf(tab: Tab): Boolean = reversed[tab] ?: if (::app.isInitialized) prefs().getBoolean("rev." + tab.name, false) else false

    fun setSort(tab: Tab, i: Int) {
        sorts[tab] = i
        if (::app.isInitialized) prefs().edit().putInt("sort." + tab.name, i).apply()
    }

    fun setReversed(tab: Tab, on: Boolean) {
        reversed[tab] = on
        if (::app.isInitialized) prefs().edit().putBoolean("rev." + tab.name, on).apply()
    }

    /** [songs] in the order chosen for [tab]. */
    fun sortSongs(songs: List<Track>, tab: Tab): List<Track> {
        val order: Comparator<Track> = when (sortOf(tab)) {
            1 -> compareBy<Track>({ Library.sortName(it.artist) }, { Library.sortName(it.album) }, { it.discNo }, { it.trackNo })
            2 -> compareBy({ Library.sortName(it.album) }, { it.discNo }, { it.trackNo })
            3 -> compareBy<Track>({ it.year.takeIf { y -> y > 0 } ?: Int.MAX_VALUE }, { Library.sortName(it.title) })
            4 -> compareBy({ it.durationMs })
            5 -> compareByDescending<Track> { it.modified }.thenBy { Library.sortName(it.title) }
            else -> compareBy({ Library.sortName(it.title) })
        }
        val sorted = songs.sortedWith(order)
        return if (reversedOf(tab)) sorted.reversed() else sorted
    }

    /** [albums] in the order chosen for [tab]. */
    fun sortAlbums(albums: List<Album>, tab: Tab): List<Album> {
        val order: Comparator<Album> = when (sortOf(tab)) {
            1 -> compareBy<Album>({ Library.sortName(it.artist) }, { it.year }, { Library.sortName(it.title) })
            2 -> compareBy<Album>({ it.year.takeIf { y -> y > 0 } ?: Int.MAX_VALUE }, { Library.sortName(it.title) })
            3 -> compareByDescending<Album> { al -> al.tracks.maxOfOrNull { it.modified }.orEmpty() }.thenBy { Library.sortName(it.title) }
            else -> compareBy({ Library.sortName(it.title) })
        }
        val sorted = albums.sortedWith(order)
        return if (reversedOf(tab)) sorted.reversed() else sorted
    }
}
