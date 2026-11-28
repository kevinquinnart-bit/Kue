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
 *  - A Google key (free, from aistudio.google.com): Gemini looks each one up on Google Search itself. Google's free tier
 *    includes searching (Gemini 2.5 Flash, checked on Google's pricing page 28 November 2026), so it costs nothing.
 *  - An OpenRouter key: KueSounds searches Apple, Deezer and Discogs itself (free) and a free model (":free", costs
 *    nothing) picks the right one and puts the names right. OpenRouter's own web search is never used: it charges even
 *    on free models.
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
    val hasWork: Boolean get() = synchronized(waiting) { waiting.isNotEmpty() }
    val running: Boolean get() = status.isNotEmpty()

    /** An answer kept for Check one at a time: what was found, and whether it was sure. */
    class Answer(val look: Covers.Lookup?, val sure: Boolean)

    private fun keyOf(item: ReviewUi.Item) = item.songs.first().id + if (item.album != null) "|album" else ""
    private val answers = HashMap<String, Answer>()
    private var loaded = false
    private fun file() = File(app.filesDir, "ai-answers.json")

    private fun load() {
        if (loaded || !::app.isInitialized) return
        loaded = true
        runCatching {
            val o = JsonParser.parseString(file().readText()).asJsonObject
            for ((k, v) in o.entrySet()) {
                val a = v.asJsonObject
                answers[k] = Answer(a.getAsJsonObject("look")?.let { lookFrom(it) }, a.get("sure")?.asBoolean == true)
            }
        }
    }

    private fun saveAnswers() {
        val o = JsonObject()
        for ((k, a) in answers) o.add(k, JsonObject().apply { a.look?.let { add("look", lookTo(it)) }; addProperty("sure", a.sure) })
        runCatching { file().writeText(o.toString()) }
    }

    private fun lookTo(l: Covers.Lookup) = JsonObject().apply {
        addProperty("title", l.title); addProperty("artist", l.artist); addProperty("album", l.album); addProperty("albumArtist", l.albumArtist)
        addProperty("year", l.year); addProperty("track", l.trackNo); addProperty("disc", l.discNo); addProperty("genre", l.genre)
        addProperty("small", l.small); addProperty("big", l.big); addProperty("from", l.from)
    }

    private fun lookFrom(o: JsonObject): Covers.Lookup {
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return Covers.Lookup(s("title"), s("artist"), s("album"), s("albumArtist"), s("year"), s("track").toIntOrNull() ?: 0, s("disc").toIntOrNull() ?: 0,
            s("genre"), s("small"), s("big"), s("from"))
    }

    /** The answer kept for [item], for Check one at a time. */
    fun answerFor(item: ReviewUi.Item): Covers.Lookup? = synchronized(answers) { load(); answers[keyOf(item)]?.look }

    /** How many of [items] haven't been asked yet. */
    fun notAsked(items: List<ReviewUi.Item>): Int = synchronized(answers) { load(); items.count { keyOf(it) !in answers } }

    /** Starts sorting [items] (those not asked before). Needs the edit permission, for writing the sure ones. */
    fun queue(context: Context, items: List<ReviewUi.Item>) {
        init(context)
        val fresh = synchronized(answers) { load(); items.filter { keyOf(it) !in answers } }
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
        if (found == null) appendLine("Search Google for it (Wikipedia, Discogs, AllMusic and the like) before answering.")
        appendLine()
        append(facts)
        if (found != null) {
            appendLine()
            appendLine("Records found for it in Apple Music, Deezer and Discogs (choose by number in \"pick\", or 0 if none is it):")
            found.forEachIndexed { i, f ->
                appendLine("${i + 1}. " + listOf(f.title, f.artist, f.album, f.year, f.genre, f.from).filter { it.isNotBlank() }.joinToString(" | "))
            }
        }
        appendLine()
        appendLine("Answer with one JSON object only, no other words, in this shape:")
        appendLine(SHAPE)
        appendLine("Rules:")
        if (item.album == null) {
            appendLine("- title and artist: the song's real name and the artist's real name, spelt as the artist does, with accents (Sinéad, not Sinead).")
            appendLine("- album: the album or single the song first came out on, not a compilation, unless the file name says otherwise. track and disc: its place on that album, 0 if not known.")
        } else {
            appendLine("- title: leave empty. album, albumArtist and artist: the album's real name and the artist's real name, spelt as they are, with accents.")
        }
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
            title = if (album) "" else s("title"), artist = s("artist"), album = s("album"), albumArtist = s("albumArtist").ifBlank { s("artist") },
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

    private var googleModel = "gemini-2.5-flash"

    /**
     * One ask to Google: the key in the header, else (if that is turned away) in the address, which some new "AQ." keys
     * need; the lighter model if the usual one isn't offered.
     */
    private fun google(body: String, k: String): Pair<Int, String> {
        fun once(): Pair<Int, String> {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$googleModel:generateContent"
            val first = post(url, body, "x-goog-api-key" to k)
            if (first.first != 401 && first.first != 403) return first
            val second = post(url + "?key=" + java.net.URLEncoder.encode(k, "UTF-8"), body, "X-Kue" to "1")
            return if (second.first in 200..299 || second.first == 429) second else first
        }
        var r = once()
        if (r.first == 404 && googleModel == "gemini-2.5-flash") { googleModel = "gemini-2.5-flash-lite"; r = once() }
        return r
    }

    /** Google's (or OpenRouter's) own words for what went wrong, from its reply. */
    fun saidWhy(reply: String): String = runCatching {
        val o = JsonParser.parseString(reply)
        val e = if (o.isJsonArray) o.asJsonArray.first().asJsonObject.getAsJsonObject("error") else o.asJsonObject.getAsJsonObject("error")
        e.get("message").asString.lineSequence().first().take(200)
    }.getOrDefault("")

    /**
     * Tries a key with one small ask, before it is kept: null when it works, else why not, in the service's own words.
     * Off the main thread.
     */
    fun test(k: String): String? = runCatching {
        when (whose(k)) {
            "Google" -> {
                val body = JsonObject().apply {
                    add("contents", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { add("parts", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("text", "Reply with the word OK.") }) }) })
                    })
                }.toString()
                val (code, reply) = google(body, k)
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
    private var freeModel: String? = null

    /** Asks the AI about [item]. Throws Stop when it should not carry on (key refused, the day's allowance used). Off the main thread. */
    fun ask(item: ReviewUi.Item, now: Map<FieldKey, String>? = null, trying: (String) -> Unit = {}): Said? {
        val k = key
        val facts = facts(item, now)
        when (whose(k)) {
            "Google" -> {
                trying("Asking Google")
                val body = JsonObject().apply {
                    add("contents", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply { add("parts", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("text", question(item, facts, null)) }) }) })
                    })
                    add("tools", com.google.gson.JsonArray().apply { add(JsonObject().apply { add("google_search", JsonObject()) }) })
                }.toString()
                val (code, reply) = google(body, k)
                checkReply(code, reply, "Google")
                val o = jsonIn(geminiText(reply)) ?: return null
                return said(o, item.album != null, "AI, Google search")
            }
            "OpenRouter" -> {
                // KueSounds searches (free); the free model only chooses and tidies.
                trying("Looking it up")
                val t = item.songs.first()
                val artist = (now?.get(FieldKey.ARTIST) ?: t.artist).takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty()
                val title = Library.withoutBrackets(now?.get(FieldKey.TITLE) ?: t.title)
                val words = (if (item.album == null) "$artist $title" else artist + " " + item.album).trim()
                val found = runCatching { Covers.lookUp(words, item.album == null, if (item.album == null) title else null) }.getOrDefault(emptyList()).filter { !it.coverOnly }.take(8)
                trying("Asking the AI")
                val model = freeModel ?: run {
                    val list = runCatching { http.newCall(Request.Builder().url("https://openrouter.ai/api/v1/models").header("User-Agent", Catalogue.WHO).build()).execute().use { it.body.string() } }.getOrNull()
                    pickFree(list ?: "") ?: throw Stop("OpenRouter lists no free models just now.")
                }.also { freeModel = it }
                val body = JsonObject().apply {
                    addProperty("model", model)
                    add("messages", com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", question(item, facts, found)) }) })
                }.toString()
                val (code, reply) = post("https://openrouter.ai/api/v1/chat/completions", body, "Authorization" to "Bearer $k")
                if (code == 404 || code == 400 && "model" in reply.lowercase()) freeModel = null
                checkReply(code, reply, "OpenRouter")
                val o = jsonIn(openRouterText(reply)) ?: return null
                val s = said(o, item.album != null, "AI")
                val chosen = found.getOrNull(s.pick - 1)
                return if (chosen != null) Said(s.look.copy(small = chosen.small, big = chosen.big, from = "AI, from " + chosen.from), s.sure, s.links, s.pick)
                else Said(s.look.copy(from = "AI"), s.sure, s.links, s.pick)
            }
            else -> return null
        }
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
            sameName && (artist == null || Covers.sameArtist(who, artist) || Library.sortName(Library.realName(t)).contains(Library.sortName(who)))
        } else {
            l.album.isNotBlank() && (Covers.same(l.album, item.album) || artist != null && Covers.sameArtist(who, artist)) && (artist == null || Covers.sameArtist(who, artist))
        }
    }

    // ------------------------------------------------------------------------------------------------------------------
    // The run, in the work service, screen off or on.

    suspend fun run(context: Context, show: (String) -> Unit) {
        init(context)
        load()
        var written = 0
        var toCheck = 0
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
                var look = s?.look
                if (s != null && (s.look.artist.isNotBlank() || s.look.album.isNotBlank())) {
                    val (cover, address) = runCatching { coverFor(s, item.album != null) }.getOrDefault(null to "")
                    look = s.look.copy(small = address.ifBlank { s.look.small }, big = address.ifBlank { s.look.big })
                    if (s.sure == "high" && agrees(item, s.look) && Google.canEdit) {
                        val set = LinkedHashMap<FieldKey, String>()
                        fun put(k: FieldKey, v: String) { if (v.isNotBlank()) set[k] = v }
                        if (item.album == null) {
                            put(FieldKey.TITLE, s.look.title); put(FieldKey.ARTIST, s.look.artist)
                            if (s.look.trackNo > 0) put(FieldKey.TRACK, s.look.trackNo.toString())
                            if (s.look.discNo > 0) put(FieldKey.DISC_NO, s.look.discNo.toString())
                        }
                        put(FieldKey.ALBUM, s.look.album); put(FieldKey.ALBUM_ARTIST, s.look.albumArtist); put(FieldKey.YEAR, s.look.year); put(FieldKey.GENRE, s.look.genre)
                        val jpeg = cover?.let { FixByHandUi.asJpeg(it) }
                        val result = runCatching { Fixer.saveByHand(context, item.songs, set, emptySet(), jpeg) }.getOrDefault("")
                        if (result.startsWith("Saved into the file") || result.startsWith("Saved into all")) {
                            written++
                            Scanner.note("AI sort wrote: " + (item.album ?: t.fileName) + " → " + s.look.artist + " / " + (s.look.title.ifBlank { s.look.album }) + (if (jpeg == null) " (no cover found)" else ""))
                        } else {
                            toCheck++
                            Scanner.note("AI sort: not written: " + (item.album ?: t.fileName) + ": " + result)
                        }
                    } else toCheck++
                } else toCheck++
                synchronized(answers) { answers[keyOf(item)] = Answer(look, s?.sure == "high"); saveAnswers() }
                done++
                kotlinx.coroutines.delay(gap)
            }
        } finally {
            lastResult = listOfNotNull(
                if (written > 0 || toCheck > 0) "Sorted $done of $total: $written written into the files, $toCheck left for Check one at a time, with the AI's answer filled in." else null,
                stopped.ifBlank { null },
            ).joinToString(" ").ifBlank { "Nothing was sorted." }
            Scanner.note("AI sort: $written written, $toCheck to check" + if (stopped.isNotBlank()) " · $stopped" else "")
            status = ""
        }
    }
}
