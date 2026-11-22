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
