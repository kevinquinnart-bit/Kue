package art.kquinn.kuetv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Films and episodes watched to the end, kept on this device. One is marked by itself when it ends or is left in
 * its last few minutes, and the user can mark one either way. Known by the address it is played from.
 */
object Watched {
    /** Goes up by one whenever a mark changes, so posters and lists can follow. */
    var changes by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Profiles.scoped(context, "watched"), Context.MODE_PRIVATE)
    private fun key(url: String) = url.hashCode().toString()

    /** Every mark, written out for a backup or the other device. */
    fun export(context: Context): String = prefs(context).all.keys.sorted().joinToString("\n")

    /** Takes the marks from a backup or the other device, in place of these. */
    fun import(context: Context, text: String) {
        val edit = prefs(context).edit().clear()
        for (k in text.split('\n')) if (k.isNotBlank()) edit.putBoolean(k, true)
        edit.apply()
        changes++
    }

    fun isWatched(context: Context, url: String): Boolean = url.isNotBlank() && prefs(context).getBoolean(key(url), false)

    fun set(context: Context, url: String, watched: Boolean) {
        if (url.isBlank()) return
        val edit = prefs(context).edit()
        if (watched) edit.putBoolean(key(url), true) else edit.remove(key(url))
        edit.apply()
        changes++
    }
}

/** How the films and series are listed: their sort, and whether those watched are hidden. Kept on this device. */
object Listing {
    /** The ways Films and Series can be sorted, Top rated first, the one used until another is picked (Kevin, 23 November 2026). */
    val sorts = listOf("Top rated", "Newest added", "Newest released", "A to Z", "Z to A", "Provider's order")

    var changes by mutableIntStateOf(0)
        private set

    fun touch() {
        changes++
    }

    private fun prefs(context: Context) = context.getSharedPreferences("listing", Context.MODE_PRIVATE)

    // Kept under a new name each time the start changes. 25 November 2026: Newest added is the start (Kevin: "the most
    // recent is top of any listing"); for a series that is the one with the newest episode added.
    fun sort(context: Context): Int = prefs(context).getInt("sort3", 1).coerceIn(0, sorts.size - 1)
    fun sort(context: Context, value: Int) {
        prefs(context).edit().putInt("sort3", value).apply()
        changes++
    }

    fun hideWatched(context: Context): Boolean = prefs(context).getBoolean("hide", false)
    fun hideWatched(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("hide", value).apply()
        changes++
    }

    /**
     * [items] in sort [sort] (see [sorts]): best rated first, newest added, newest by year, by name ignoring "The" and
     * the provider's tags either way round, or as the provider lists them. Those with no rating or year go last.
     */
    fun <T> sorted(items: List<T>, sort: Int, name: (T) -> String, added: (T) -> Long, rating: (T) -> Float, year: (T) -> Int): List<T> = when (sort) {
        0 -> items.sortedByDescending(rating)
        1 -> items.sortedByDescending(added)
        2 -> items.sortedByDescending(year)
        3 -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { plainName(name(it)) })
        4 -> items.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { plainName(name(it)) })
        else -> items
    }

    /** "|UK| The Batman" as "Batman": a provider's tag and a leading "The" or "A" don't count for A to Z. */
    fun plainName(name: String): String =
        name.trim().replace(Regex("^(\\[[^]]*]|\\|[^|]*\\||[A-Z]{2,4}\\s*[-:|]\\s)\\s*"), "").replace(Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE), "").trim()
}

/**
 * Pictures for films and series the provider gives none for, and for episodes with none: from the film index, found
 * by name, once each (Kevin, 25 November 2026: a picture on every show and every episode).
 */
object PosterLookup {
    private val posters = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val episodes = java.util.concurrent.ConcurrentHashMap<String, Map<Pair<Int, Int>, String>>()

    /** A name with the provider's tags, any year in brackets and a leading "The" left off, for searching. */
    internal fun searchName(name: String): String =
        Listing.plainName(name).replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").replace(Regex("\\s+(19|20)\\d\\d$"), "").trim()

    /** The poster for a film ("movie") or "series" called [name]; empty when none is found. Call off the screen's thread. */
    fun poster(type: String, name: String): String = posters.getOrPut("$type|$name") {
        runCatching {
            val plain = searchName(name)
            if (plain.isBlank()) return@runCatching ""
            val found = AddOns.search(type, plain)
            (found.firstOrNull { Listing.plainName(it.name).equals(plain, ignoreCase = true) } ?: found.firstOrNull())?.poster.orEmpty()
        }.getOrDefault("")
    }

    /** Each episode's picture for the series called [name], by season and number; empty when none are found. Call off the screen's thread. */
    fun episodePictures(name: String): Map<Pair<Int, Int>, String> = episodes.getOrPut(name) {
        runCatching {
            val show = AddOns.seriesByName(searchName(name)) ?: return@runCatching emptyMap()
            AddOns.episodes(show.id).orEmpty().filter { it.image.isNotBlank() }.associate { (it.season to it.number) to it.image }
        }.getOrDefault(emptyMap())
    }
}

/**
 * A wide picture for the top of a film's or series' page (Kevin, 25 November 2026: the full-width poster), from the film
 * index by the title's own number, found by name once. Empty when the title isn't in the index.
 */
object Backdrops {
    private val found = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** [type] "movie" or "series"; [imdb] the title's number when it is already known. Call off the screen's thread. */
    fun of(type: String, name: String, imdb: String? = null): String = found.getOrPut("$type|$name") {
        runCatching {
            val id = imdb?.takeIf { it.startsWith("tt") } ?: AddOns.idFor(type, name) ?: return@runCatching ""
            "https://images.metahub.space/background/medium/$id/img"
        }.getOrDefault("")
    }
}

/** Each series' episode order: oldest first (as the provider lists them) or newest first. Kept on this device. */
object EpisodeOrder {
    private fun prefs(context: Context) = context.getSharedPreferences("listing", Context.MODE_PRIVATE)
    // Newest first unless changed for that series (Kevin, 25 November 2026: the most recent at the top).
    fun newestFirst(context: Context, showId: String): Boolean = prefs(context).getBoolean("newest.$showId", true)
    fun set(context: Context, showId: String, newest: Boolean) {
        prefs(context).edit().putBoolean("newest.$showId", newest).apply()
        Listing.touch()
    }

    /** "2021-03-12" as "12 Mar 2021"; anything else as it is. */
    fun airedWords(aired: String): String = runCatching {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).parse(aired.take(10))
        java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(d!!)
    }.getOrDefault(aired)
}

/** A film's or series' trailer: opens it in the YouTube app, or the web if that isn't there. False when nothing can. */
fun openTrailer(context: Context, trailer: String): Boolean {
    val id = trailerId(trailer)
    if (id.isBlank()) return false
    for (address in listOf("vnd.youtube:$id", "https://www.youtube.com/watch?v=$id")) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(address)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return true
        } catch (e: android.content.ActivityNotFoundException) {
            // Try the next way.
        }
    }
    return false
}

/** Plays [url] in another video app (VLC, MX Player…). False when there is none, or it can't be played elsewhere. */
fun openElsewhere(context: Context, url: String, title: String): Boolean {
    if (url.isBlank() || url.startsWith("magnet:") || url.startsWith(Playable.NO_ADDRESS)) return false
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(android.net.Uri.parse(url), "video/*")
        .putExtra("title", title)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}

/** The YouTube video's id in a trailer as providers give it: the id itself, or a watch, short or embed address. */
fun trailerId(trailer: String): String = trailer.trim().let { t -> Regex("(?:v=|youtu\\.be/|embed/)([A-Za-z0-9_-]{6,})").find(t)?.groupValues?.get(1) ?: t }
