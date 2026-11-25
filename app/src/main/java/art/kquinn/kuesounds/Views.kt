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
        Tab.ALBUMS to Layout.COVERS, Tab.SONGS to Layout.LIST, Tab.ARTISTS to Layout.LIST,
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
}
