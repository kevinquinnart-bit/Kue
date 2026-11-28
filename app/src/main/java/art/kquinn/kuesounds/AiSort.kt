package art.kquinn.kuesounds

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sort with AI (Kevin, 28 November 2026: "painfully slow when you have a large catalogue ... enable the file fixer to
 * have an API key for an ai search and sort"; his sources are Wikipedia, Discogs and Google's AI overview). The person
 * pastes their own key, once:
 *
 *  - A Google key (free, from aistudio.google.com): Google's newest free Flash-Lite model. Searching Google from it is
 *    not free for new keys (Gemini 3 models: "Not available" on the free tier, Google's pricing page, 28 November 2026;
 *    Gemini 2.5, which had it, is closed to new users), so it is never asked to.
 *  - An OpenRouter key: a free model (":free"). OpenRouter's own web search is never used: it charges even on free models.
 *
 * Either way KueSounds searches Apple, Deezer and Discogs itself (free, no key) and the AI picks the right record and
 * puts the names right, with accents.
 *
 * The answers it is sure of, and that agree with the file, are written into the files; the rest wait in Check one at a
 * time with its answer filled in. Each one is asked once; a stop (the day's free allowance used, no Wi-Fi) carries on
 * from where it was the next time.
 */
object AiSort {
    private lateinit var app: Context
    private fun prefs() = app.getSharedPreferences("aisort", Context.MODE_PRIVATE)
    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    /** The key, kept on the phone only. */
    var key: String
        get() = if (::app.isInitialized) prefs().getString("key", "").orEmpty() else ""
        set(v) { prefs().edit().putString("key", v.trim()).apply(); keyChanges++ }
    var keyChanges by mutableIntStateOf(0)
        private set

    /** Whose key it is, from how it starts: "Google", "OpenRouter", or "" for neither. */
    fun whose(k: String): String = when {
        // Google's keys made in AI Studio now start "AQ." (the older ones "AIza"): both are Google's (Kevin, 28 November
        // 2026: his new key was turned away as "not a Google key").
        k.startsWith("AIza") || k.startsWith("AQ.") -> "Google"
        k.startsWith("sk-or-") -> "OpenRouter"
        else -> ""
    }

    var status by mutableStateOf("")
        private set
    var lastResult by mutableStateOf("")
        private set
    var done by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    @Volatile private var stopWanted = false

    private val waiting = ArrayList<ReviewUi.Item>()
    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() || approved.isNotEmpty() }
    val running: Boolean get() = status.isNotEmpty()

    /** An answer kept for Check one at a time: what was found, and whether it was sure. */
    class Answer(val look: Covers.Lookup?, val sure: Boolean, val written: Boolean = false)

    private fun keyOf(item: ReviewUi.Item) = item.songs.first().id + if (item.album != null) "|album" else ""

    /**
     * The kept answer for [item], under any of its songs: an album's songs can come in another order once one of them
     * changes, so its first song isn't always the one the answer was kept under.
     */
    private fun kept(item: ReviewUi.Item): Answer? {
        val end = if (item.album != null) "|album" else ""
        return item.songs.firstNotNullOfOrNull { answers[it.id + end] }
    }
    private val answers = HashMap<String, Answer>()
    private var loaded = false
    private var counted = false
    private fun file() = File(app.filesDir, "ai-answers.json")

    private fun load() {
        if (loaded || !::app.isInitialized) return
        loaded = true
        runCatching {
            val whole = JsonParser.parseString(file().readText()).asJsonObject
            // Answers from before Wikipedia and the compilation rules (28 November 2026) are asked again, except those
            // written into the files (a sure answer for a song now settled), which are kept so they can be listed.
            // Version 3 (28 November 2026): song pages lead to their album, and the artist is searched without " - ".
            val old = (whole.get("v")?.asInt ?: 1) < 3
            val o = if (whole.get("v") == null) whole else whole.getAsJsonObject("items")
            val settled = Scanner.data.tracks.filter { it.settled }.map { it.id }.toHashSet()
            for ((k, v) in o.entrySet()) {
                val a = v.asJsonObject
                val sure = a.get("sure")?.asBoolean == true
                val written = a.get("written")?.asBoolean ?: (sure && k.substringBefore('|') in settled)
                if (old && !written) continue
                answers[k] = Answer(a.getAsJsonObject("look")?.let { lookFrom(it) }, sure, written)
            }
            if (old) saveAnswers()
            // Songs written by earlier runs, recorded so they can be ticked as reviewed.
            val wroteIds = answers.filter { it.value.written }.keys.map { it.substringBefore('|') }
            if (wroteIds.isNotEmpty() && !prefs().getBoolean("aiMarked", false)) {
                prefs().edit().putBoolean("aiMarked", true).apply()
                Scanner.markAiWritten(writtenSongsLocked().map { it.id }.ifEmpty { wroteIds }, on = true)
            }
        }
    }

    /** The songs Sort with AI wrote into, for checking them (Kevin, 28 November 2026). */
    fun writtenSongs(): List<Track> = synchronized(answers) {
        load()
        writtenSongsLocked()
    }

    private fun writtenSongsLocked(): List<Track> {
        val ids = answers.filter { it.value.written }.keys.map { it.substringBefore('|') }.toHashSet()
        val byAlbum = answers.filter { it.value.written && it.key.endsWith("|album") }.keys.map { it.substringBefore('|') }
        val tracks = Scanner.data.tracks
        val albumSongs = byAlbum.flatMap { id -> tracks.firstOrNull { it.id == id }?.let { first -> tracks.filter { t -> t.album == first.album && Library.albumArtistOf(t) == Library.albumArtistOf(first) } }.orEmpty() }
        return (tracks.filter { it.id in ids } + albumSongs).distinctBy { it.id }.sortedWith(compareBy({ Library.sortName(it.artist) }, { Library.sortName(it.album) }, { it.trackNo }))
    }

    private fun saveAnswers() {
        val o = JsonObject()
        for ((k, a) in answers) o.add(k, JsonObject().apply { a.look?.let { add("look", lookTo(it)) }; addProperty("sure", a.sure); addProperty("written", a.written) })
        runCatching { file().writeText(JsonObject().apply { addProperty("v", 3); add("items", o) }.toString()) }
    }

    private fun lookTo(l: Covers.Lookup) = JsonObject().apply {
        addProperty("title", l.title); addProperty("artist", l.artist); addProperty("album", l.album); addProperty("albumArtist", l.albumArtist)
        addProperty("year", l.year); addProperty("track", l.trackNo); addProperty("disc", l.discNo); addProperty("genre", l.genre)
        addProperty("small", l.small); addProperty("big", l.big); addProperty("from", l.from); addProperty("kind", l.kind); addProperty("credits", l.credits)
    }

    private fun lookFrom(o: JsonObject): Covers.Lookup {
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return Covers.Lookup(s("title"), s("artist"), s("album"), s("albumArtist"), s("year"), s("track").toIntOrNull() ?: 0, s("disc").toIntOrNull() ?: 0,
            s("genre"), s("small"), s("big"), s("from"), kind = s("kind"), credits = s("credits"))
    }

    /** The answer kept for [item], for Check one at a time. */
    fun answerFor(item: ReviewUi.Item): Covers.Lookup? = synchronized(answers) { load(); kept(item)?.look }

    /** How many of [items] haven't been asked yet. */
    fun notAsked(items: List<ReviewUi.Item>): Int = synchronized(answers) { load(); items.count { kept(it) == null } }

    /** Starts sorting [items] (those not asked before). Needs the edit permission, for writing the sure ones. */
    fun queue(context: Context, items: List<ReviewUi.Item>) {
        init(context)
        val fresh = synchronized(answers) { load(); items.filter { kept(it) == null } }
        if (fresh.isEmpty()) { lastResult = "Every one has been asked already. Their answers are in Check one at a time."; return }
        val go = {
            stopWanted = false
            synchronized(waiting) { waiting.clear(); waiting += fresh }
            total = fresh.size
            done = 0
            status = "Starting"
            WorkService.start(context.applicationContext)
        }
        if (Google.canEdit) go() else Fixer.askPermission { go() }
    }

    fun stop() { stopWanted = true }

    private fun next(): ReviewUi.Item? = synchronized(waiting) { if (waiting.isEmpty()) null else waiting.removeAt(0) }

    private class Stop(message: String) : Exception(message)

    private val http by lazy { Drive.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build() }
    private val jsonType = "application/json".toMediaType()

    // ------------------------------------------------------------------------------------------------------------------
    // Asking

    /** What is known of [item] from its files, for the question. */
    fun facts(item: ReviewUi.Item, now: Map<FieldKey, String>? = null): String {
        val t = item.songs.first()
        fun mmss(ms: Long) = if (ms <= 0) "" else "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
        return if (item.album == null) buildString {
            appendLine("File name: " + Library.realName(t))
            appendLine("Song name in the file: " + (now?.get(FieldKey.TITLE) ?: t.title))
            appendLine("Artist in the file: " + (now?.get(FieldKey.ARTIST) ?: t.artist).takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty())
            appendLine("Album in the file: " + (now?.get(FieldKey.ALBUM) ?: t.album))
            appendLine("Year in the file: " + (now?.get(FieldKey.YEAR) ?: if (t.year > 0) t.year.toString() else ""))
            appendLine("Length: " + mmss(t.durationMs))
        } else buildString {
            appendLine("Album name in the files: " + item.album)
            appendLine("Artist in the files: " + (t.albumArtist.ifBlank { t.artist }).takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty())
            appendLine("Year in the files: " + (if (t.year > 0) t.year.toString() else ""))
            appendLine("Its songs (file name, song name, length):")
            for (s in item.songs.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title })).take(40)) {
                appendLine("- " + Library.realName(s) + " | " + s.title + " | " + mmss(s.durationMs))
            }
        }
    }

    private const val SHAPE =
        "{\"title\":\"\",\"artist\":\"\",\"album\":\"\",\"albumArtist\":\"\",\"year\":\"\",\"track\":0,\"disc\":0,\"genre\":\"\"," +
            "\"cover\":\"\",\"discogs\":\"\",\"wikipedia\":\"\",\"pick\":0,\"sure\":\"low\"}"

    /** The question for [item]; [found] is the list KueSounds found itself (for a model that can't search). */
    fun question(item: ReviewUi.Item, facts: String, found: List<Covers.Lookup>?): String = buildString {
        appendLine("You put music files right for a music library. " + if (item.album == null) "Work out which recording this song file is." else "Work out which album these files are.")
        appendLine()
        append(facts)
        if (found != null) {
            appendLine()
            if (found.any { isCompilation(it) }) appendLine("Records marked (compilation) are not the original album: don't pick one unless there is nothing else.")
            if (found.any { it.from == "Wikipedia" }) appendLine("Wikipedia records name the original release and its track list: prefer a Wikipedia record marked (studio album) or (album) that has the song.")
            appendLine(if (found.isEmpty()) "Nothing was found for it in Wikipedia, Apple Music, Deezer or Discogs: answer from what you know, with \"pick\" 0." else "Records found for it (choose by number in \"pick\", or 0 if none is it):")
            found.forEachIndexed { i, f ->
                appendLine("${i + 1}. " + listOf(Library.withoutRemaster(f.title), f.artist, Library.withoutRemaster(f.album), f.year, if (f.trackNo > 0) "track " + f.trackNo else "", f.genre, f.from,
                    if (f.credits.isNotBlank()) f.credits + " is named on its page, as a member" else "").filter { it.isNotBlank() }.joinToString(" | ") +
                    when { isCompilation(f) -> " (compilation)"; f.kind.isNotBlank() -> " (" + f.kind + ")"; else -> "" })
            }
        }
        appendLine()
        appendLine("Answer with one JSON object only, no other words, in this shape:")
        appendLine(SHAPE)
        appendLine("Rules:")
        if (item.album == null) {
            appendLine("- title: the song's name as it is on the original album's track list. artist and albumArtist: the artist that release is credited to (a band's album: the band, even if the file names its lead singer: old files often credit the singer by mistake), spelt as they spell it, with accents (Sinéad, not Sinead).")
            appendLine("- A Wikipedia song page names the album the song is from: that album is the answer, unless it is a compilation.")
            appendLine("- album: the original album the song first came out on. Never a remix single or a re-release. Never a compilation: no greatest hits, best of, collection, anthology, very best, hits or various artists album, even if that is all that was found. If it was never on an original album, give the single: album is the single's name and track is 1. track and disc: its place on that album, 0 if not known.")
            appendLine("- If the file is a mix, medley, megamix or mash-up of several songs, sure is \"low\".")
        } else {
            appendLine("- title: leave empty. album, albumArtist and artist: the album's real name and the artist's real name, spelt as they are, with accents.")
        }
        appendLine("- Leave out \"Remastered\", \"Remaster\" and the like from names: \"Shakedown\", not \"Shakedown (Remastered)\".")
        appendLine("- year: four figures, the year that release came out. genre: one or two words (Folk, Country, Pop, Rock...).")
        appendLine("- cover: a direct address of a picture of that release's cover, if you saw one. discogs: its discogs.com release address. wikipedia: its en.wikipedia.org page address. Empty if not known.")
        appendLine("- sure: \"high\" only when what you found plainly matches this file's artist and name; \"medium\" when it probably does; \"low\" when you are guessing.")
        appendLine("- Never make anything up: leave it empty instead.")
    }

    /** The first {...} in [text] (an answer may come wrapped in ``` or with words round it). */
    fun jsonIn(text: String): JsonObject? {
        val from = text.indexOf('{')
        val to = text.lastIndexOf('}')
        if (from < 0 || to <= from) return null
        return runCatching { JsonParser.parseString(text.substring(from, to + 1)).asJsonObject }.getOrNull()
    }

    /** Gemini's answer text, from its reply. */
    fun geminiText(reply: String): String = runCatching {
        JsonParser.parseString(reply).asJsonObject.getAsJsonArray("candidates").first().asJsonObject.getAsJsonObject("content")
            .getAsJsonArray("parts").joinToString("") { it.asJsonObject.get("text")?.asString.orEmpty() }
    }.getOrDefault("")

    /** OpenRouter's answer text, from its reply. */
    fun openRouterText(reply: String): String = runCatching {
        JsonParser.parseString(reply).asJsonObject.getAsJsonArray("choices").first().asJsonObject.getAsJsonObject("message").get("content").asString
    }.getOrDefault("")

    /** A free model on OpenRouter, from its list: one of the good ones if offered, else the first free one. */
    fun pickFree(models: String): String? = runCatching {
        val ids = JsonParser.parseString(models).asJsonObject.getAsJsonArray("data").map { it.asJsonObject.get("id").asString }.filter { it.endsWith(":free") }
        // Big general models first (the free list on 28 November 2026 had Gemma 4 31B and Nemotron 3 Ultra), tiny ones last.
        val liked = listOf("google/gemma-4-31b", "nvidia/nemotron-3-ultra", "deepseek/deepseek-chat", "meta-llama/llama-3.3-70b", "google/gemma", "qwen/qwen3", "mistralai/mistral")
        liked.firstNotNullOfOrNull { p -> ids.firstOrNull { it.startsWith(p) } } ?: ids.firstOrNull()
    }.getOrNull()

    /** What the answer says, as a find, with the links it gave for the cover. */
    class Said(val look: Covers.Lookup, val sure: String, val links: List<String>, val pick: Int)

    fun said(o: JsonObject, album: Boolean, from: String): Said {
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
        fun n(k: String) = s(k).substringBefore('/').toIntOrNull() ?: 0
        val look = Covers.Lookup(
            title = if (album) "" else Library.withoutRemaster(s("title")), artist = s("artist"), album = Library.withoutRemaster(s("album")), albumArtist = s("albumArtist").ifBlank { s("artist") },
            year = Regex("\\d{4}").find(s("year"))?.value.orEmpty(), trackNo = if (album) 0 else n("track"), discNo = if (album) 0 else n("disc"),
            genre = s("genre"), small = "", big = "", from = from,
        )
        val links = listOf(s("cover"), s("discogs"), s("wikipedia")).filter { it.startsWith("http") }
        return Said(look, s("sure").lowercase(), links, n("pick"))
    }

    private fun post(url: String, body: String, auth: Pair<String, String>): Pair<Int, String> {
        val req = Request.Builder().url(url).header(auth.first, auth.second).header("User-Agent", Catalogue.WHO).post(body.toRequestBody(jsonType)).build()
        return http.newCall(req).execute().use { r -> r.code to r.body.string() }
    }

    private var googleModel: String? = null
    private var freeModel: String? = null

    /**
     * Google's newest free Flash-Lite model, from its own list for this key (Kevin, 28 November 2026: Google answered
     * "gemini-2.5-flash-lite is no longer available to new users ... use gemini-3.5-flash-lite"). Flash-Lite for its
     * larger free daily allowance; plain Flash if there is no Lite.
     */
    fun pickGoogle(list: String): String? = runCatching {
        val names = JsonParser.parseString(list).asJsonObject.getAsJsonArray("models").map { it.asJsonObject }
            .filter { m -> m.getAsJsonArray("supportedGenerationMethods")?.any { it.asString == "generateContent" } == true }
            .map { it.get("name").asString.removePrefix("models/") }
            .filter { n -> n.startsWith("gemini-") && "flash" in n && listOf("preview", "exp", "tts", "live", "image", "audio", "embedding", "thinking").none { it in n } && !n.endsWith("-latest") }
        fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val lite = names.filter { "flash-lite" in it }.maxByOrNull { version(it) * 1000 - it.length }
        lite ?: names.filter { "flash-lite" !in it }.maxByOrNull { version(it) * 1000 - it.length }
    }.getOrNull()

    private fun googleModelFor(k: String): String = googleModel ?: run {
        val list = runCatching {
            http.newCall(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").header("x-goog-api-key", k).header("User-Agent", Catalogue.WHO).build())
                .execute().use { if (it.isSuccessful) it.body.string() else null }
        }.getOrNull()
        (list?.let { pickGoogle(it) } ?: "gemini-3.5-flash-lite").also { googleModel = it }
    }

    /** One ask to Google: the key in the header, else (if that is turned away) in the address, which some "AQ." keys need. */
    private fun google(text: String, k: String): Pair<Int, String> {
        val body = JsonObject().apply {
            add("contents", com.google.gson.JsonArray().apply {
                add(JsonObject().apply { add("parts", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) }) })
            })
        }.toString()
        fun once(): Pair<Int, String> {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" + googleModelFor(k) + ":generateContent"
            val first = post(url, body, "x-goog-api-key" to k)
            if (first.first != 401 && first.first != 403) return first
            val second = post(url + "?key=" + java.net.URLEncoder.encode(k, "UTF-8"), body, "X-Kue" to "1")
            return if (second.first in 200..299 || second.first == 429) second else first
        }
        var r = once()
        // The model taken away since it was chosen: choose again from Google's list, once.
        if (r.first == 404 || r.first == 400 && "no longer available" in r.second) { googleModel = null; r = once() }
        return r
    }

    /** Google's (or OpenRouter's) own words for what went wrong, from its reply. */
    fun saidWhy(reply: String): String = runCatching {
        val o = JsonParser.parseString(reply)
        val e = if (o.isJsonArray) o.asJsonArray.first().asJsonObject.getAsJsonObject("error") else o.asJsonObject.getAsJsonObject("error")
        e.get("message").asString.lineSequence().first().take(300)
    }.getOrDefault("")

    /**
     * Tries a key with one small ask, before it is kept: null when it works, else why not, in the service's own words.
     * Off the main thread.
     */
    fun test(k: String): String? = runCatching {
        when (whose(k)) {
            "Google" -> {
                googleModel = null
                val (code, reply) = google("Reply with the word OK.", k)
                if (code in 200..299) null else "Google said: " + saidWhy(reply).ifBlank { "error $code" }
            }
            "OpenRouter" -> {
                val (code, reply) = http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/key").header("Authorization", "Bearer $k").header("User-Agent", Catalogue.WHO).build())
                    .execute().use { it.code to it.body.string() }
                if (code in 200..299) null else "OpenRouter said: " + saidWhy(reply).ifBlank { "error $code" }
            }
            else -> "That isn't a Google or OpenRouter key."
        }
    }.getOrElse { "Couldn't reach it: " + (it.message ?: it.javaClass.simpleName) }

    /** The file name as words to search with: no ending, no track number, no signs. */
    fun nameWords(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("[_]+"), " ").replace(Regex("^\\s*\\d{1,3}\\s*[-.)]*\\s*"), "")
            .replace(Regex("[\\[\\](){}]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Records for [item] from Apple Music, Deezer and Discogs (free, no key), by its names, else by its file name. The
     * free AI models can't search the web for nothing, so KueSounds searches and the AI chooses and tidies.
     */
    private fun candidates(item: ReviewUi.Item, now: Map<FieldKey, String>?): List<Covers.Lookup> {
        val t = item.songs.first()
        val artist = searchArtist((now?.get(FieldKey.ARTIST) ?: if (item.album != null) t.albumArtist.ifBlank { t.artist } else t.artist).takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty())
        val title = Library.withoutBrackets(now?.get(FieldKey.TITLE) ?: t.title)
        val song = item.album == null
        val words = (if (song) "$artist $title" else artist + " " + item.album).trim()
        // Wikipedia first: it names the original release, its kind and its track list (Kevin, 28 November 2026).
        val wiki = runCatching {
            if (song) Covers.wikiRecords("\"" + title + "\" " + artist, title, artist).ifEmpty { Covers.wikiRecords("$title $artist song", title, artist) }
            else Covers.wikiRecords(item.album + " " + artist + " album", null, artist).filter { Covers.same(it.album, item.album) || Library.sortName(it.album).contains(Library.sortName(item.album)) }
        }.getOrDefault(emptyList()).take(3).let { w ->
            // A song's page names the album it is from: that album's own page too, for its cover and track number
            // (Kevin, 28 November 2026: "Don't Go" by Yazoo is on Upstairs at Eric's, 1982).
            val albums = w.filter { it.kind.startsWith("song page") && it.album.isNotBlank() }.map { it.album }.distinct()
            val pages = albums.filter { a -> w.none { it.kind.endsWith("album") && Covers.same(it.album, a) } }.take(1).flatMap { a ->
                runCatching { Covers.wikiRecords("\"" + a + "\" " + artist + " album", title, artist).filter { Covers.same(it.album, a) } }.getOrDefault(emptyList())
            }
            (pages + w).distinctBy { Library.sortName(it.album) + "|" + it.kind }
        }
        var found = wiki + runCatching { Covers.lookUp(words, song, if (song) title else null) }.getOrDefault(emptyList()).filter { !it.coverOnly }
        if (found.size == wiki.size && song) {
            val byName = nameWords(Library.realName(t))
            if (byName.isNotBlank() && !Covers.same(byName, words)) found = found + runCatching { Covers.lookUp(byName, true, null) }.getOrDefault(emptyList()).filter { !it.coverOnly }
        }
        // Original albums first (Kevin, 28 November 2026: "the original album song if possible and not compilation and
        // greatest hits albums"; the single if there was no album).
        return found.sortedBy { if (isCompilation(it)) 1 else 0 }.take(10)
    }

    /**
     * The artist to search with: the first name of a list ("Yazoo - Alison Moyet" is Yazoo; "Kenny Rogers with Dolly
     * Parton" is Kenny Rogers). A " - " in the words turned Wikipedia's search up empty (28 November 2026).
     */
    fun searchArtist(a: String): String =
        a.split(Regex("\\s+-\\s+|\\s+&\\s+|\\s+and\\s+|\\s+with\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|,\\s*|\\s+x\\s+", RegexOption.IGNORE_CASE))
            .first().trim().ifBlank { a.trim() }

    /** A compilation by its kind (Discogs and Wikipedia say), its artist (Various) or its name (greatest hits and the like). */
    fun isCompilation(l: Covers.Lookup): Boolean = l.kind == "compilation" || compilation(l.album) || l.albumArtist.equals("Various Artists", true)

    /** Whether an album's name is a compilation's: greatest hits, best of, collection and the like. */
    fun compilation(album: String): Boolean =
        Regex("greatest hits|best of|very best|\\bcollection\\b|anthology|\\bessential|\\bhits\\b|\\bgold\\b|platinum|ultimate|definitive|complete|now that'?s what|various artists|golden|favou?rites|compilation|the singles|box ?set|\\bvol(ume)?\\.? ?\\d|\\bthe best\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(album)

    /** Whether a song is a mix of several songs (medley, megamix, mash-up), which Kevin puts right himself. */
    fun mixOfSongs(text: String): Boolean =
        Regex("medley|megamix|mega mix|mash-? ?up|non-? ?stop|mixtape|party mix|dj mix|continuous mix", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Asks the AI about [item]. Throws Stop when it should not carry on (key refused, the day's allowance used). Off the main thread. */
    fun ask(item: ReviewUi.Item, now: Map<FieldKey, String>? = null, trying: (String) -> Unit = {}): Said? {
        val k = key
        val who = whose(k).ifEmpty { return null }
        trying("Looking it up")
        val found = candidates(item, now)
        val q = question(item, facts(item, now), found)
        trying("Asking the AI")
        val text = if (who == "Google") {
            val (code, reply) = google(q, k)
            checkReply(code, reply, "Google")
            geminiText(reply)
        } else {
            val model = freeModel ?: run {
                val list = runCatching { http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").header("User-Agent", Catalogue.WHO).build()).execute().use { it.body.string() } }.getOrNull()
                pickFree(list ?: "") ?: throw Stop("OpenRouter lists no free models just now.")
            }.also { freeModel = it }
            val body = JsonObject().apply {
                addProperty("model", model)
                add("messages", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", q) }) })
            }.toString()
            val (code, reply) = post("https://openrouter.ai/api/v1/chat/completions", body, "Authorization" to "Bearer $k")
            if (code == 404 || code == 400 && "model" in reply.lowercase()) freeModel = null
            checkReply(code, reply, "OpenRouter")
            openRouterText(reply)
        }
        val o = jsonIn(text) ?: return null
        val s = said(o, item.album != null, "AI")
        // A find's cover only when it is the album the AI settled on (not a compilation's cover on the original album).
        val picked = found.getOrNull(s.pick - 1)?.takeIf { Covers.same(it.album, s.look.album) }
        val chosen = picked?.takeIf { !isCompilation(it) }
        val kind = picked?.let { if (isCompilation(it)) "compilation" else it.kind }.orEmpty()
        return if (chosen != null) Said(s.look.copy(small = chosen.small, big = chosen.big, from = "AI, from " + chosen.from, kind = kind, credits = chosen.credits), s.sure, s.links, s.pick)
        else Said(s.look.copy(from = "AI", kind = kind), s.sure, s.links, s.pick)
    }

    private fun checkReply(code: Int, reply: String, who: String) {
        if (code in 200..299) return
        val low = reply.lowercase()
        when {
            code == 400 && ("api key" in low || "api_key" in low) || code == 401 || code == 403 -> throw Stop("$who turned the key away: " + saidWhy(reply).ifBlank { "error $code" })
            code == 402 -> throw Stop("OpenRouter says the account needs credit for this. Free models need none: try again later.")
            code == 429 && ("day" in low || "daily" in low || "perday" in low || "quota" in low) -> throw Stop("$who's free allowance for today is used up. Tap Sort again tomorrow: it carries on from here.")
            code == 429 -> throw RuntimeException("busy")
            else -> throw RuntimeException("$who said $code " + saidWhy(reply))
        }
    }

    /** The cover for what was found: the AI's own links first (picture, Discogs, Wikipedia), then the shops by name. */
    fun coverFor(s: Said, album: Boolean): Pair<ByteArray?, String> {
        if (s.look.big.isNotBlank()) Covers.download(s.look.big)?.let { return it to s.look.big }
        for (l in s.links) runCatching { Covers.pictureFromLink(l) }.getOrNull()?.takeIf { it.size > 4000 }?.let { return it to l }
        val l = s.look
        val name = l.album.ifBlank { return null to "" }
        val who = l.albumArtist.ifBlank { l.artist }
        val found = runCatching { Covers.lookUp("$who $name", false) }.getOrDefault(emptyList())
        val hit = found.firstOrNull { !it.coverOnly && Covers.same(it.album, name) && (who.isBlank() || Covers.sameArtist(it.artist, who)) } ?: return null to ""
        return Covers.download(hit.big) to hit.big
    }

    /** Whether what was found agrees with the file closely enough to write it without asking. */
    fun agrees(item: ReviewUi.Item, l: Covers.Lookup): Boolean {
        val t = item.songs.first()
        val artist = (if (item.album != null) t.albumArtist.ifBlank { t.artist } else t.artist).takeIf { it.isNotBlank() && it != Library.UNKNOWN_ARTIST }
        val who = l.artist.ifBlank { l.albumArtist }
        if (who.isBlank()) return false
        return if (item.album == null) {
            if (l.title.isBlank()) return false
            val name = Library.withoutBrackets(t.title)
            val sameName = Covers.same(Library.withoutBrackets(l.title), name) || Library.sortName(Library.realName(t)).contains(Library.sortName(Library.withoutBrackets(l.title)))
            // The band's record, when the file names its lead singer and the band's page names them too (Kevin, 28
            // November 2026: "the lead singer of a band listed as the artist. It's an error").
            val member = artist != null && l.credits.isNotBlank() && Covers.sameArtist(l.credits, artist)
            sameName && (artist == null || member || Covers.sameArtist(who, artist) || Covers.sameArtist(who, searchArtist(artist)) || Library.sortName(Library.realName(t)).contains(Library.sortName(who)))
        } else {
            val member = artist != null && l.credits.isNotBlank() && Covers.sameArtist(l.credits, artist)
            l.album.isNotBlank() && (Covers.same(l.album, item.album) || artist != null && Covers.sameArtist(who, artist)) && (artist == null || member || Covers.sameArtist(who, artist))
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The run, in the work service, screen off or on.

    /**
     * Writes [look] into [item]'s files with [cover] (a picture, any kind) as its one picture. True when every file took
     * it. Off the main thread.
     */
    private suspend fun write(context: Context, item: ReviewUi.Item, look: Covers.Lookup, cover: ByteArray?): Boolean {
        val set = LinkedHashMap<FieldKey, String>()
        fun put(k: FieldKey, v: String) { if (v.isNotBlank()) set[k] = v }
        if (item.album == null) {
            put(FieldKey.TITLE, look.title); put(FieldKey.ARTIST, look.artist)
            if (look.trackNo > 0) put(FieldKey.TRACK, look.trackNo.toString())
            if (look.discNo > 0) put(FieldKey.DISC_NO, look.discNo.toString())
        }
        put(FieldKey.ALBUM, look.album); put(FieldKey.ALBUM_ARTIST, look.albumArtist); put(FieldKey.YEAR, look.year); put(FieldKey.GENRE, look.genre)
        val jpeg = cover?.let { FixByHandUi.asJpeg(it) }
        val t = item.songs.first()
        val result = runCatching { Fixer.saveByHand(context, item.songs, set, emptySet(), jpeg, byHand = false) }.getOrDefault("")
        val ok = result.startsWith("Saved into the file") || result.startsWith("Saved into all")
        Scanner.note(
            if (ok) "AI sort wrote: " + (item.album ?: t.fileName) + " → " + look.artist + " / " + (look.title.ifBlank { look.album }) + (if (jpeg == null) " (no cover found)" else "")
            else "AI sort: not written: " + (item.album ?: t.fileName) + ": " + result,
        )
        if (ok) synchronized(answers) { answers[keyOf(item)] = Answer(look, kept(item)?.sure ?: false, true); saveAnswers() }
        return ok
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The AI's answers, ticked by Kevin in one list and saved together (28 November 2026: 135 sorted, only 10 written,
    // "all done, didn't update"; the rest had to be saved one at a time).

    /** [items] with the AI's answer for each (null when it had none), not yet written. */
    fun answersFor(items: List<ReviewUi.Item>): List<Pair<ReviewUi.Item, Covers.Lookup?>> = synchronized(answers) {
        load()
        val out = items.map { it to kept(it)?.takeIf { a -> !a.written }?.look?.takeIf { l -> l.artist.isNotBlank() || l.album.isNotBlank() }
            ?.let { l -> l.copy(title = Library.withoutRemaster(l.title), album = Library.withoutRemaster(l.album)) } }
        // Once a session, in the report: how many answers are kept and how many match the list (Kevin, 28 November
        // 2026: the list of answers didn't show).
        if (!counted) {
            counted = true
            Scanner.note("AI answers kept: " + answers.size + " (" + answers.count { it.value.written } + " written); of the " + items.size + " to check, " +
                items.count { kept(it) != null } + " have one, " + out.count { it.second != null } + " to look over")
        }
        out
    }

    /** Whether an answer is ticked to start with: it has names, and isn't a compilation or a mix of songs. */
    fun tickedAtFirst(item: ReviewUi.Item, l: Covers.Lookup?): Boolean =
        l != null && (if (item.album == null) l.title.isNotBlank() && l.artist.isNotBlank() else l.album.isNotBlank()) &&
            !isCompilation(l) && item.songs.none { mixOfSongs(it.title + " " + Library.realName(it)) }

    private val approved = ArrayList<Pair<ReviewUi.Item, Covers.Lookup>>()

    /** Saves the answers Kevin ticked into their files, in the work service. Needs the edit permission. */
    fun saveTicked(context: Context, list: List<Pair<ReviewUi.Item, Covers.Lookup>>) {
        init(context)
        if (list.isEmpty()) return
        val go = {
            stopWanted = false
            synchronized(waiting) { approved.clear(); approved += list }
            total = list.size
            done = 0
            status = "Saving"
            WorkService.start(context.applicationContext)
        }
        if (Google.canEdit) go() else Fixer.askPermission { go() }
    }

    private suspend fun runSaves(context: Context, show: (String) -> Unit) {
        var written = 0
        var failed = 0
        var stopped = ""
        try {
            while (true) {
                if (stopWanted) { synchronized(waiting) { approved.clear() }; stopped = "Stopped."; break }
                if (!Net.unmetered(context)) { synchronized(waiting) { approved.clear() }; stopped = "Stopped: not on Wi-Fi."; break }
                val (item, look) = synchronized(waiting) { if (approved.isEmpty()) null else approved.removeAt(0) } ?: break
                status = "Saving " + (done + 1) + " of " + total + ": " + (look.title.ifBlank { look.album })
                show(status)
                val cover = look.big.takeIf { it.isNotBlank() }?.let { runCatching { Covers.download(it) }.getOrNull() }
                if (write(context, item, look, cover)) written++ else failed++
                done++
            }
        } finally {
            lastResult = "Saved $written into the files" + (if (failed > 0) ", $failed couldn't be saved (in the report)" else "") + "." + if (stopped.isNotBlank()) " $stopped" else ""
            status = ""
        }
    }

    suspend fun run(context: Context, show: (String) -> Unit) {
        init(context)
        load()
        if (synchronized(waiting) { approved.isNotEmpty() }) runSaves(context, show)
        if (synchronized(waiting) { waiting.isEmpty() }) return
        var written = 0
        var toCheck = 0
        var notSure = 0
        var notMatching = 0
        var noAnswer = 0
        var stopped = ""
        val gap = if (whose(key) == "Google") 6500L else 3500L
        try {
            while (true) {
                if (stopWanted) { synchronized(waiting) { waiting.clear() }; stopped = "Stopped. Tap Sort again to carry on."; break }
                if (!Net.unmetered(context)) { synchronized(waiting) { waiting.clear() }; stopped = "Stopped: not on Wi-Fi. Tap Sort again on Wi-Fi to carry on."; break }
                val item = next() ?: break
                val t = item.songs.first()
                status = "Sorting " + (done + 1) + " of " + total + ": " + (item.album ?: t.title)
                show(status)
                // A mix of several songs: not asked about, left for Kevin to put right himself (28 November 2026).
                if (item.songs.any { mixOfSongs(it.title + " " + Library.realName(it)) }) {
                    toCheck++
                    synchronized(answers) { answers[keyOf(item)] = Answer(null, false); saveAnswers() }
                    done++
                    continue
                }
                var said: Said? = null
                try {
                    said = try { ask(item) } catch (x: RuntimeException) {
                        if (x.message == "busy") { kotlinx.coroutines.delay(60_000); ask(item) } else throw x
                    }
                } catch (x: Stop) {
                    synchronized(waiting) { waiting.clear() }
                    stopped = x.message.orEmpty()
                    break
                } catch (x: Exception) {
                    Scanner.note("AI sort: not asked: " + (item.album ?: t.fileName) + ": " + x.javaClass.simpleName + " " + x.message)
                    done++
                    kotlinx.coroutines.delay(gap)
                    continue
                }
                val s = said
                if (s != null && (s.look.artist.isNotBlank() || s.look.album.isNotBlank())) {
                    val (cover, address) = runCatching { coverFor(s, item.album != null) }.getOrDefault(null to "")
                    val answer = s.look.copy(small = address.ifBlank { s.look.small }, big = address.ifBlank { s.look.big })
                    // Never written without asking: a compilation, or a mix of several songs (Kevin puts those right himself).
                    val mix = item.songs.any { mixOfSongs(it.title + " " + Library.realName(it)) }
                    synchronized(answers) { answers[keyOf(item)] = Answer(answer, s.sure == "high"); saveAnswers() }
                    val agreed = agrees(item, s.look) && !isCompilation(s.look) && !mix
                    if (s.sure == "high" && agreed && Google.canEdit && write(context, item, answer, cover)) written++
                    else {
                        toCheck++
                        if (s.sure != "high") notSure++ else notMatching++
                    }
                } else {
                    toCheck++
                    noAnswer++
                    synchronized(answers) { answers[keyOf(item)] = Answer(s?.look, false); saveAnswers() }
                }
                done++
                kotlinx.coroutines.delay(gap)
            }
        } finally {
            lastResult = listOfNotNull(
                if (written > 0 || toCheck > 0) "Sorted $done of $total: $written written into the files. $toCheck for you to look over" +
                    listOfNotNull(
                        if (notSure > 0) "$notSure the AI wasn't sure of" else null,
                        if (notMatching > 0) "$notMatching with names unlike the file's" else null,
                        if (noAnswer > 0) "$noAnswer with no answer" else null,
                    ).joinToString(", ").let { if (it.isEmpty()) "." else ": $it." } else null,
                stopped.ifBlank { null },
            ).joinToString(" ").ifBlank { "Nothing was sorted." }
            Scanner.note("AI sort: $written written, $toCheck to check ($notSure not sure, $notMatching unlike the file, $noAnswer no answer)" + if (stopped.isNotBlank()) " · $stopped" else "")
            status = ""
        }
    }
}
