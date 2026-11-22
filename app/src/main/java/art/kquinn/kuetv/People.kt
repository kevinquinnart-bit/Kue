package art.kquinn.kuetv

import com.google.gson.JsonParser
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Films an actor was in, or a director made, from Wikidata: the free, open knowledge base behind Wikipedia.
 * No account or key is needed. Each film comes with the number add-ons know it by (its IMDb number), so one
 * that isn't in the provider's list can still be played from the add-ons.
 */
object Credits {
    /** One person in a series' cast, as TVmaze has them: their name, who they play, and TVmaze's number for them. */
    class Role(val person: String, val character: String, val personId: Int)

    /** A series someone was in: its name, first year and IMDb number (empty when TVmaze doesn't have one). */
    class Series(val name: String, val year: Int?, val imdb: String, val poster: String)

    private const val TVMAZE = "https://api.tvmaze.com"

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("TVmaze answered " + r.code) else r.body.string()
        }

    /**
     * The cast of a series, from TVmaze (free, no account): found by its IMDb number when that is known, otherwise by
     * its name. Empty when TVmaze doesn't have it. Blocks; call off the main thread.
     */
    fun seriesCast(name: String, imdb: String?): List<Role> {
        val showId = (if (!imdb.isNullOrBlank()) runCatching { JsonParser.parseString(get("$TVMAZE/lookup/shows?imdb=$imdb")).asJsonObject.get("id").asInt }.getOrNull() else null)
            ?: runCatching { JsonParser.parseString(get("$TVMAZE/singlesearch/shows?q=" + URLEncoder.encode(name, "UTF-8"))).asJsonObject.get("id").asInt }.getOrNull()
            ?: return emptyList()
        return JsonParser.parseString(get("$TVMAZE/shows/$showId/cast")).asJsonArray.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                val person = o.getAsJsonObject("person")
                Role(person.get("name").asString, o.getAsJsonObject("character")?.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), person.get("id").asInt)
            }.getOrNull()
        }.distinctBy { it.personId }.take(10)
    }

    /** The series a person (by TVmaze's number) has been in, newest first. Blocks; call off the main thread. */
    fun seriesOf(personId: Int): List<Series> =
        JsonParser.parseString(get("$TVMAZE/people/$personId/castcredits?embed=show")).asJsonArray.mapNotNull { e ->
            runCatching {
                val show = e.asJsonObject.getAsJsonObject("_embedded").getAsJsonObject("show")
                Series(
                    show.get("name").asString,
                    show.get("premiered")?.takeIf { it.isJsonPrimitive }?.asString?.take(4)?.toIntOrNull(),
                    show.getAsJsonObject("externals")?.get("imdb")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    show.getAsJsonObject("image")?.get("medium")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                )
            }.getOrNull()
        }.distinctBy { it.name }.sortedByDescending { it.year ?: 0 }

    class Film(val title: String, val imdb: String, val year: Int?)

    private val http by lazy { Provider.http.newBuilder().callTimeout(25, TimeUnit.SECONDS).build() }
    private const val ENDPOINT = "https://query.wikidata.org/sparql"
    private const val AGENT = "KueTV/1.0 (https://github.com/kevinquinnart-bit/Kue)"

    /** The films [person] was in, or directed when [directed], newest first; at most 60. Blocks; call off the main thread. */
    fun films(person: String, directed: Boolean): List<Film> {
        val who = person.replace("\\", "").replace("\"", "").trim()
        if (who.isEmpty()) return emptyList()
        val role = if (directed) "P57" else "P161"
        val query = """
            SELECT ?imdb ?title (MIN(?date) AS ?first) WHERE {
              ?person rdfs:label "$who"@en ; wdt:P31 wd:Q5 .
              ?film wdt:$role ?person ; wdt:P31 wd:Q11424 ; wdt:P345 ?imdb ; rdfs:label ?title .
              FILTER(LANG(?title) = "en")
              OPTIONAL { ?film wdt:P577 ?date }
            } GROUP BY ?imdb ?title ORDER BY DESC(?first) LIMIT 60
        """.trimIndent()
        val url = ENDPOINT + "?format=json&query=" + URLEncoder.encode(query, "UTF-8")
        val text = http.newCall(
            Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/sparql-results+json").build(),
        ).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("Wikidata answered " + r.code) else r.body.string() }
        val rows = JsonParser.parseString(text).asJsonObject.getAsJsonObject("results").getAsJsonArray("bindings")
        return rows.mapNotNull { row ->
            val o = row.asJsonObject
            val value: (String) -> String? = { k -> o.getAsJsonObject(k)?.get("value")?.asString }
            val imdb = value("imdb")?.takeIf { it.startsWith("tt") } ?: return@mapNotNull null
            Film(value("title").orEmpty(), imdb, value("first")?.take(4)?.toIntOrNull())
        }.distinctBy { it.imdb }
    }

    /** A title made plain for comparing with the provider's: "EN - The Terminal (2004) 4K" and "The Terminal" are the same. */
    fun plain(name: String): String =
        name.lowercase()
            .replace(Regex("\\(\\d{4}\\)|\\[[^\\]]*]"), " ")
            .replace(Regex("^[a-z]{2,3}\\s*[-:|]\\s*"), "")
            .replace(Regex("\\b(4k|uhd|fhd|hd|sd)\\b"), " ")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")
}
