package art.kquinn.kuevault

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The hub's sections. Each gets its own free accounts; [ready] says whether its screen is built yet. */
enum class Section(val title: String, val colour: Long, val ready: Boolean, val coming: String) {
    PHOTOS("Photos", 0xFFFFD23F, false, "Photos arrive in version 3"),
    MAIL("Mail", 0xFF5CCEFF, false, "Mail arrives in version 5"),
    MUSIC("Music", 0xFFF28C38, false, "Music arrives in version 6"),
    FILMS("Films", 0xFF6BD08A, false, "Films arrive in version 6"),
    DOCUMENTS("Documents", 0xFFC9A3FF, true, ""),
    BACKUPS("Backups", 0xFFB9C2D3, false, "Backups arrive in version 6"),
}

/** One free account given to a section. [order]: the first one fills up first. */
data class VaultAccount(val email: String, val section: Section, val order: Int)

/** How much of an account is used. [limit] is null when the provider gives no limit. */
data class Quota(val used: Long, val limit: Long?) {
    val free: Long get() = if (limit == null) Long.MAX_VALUE else (limit - used).coerceAtLeast(0)
    val fraction: Float get() = if (limit == null || limit <= 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
}

/**
 * The hub: which free accounts belong to which section, how full each is, and which one a new file goes to.
 * Kept on the phone; the files themselves stay in the accounts.
 */
object Vault {
    /** An account stops taking new files when it is this full, so the account itself never runs out. */
    const val FULL = 0.9f

    private lateinit var app: Context
    val accounts = mutableStateListOf<VaultAccount>()
    val quotas = mutableStateMapOf<String, Quota>()

    fun init(context: Context) {
        app = context.applicationContext
        accounts.clear()
        accounts.addAll(load())
        for (a in accounts) readQuota(a.email)?.let { quotas[a.email] = it }
    }

    private fun prefs() = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    private fun load(): List<VaultAccount> = runCatching {
        JsonParser.parseString(prefs().getString("accounts", "[]")).asJsonArray.mapNotNull { e ->
            val o = e.asJsonObject
            val section = runCatching { Section.valueOf(o.get("section").asString) }.getOrNull() ?: return@mapNotNull null
            VaultAccount(o.get("email").asString, section, o.get("order")?.asInt ?: 0)
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val array = JsonArray()
        for (a in accounts) array.add(JsonObject().apply { addProperty("email", a.email); addProperty("section", a.section.name); addProperty("order", a.order) })
        prefs().edit().putString("accounts", array.toString()).apply()
    }

    private fun readQuota(email: String): Quota? {
        val used = prefs().getLong("used.$email", -1)
        if (used < 0) return null
        val limit = prefs().getLong("limit.$email", -1)
        return Quota(used, limit.takeIf { it >= 0 })
    }

    fun keepQuota(email: String, quota: Quota) {
        quotas[email] = quota
        prefs().edit().putLong("used.$email", quota.used).putLong("limit.$email", quota.limit ?: -1).apply()
    }

    /** Adds an account to a section, after the ones already there; moves it if it was in another. */
    fun add(email: String, section: Section) {
        accounts.removeAll { it.email.equals(email, ignoreCase = true) }
        accounts += VaultAccount(email, section, (accounts.filter { it.section == section }.maxOfOrNull { it.order } ?: -1) + 1)
        save()
        Report.note("account added for " + section.title + ": " + hidden(email))
    }

    fun remove(email: String) {
        accounts.removeAll { it.email.equals(email, ignoreCase = true) }
        quotas.remove(email)
        prefs().edit().remove("used.$email").remove("limit.$email").apply()
        save()
        Report.note("account taken out: " + hidden(email))
    }

    /** Forgets everything KueVault keeps on this phone. The files in the accounts are left as they are. */
    fun forgetAll() {
        accounts.clear()
        quotas.clear()
        prefs().edit().clear().apply()
        Report.note("everything on this phone forgotten")
    }

    fun forSection(section: Section): List<VaultAccount> = accounts.filter { it.section == section }.sortedBy { it.order }

    /** Space used and the total of a section's accounts, as far as known. */
    fun sectionSpace(section: Section): Pair<Long, Long> {
        val qs = forSection(section).mapNotNull { quotas[it.email] }
        return qs.sumOf { it.used } to qs.sumOf { it.limit ?: 0L }
    }

    /**
     * The account a new file of [size] bytes goes to in [section]: the first, in order, that will still be
     * under [FULL] with it added. Null when none has room.
     */
    fun pick(section: Section, size: Long): VaultAccount? = pick(forSection(section), quotas, size)

    fun pick(candidates: List<VaultAccount>, known: Map<String, Quota>, size: Long): VaultAccount? =
        candidates.sortedBy { it.order }.firstOrNull { a ->
            val q = known[a.email] ?: return@firstOrNull true
            val limit = q.limit ?: return@firstOrNull true
            (q.used + size).toFloat() / limit < FULL
        }

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
