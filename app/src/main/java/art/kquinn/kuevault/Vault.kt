package art.kquinn.kuevault

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The hub's sections. Each lives in one place the person owns; [ready] says whether its screen is built yet. */
enum class Section(val title: String, val colour: Long, val ready: Boolean, val coming: String) {
    PHOTOS("Photos", 0xFFFFD23F, false, "Photos come with KuePhotos"),
    MUSIC("Music", 0xFFF28C38, false, "Music opens in KueSounds"),
    FILMS("Films", 0xFF6BD08A, false, "Films open in KueTV"),
    DOCUMENTS("Documents", 0xFFC9A3FF, true, ""),
    BACKUPS("Backups", 0xFFB9C2D3, false, "Backups arrive with the new-phone move"),
}

/** The kinds of storage a person can connect. [ready]: KueVault can connect it in this version. */
enum class PlaceKind(val title: String, val ready: Boolean, val coming: String) {
    GOOGLE("Google Drive", true, ""),
    HOME("Home computer or network drive", true, ""),
    ONEDRIVE("OneDrive", false, "OneDrive comes once KueVault is registered with Microsoft"),
    DROPBOX("Dropbox", false, "Dropbox comes once KueVault is registered with Dropbox"),
}

/**
 * One storage place the person owns and connected: a Google account, or a shared folder on their home
 * computer. [label] is what the screen shows: the Google address, or the computer and folder.
 * For a home computer, [host], [share] and [user] say how to reach it; the password is kept apart, locked.
 */
data class Place(val id: String, val kind: PlaceKind, val label: String, val host: String = "", val share: String = "", val user: String = "") {
    /** The Google address, for a Google place. */
    val email: String get() = if (kind == PlaceKind.GOOGLE) label else ""

    companion object {
        fun google(email: String) = Place("g:" + email.lowercase(), PlaceKind.GOOGLE, email)
        fun home(host: String, share: String, user: String) =
            Place("h:" + host.lowercase() + "/" + share.lowercase(), PlaceKind.HOME, share + " on " + host, host, share, user)
    }
}

/** How much of a place is used. [limit] is null when the place gives no limit. */
data class Quota(val used: Long, val limit: Long?) {
    val free: Long get() = if (limit == null) Long.MAX_VALUE else (limit - used).coerceAtLeast(0)
    val fraction: Float get() = if (limit == null || limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
}

/** Whether a section's place can take a new file. */
enum class Room { OK, NEARLY_FULL, FULL, NO_PLACE }

/**
 * The hub: the places the person connected, which place each section lives in, and how full each place is.
 * Kept on the phone; the files themselves stay in the places.
 *
 * Each section lives in ONE place the person chose. KueVault never splits a section across several Google
 * accounts, or spills files into another account when one fills: Google's Drive rules don't allow tools
 * that get round storage limits that way (23 November 2026). When a place fills, the person is told, and
 * chooses another place for that section themselves.
 */
object Vault {
    /** A place is "nearly full" from here, and the person is warned. */
    const val NEARLY = 0.85f
    /** A section with an archive starts moving its oldest files there when its place is this full... */
    const val ARCHIVE_FROM = 0.80f
    /** ...and stops when it is back down to this. */
    const val ARCHIVE_TO = 0.70f
    /** A file that would take a place past this isn't put in; the place itself never runs out. */
    const val FULL = 0.97f

    private lateinit var app: Context
    val places = mutableStateListOf<Place>()
    /** Which place each section lives in, by place id. */
    val homes = mutableStateMapOf<Section, String>()
    /** Where each section keeps a full backup copy of its files, by place id. */
    val copies = mutableStateMapOf<Section, String>()
    /** Where each section's older files go when its place gets full, by place id. Only with a backup copy too. */
    val archives = mutableStateMapOf<Section, String>()
    val quotas = mutableStateMapOf<String, Quota>()

    fun init(context: Context) {
        app = context.applicationContext
        places.clear()
        homes.clear()
        copies.clear()
        archives.clear()
        val p = prefs()
        if (p.contains("places")) {
            places.addAll(readPlaces(p.getString("places", "[]").orEmpty()))
            homes.putAll(readHomes(p.getString("homes", "{}").orEmpty()))
            copies.putAll(readHomes(p.getString("copies", "{}").orEmpty()))
            archives.putAll(readHomes(p.getString("archives", "{}").orEmpty()))
        } else if (p.contains("accounts")) {
            // Version 1 kept a list of Google accounts per section. Each account becomes a place; each section
            // lives in its first account. Files in a section's other accounts stay there and still show.
            val (ps, hs) = fromAccounts(p.getString("accounts", "[]").orEmpty())
            places.addAll(ps)
            homes.putAll(hs)
            save()
            Report.note("moved to places: " + ps.size + " places, " + hs.size + " sections given a place")
        }
        for (place in places) readQuota(place.id)?.let { quotas[place.id] = it }
    }

    private fun prefs() = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    internal fun readPlaces(text: String): List<Place> = runCatching {
        JsonParser.parseString(text).asJsonArray.mapNotNull { e ->
            val o = e.asJsonObject
            val kind = runCatching { PlaceKind.valueOf(o.get("kind").asString) }.getOrNull() ?: return@mapNotNull null
            Place(o.get("id").asString, kind, o.get("label").asString, o.get("host")?.asString.orEmpty(), o.get("share")?.asString.orEmpty(), o.get("user")?.asString.orEmpty())
        }
    }.getOrDefault(emptyList())

    internal fun readHomes(text: String): Map<Section, String> = runCatching {
        JsonParser.parseString(text).asJsonObject.entrySet().mapNotNull { (k, v) ->
            (runCatching { Section.valueOf(k) }.getOrNull() ?: return@mapNotNull null) to v.asString
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Version 1's accounts as places: every address once, and each section in its first account. */
    internal fun fromAccounts(text: String): Pair<List<Place>, Map<Section, String>> {
        data class Old(val email: String, val section: Section?, val order: Int)
        val old = runCatching {
            JsonParser.parseString(text).asJsonArray.map { e ->
                val o = e.asJsonObject
                Old(o.get("email").asString, runCatching { Section.valueOf(o.get("section").asString) }.getOrNull(), o.get("order")?.asInt ?: 0)
            }
        }.getOrDefault(emptyList())
        val ps = old.map { Place.google(it.email) }.distinctBy { it.id }
        val hs = old.filter { it.section != null }.groupBy { it.section!! }.mapValues { (_, list) -> Place.google(list.minBy { it.order }.email).id }
        return ps to hs
    }

    private fun save() {
        val array = JsonArray()
        for (p in places) array.add(JsonObject().apply {
            addProperty("id", p.id); addProperty("kind", p.kind.name); addProperty("label", p.label)
            if (p.host.isNotEmpty()) addProperty("host", p.host)
            if (p.share.isNotEmpty()) addProperty("share", p.share)
            if (p.user.isNotEmpty()) addProperty("user", p.user)
        })
        fun map(m: Map<Section, String>) = JsonObject().apply { for ((s, id) in m) addProperty(s.name, id) }.toString()
        prefs().edit().putString("places", array.toString()).putString("homes", map(homes)).putString("copies", map(copies))
            .putString("archives", map(archives)).remove("accounts").apply()
    }

    private fun readQuota(id: String): Quota? {
        val used = prefs().getLong("used.$id", -1)
        if (used < 0) return null
        val limit = prefs().getLong("limit.$id", -1)
        return Quota(used, limit.takeIf { it >= 0 })
    }

    fun keepQuota(id: String, quota: Quota) {
        quotas[id] = quota
        prefs().edit().putLong("used.$id", quota.used).putLong("limit.$id", quota.limit ?: -1).apply()
    }

    fun place(id: String?): Place? = places.firstOrNull { it.id == id }

    /** Connects a place (or keeps the one already connected with the same id) and gives back the one kept. */
    fun connect(place: Place): Place {
        val i = places.indexOfFirst { it.id == place.id }
        if (i >= 0) places[i] = place else places += place
        save()
        Report.note("place connected: " + describe(place))
        return place
    }

    /** Takes a place out of KueVault. Its files stay where they are; sections living there have no place again. */
    fun disconnect(id: String) {
        val place = place(id) ?: return
        places.removeAll { it.id == id }
        homes.entries.removeAll { it.value == id }
        copies.entries.removeAll { it.value == id }
        archives.entries.removeAll { it.value == id }
        quotas.remove(id)
        prefs().edit().remove("used.$id").remove("limit.$id").apply()
        if (place.kind == PlaceKind.HOME) Secrets.forget(app, id)
        save()
        Library.tidy()
        Report.note("place taken out: " + describe(place))
    }

    /** Makes [section] live in place [id]. New files go there from now on; files already elsewhere stay put. */
    fun setHome(section: Section, id: String) {
        homes[section] = id
        if (copies[section] == id) { copies.remove(section); archives.remove(section) }
        if (archives[section] == id) archives.remove(section)
        save()
        Report.note(section.title + " now lives in " + (place(id)?.let { describe(it) } ?: "?"))
    }

    fun homeOf(section: Section): Place? = place(homes[section])
    fun copyOf(section: Section): Place? = place(copies[section])
    fun archiveOf(section: Section): Place? = place(archives[section])

    /** Sets or clears ([id] null) where [section] keeps its backup copy. Clearing it clears the archive too. */
    fun setCopy(section: Section, id: String?) {
        if (id != null && place(id)?.kind == PlaceKind.GOOGLE) return
        if (id == null) { copies.remove(section); archives.remove(section) } else copies[section] = id
        if (archives[section] != null && archives[section] == copies[section]) archives.remove(section)
        save()
        Report.note(section.title + " backup copy: " + (place(id)?.let { describe(it) } ?: "none"))
    }

    /**
     * Sets or clears where [section]'s older files go. Refused (false) without a backup copy in another place:
     * an archived file must always be in two places, so one lost or stolen drive never loses it.
     */
    fun setArchive(section: Section, id: String?): Boolean {
        if (id != null && (copies[section] == null || copies[section] == id || homes[section] == id || place(id)?.kind == PlaceKind.GOOGLE)) return false
        if (id == null) archives.remove(section) else archives[section] = id
        save()
        Report.note(section.title + " archive: " + (place(id)?.let { describe(it) } ?: "none"))
        return true
    }

    /** The sections living in place [id]. */
    fun sectionsIn(id: String): List<Section> = Section.entries.filter { homes[it] == id }

    /** Forgets everything KueVault keeps on this phone. The files in the places are left as they are. */
    fun forgetAll() {
        for (p in places) if (p.kind == PlaceKind.HOME) Secrets.forget(app, p.id)
        places.clear()
        homes.clear()
        copies.clear()
        archives.clear()
        quotas.clear()
        prefs().edit().clear().apply()
        Report.note("everything on this phone forgotten")
    }

    /** Whether [section]'s place can take a file of [size] bytes. */
    fun room(section: Section, size: Long): Room {
        val home = homeOf(section) ?: return Room.NO_PLACE
        return room(quotas[home.id], size)
    }

    fun room(q: Quota?, size: Long): Room {
        val limit = q?.limit ?: return Room.OK
        if (limit <= 0) return Room.OK
        val after = (q.used + size).toFloat() / limit
        return when {
            after >= FULL -> Room.FULL
            after >= NEARLY -> Room.NEARLY_FULL
            else -> Room.OK
        }
    }

    /**
     * Which files to archive so a place at [quota] comes back down to [ARCHIVE_TO]: the oldest first.
     * Nothing when the place isn't past [ARCHIVE_FROM] (or [force], for "Archive now": past [ARCHIVE_TO]).
     */
    fun toArchive(files: List<Store.Item>, quota: Quota?, force: Boolean = false): List<Store.Item> {
        val limit = quota?.limit ?: return emptyList()
        if (limit <= 0) return emptyList()
        val start = if (force) ARCHIVE_TO else ARCHIVE_FROM
        if (quota.used.toFloat() / limit < start) return emptyList()
        var over = quota.used - (limit * ARCHIVE_TO).toLong()
        val out = ArrayList<Store.Item>()
        for (f in files.sortedBy { it.modifiedMs }) {
            if (over <= 0) break
            out += f
            over -= f.size
        }
        return out
    }

    /** A place for the report, with addresses shortened: "Google Drive kq…@gmail.com". */
    fun describe(place: Place): String = place.kind.title + " " + if (place.kind == PlaceKind.GOOGLE) hidden(place.email) else place.share + " on " + place.host

    /** An address shortened for the report: "kq…@gmail.com". */
    fun hidden(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 2) email else email.take(2) + "…" + email.substring(at)
    }

    /** Bytes in words: "21.4 GB", "830 MB". */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.UK, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(java.util.Locale.UK, "%.0f MB", bytes / 1e6)
        bytes >= 1_000L -> String.format(java.util.Locale.UK, "%.0f KB", bytes / 1e3)
        else -> "$bytes bytes"
    }
}
