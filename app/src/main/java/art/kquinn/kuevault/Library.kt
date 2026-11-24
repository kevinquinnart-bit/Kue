package art.kquinn.kuevault

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.Normalizer

/**
 * One library of everything in every place, kept on the phone so it shows at once and can be searched with
 * no internet. Read afresh from the places when KueVault opens and after anything changes.
 */
object Library {
    val items = mutableStateListOf<Store.Item>()
    /** Read from the places at least once since KueVault opened. */
    var fresh by mutableStateOf(false)
    /** When the library was last read from the places, or 0. */
    var readAt by mutableStateOf(0L)

    private var file: File? = null
    private val gson = Gson()

    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        runCatching {
            val kept = file!!.readText()
            val type = object : TypeToken<Kept>() {}.type
            val k: Kept = gson.fromJson(kept, type)
            items.clear()
            items.addAll(k.items.filter { it.section != null && it.name != null })
            readAt = k.readAt
        }
    }

    private class Kept(val readAt: Long, val items: List<Store.Item>)

    private fun save() {
        runCatching { file?.writeText(gson.toJson(Kept(readAt, items.toList()))) }
    }

    /** Puts in what was just read from the places: each place's list replaces what was kept for it. */
    fun replace(fromPlaces: Map<String, List<Store.Item>>) {
        val keep = items.filter { it.placeId !in fromPlaces.keys && Vault.place(it.placeId) != null }
        items.clear()
        items.addAll((keep + fromPlaces.values.flatten()).sortedByDescending { it.modifiedMs })
        readAt = System.currentTimeMillis()
        fresh = true
        save()
    }

    fun add(item: Store.Item) {
        items.add(0, item)
        save()
    }

    fun remove(item: Store.Item) {
        items.removeAll { it.placeId == item.placeId && it.id == item.id }
        save()
    }

    /** Forgets what places no longer connected held. */
    fun tidy() {
        if (items.removeAll { Vault.place(it.placeId) == null }) save()
    }

    fun forgetAll() {
        items.clear()
        readAt = 0
        fresh = false
        runCatching { file?.delete() }
    }

    fun of(section: Section): List<Store.Item> = items.filter { it.section == section }

    /** How results are put in order. */
    enum class Order(val title: String) { NEWEST("Newest"), NAME("Name"), BIGGEST("Biggest") }

    /**
     * What matches [query]: every word typed must be found in the file's name, its section, its kind of file
     * ("pdf", "jpg") or the place it is in. Capitals and accents don't matter. [section] null: every section.
     */
    fun search(all: List<Store.Item>, query: String, section: Section?, places: Map<String, String>, order: Order = Order.NEWEST): List<Store.Item> {
        val words = plain(query).split(' ').filter { it.isNotEmpty() }
        val found = all.filter { item ->
            (section == null || item.section == section) && (words.isEmpty() || run {
                val text = plain(item.name + " " + item.section.title + " " + item.name.substringAfterLast('.', "") + " " + (places[item.placeId] ?: ""))
                words.all { text.contains(it) }
            })
        }
        return when (order) {
            Order.NEWEST -> found.sortedByDescending { it.modifiedMs }
            Order.NAME -> found.sortedBy { plain(it.name) }
            Order.BIGGEST -> found.sortedByDescending { it.size }
        }
    }

    /** Lower case, accents taken off, anything but letters and numbers turned to spaces. */
    fun plain(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ").trim()
}
