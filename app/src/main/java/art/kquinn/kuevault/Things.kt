package art.kquinn.kuevault

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The kinds of paper a thing can have. */
enum class Paper(val title: String, val one: String) {
    RECEIPT("Receipt or invoice", "Receipt"),
    WARRANTY("Warranty", "Warranty"),
    MANUAL("Manual", "Manual"),
    PHOTO("Photo", "Photo"),
    OTHER("Other papers", "Papers"),
}

/** One file belonging to a thing: which place it is in, and its id and name there. */
data class Paperwork(val kind: Paper, val placeId: String, val id: String, val name: String, val size: Long, val mime: String) {
    fun item(): Store.Item = Store.Item(id, name, size, 0L, mime, placeId, Section.THINGS)
}

/**
 * Something the person owns, with everything that goes with it: where and when it was bought, its warranty,
 * and its papers (receipt, warranty, manual, photos), which live in My things' place.
 */
data class Thing(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val make: String = "",
    val model: String = "",
    val serial: String = "",
    val shop: String = "",
    /** The day it was bought, as days since 1 Jan 1970; null when not known. */
    val boughtDay: Long? = null,
    val price: String = "",
    /** How long the warranty lasts from the day it was bought, in months; null when not known. */
    val warrantyMonths: Int? = null,
    val barcode: String = "",
    val notes: String = "",
    val papers: List<Paperwork> = emptyList(),
    val addedMs: Long = System.currentTimeMillis(),
) {
    val bought: LocalDate? get() = boughtDay?.let { LocalDate.ofEpochDay(it) }

    /** The last day of the warranty, when both the day it was bought and the warranty's length are known. */
    val warrantyEnds: LocalDate? get() {
        val b = bought ?: return null
        val m = warrantyMonths ?: return null
        return b.plusMonths(m.toLong()).minusDays(1)
    }

    /** What the screen calls it: its name, else make and model. */
    val title: String get() = name.ifBlank { listOf(make, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unnamed thing" } }

    fun papersOf(kind: Paper) = papers.filter { it.kind == kind }
}

/** Where a warranty stands today. */
enum class Cover { NONE_KNOWN, COVERED, ENDING_SOON, ENDED }

/** Everything the person owns, kept on the phone (and in Android's own backup). The papers live in the places. */
object Things {
    val all = mutableStateListOf<Thing>()
    private var file: File? = null
    private val gson = Gson()

    fun init(context: Context) {
        file = File(context.filesDir, "things.json")
        runCatching {
            val type = object : TypeToken<List<Thing>>() {}.type
            val kept: List<Thing> = gson.fromJson(file!!.readText(), type)
            all.clear()
            // Gson leaves fields it didn't find null even when Kotlin says they can't be: put defaults back.
            all.addAll(kept.map { t -> tidy(t) })
        }
    }

    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun tidy(t: Thing): Thing = t.copy(
        id = t.id ?: UUID.randomUUID().toString(), name = t.name ?: "", make = t.make ?: "", model = t.model ?: "",
        serial = t.serial ?: "", shop = t.shop ?: "", price = t.price ?: "", barcode = t.barcode ?: "", notes = t.notes ?: "",
        papers = (t.papers ?: emptyList()).filter { it.kind != null && it.id != null },
    )

    private fun save() {
        runCatching { file?.writeText(gson.toJson(all.toList())) }
    }

    fun get(id: String): Thing? = all.firstOrNull { it.id == id }

    /** Puts [thing] in, or in place of the one with its id. */
    fun keep(thing: Thing) {
        val i = all.indexOfFirst { it.id == thing.id }
        if (i >= 0) all[i] = thing else all.add(0, thing)
        save()
    }

    fun remove(id: String) {
        if (all.removeAll { it.id == id }) save()
    }

    fun addPaper(id: String, paper: Paperwork) {
        val t = get(id) ?: return
        keep(t.copy(papers = t.papers + paper))
    }

    fun removePaper(id: String, paper: Paperwork) {
        val t = get(id) ?: return
        keep(t.copy(papers = t.papers.filterNot { it.placeId == paper.placeId && it.id == paper.id }))
    }

    fun forgetAll() {
        all.clear()
        runCatching { file?.delete() }
    }

    /**
     * Finds [paper] in the library. A section moved or archived to another place gives its files new ids
     * there, so a paper not found by its id is found by its name and size in My things.
     */
    fun find(paper: Paperwork, library: List<Store.Item>): Store.Item? =
        library.firstOrNull { it.placeId == paper.placeId && it.id == paper.id }
            ?: library.firstOrNull { it.section == Section.THINGS && it.name == paper.name && (paper.size <= 0 || it.size == paper.size) }

    /** After the library is read: papers whose files moved place point at where they are now. */
    fun relink(library: List<Store.Item>) {
        var changed = false
        val next = all.map { t ->
            val papers = t.papers.map { p ->
                val at = find(p, library)
                if (at != null && (at.placeId != p.placeId || at.id != p.id)) { changed = true; p.copy(placeId = at.placeId, id = at.id) } else p
            }
            t.copy(papers = papers)
        }
        if (changed) { all.clear(); all.addAll(next); save() }
    }

    /** Where [thing]'s warranty stands on [today]: ending soon is within 30 days. */
    fun cover(thing: Thing, today: LocalDate = LocalDate.now()): Cover {
        val end = thing.warrantyEnds ?: return Cover.NONE_KNOWN
        return when {
            end.isBefore(today) -> Cover.ENDED
            !end.isAfter(today.plusDays(30)) -> Cover.ENDING_SOON
            else -> Cover.COVERED
        }
    }

    /** Things matching every word of [query] in their name, make, model, serial, shop or notes. Newest first. */
    fun search(list: List<Thing>, query: String): List<Thing> {
        val words = Library.plain(query).split(' ').filter { it.isNotEmpty() }
        return list.filter { t ->
            words.isEmpty() || run {
                val text = Library.plain(listOf(t.name, t.make, t.model, t.serial, t.shop, t.notes, t.barcode).joinToString(" "))
                words.all { text.contains(it) }
            }
        }.sortedByDescending { it.boughtDay ?: (it.addedMs / 86_400_000L) }
    }

    /** A file name for a paper: "Bosch WAN28281GB · Receipt.pdf", with characters places refuse taken out. */
    fun fileName(thing: Thing, kind: Paper, extension: String): String {
        val base = (thing.title + " · " + kind.one).replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(90)
        return base + "." + extension.trimStart('.').ifBlank { "pdf" }
    }

    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}
