package art.kquinn.kuesounds

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Internet radio: stations from Radio Browser's free, open directory of the world's stations
 * (https://www.radio-browser.info). Favourite stations go in KueSounds' own file in the Drive (see [Sync]).
 */
object Radio {
    data class Station(val id: String, val name: String, val url: String, val logo: String, val tags: String, val country: String, val bitrate: Int)

    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    /** The directory's own servers: the next is tried when one doesn't answer. */
    private val servers = listOf("de1", "de2", "fi1", "nl1", "at1")

    private fun get(path: String): String {
        var last: Exception? = null
        for (s in servers) {
            try {
                val req = Request.Builder().url("https://$s.api.radio-browser.info$path").header("User-Agent", "KueSounds/1.0").build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body.string()
                    last = java.io.IOException("HTTP " + r.code)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("no answer")
    }

    private fun parse(json: String): List<Station> = JsonParser.parseString(json).asJsonArray.mapNotNull { e ->
        val o = e.asJsonObject
        val url = (o.get("url_resolved")?.asString).orEmpty().ifEmpty { o.get("url")?.asString.orEmpty() }
        if (url.isEmpty()) return@mapNotNull null
        Station(
            o.get("stationuuid")?.asString.orEmpty(), o.get("name")?.asString.orEmpty().trim(), url, o.get("favicon")?.asString.orEmpty(),
            o.get("tags")?.asString.orEmpty().split(',').take(3).joinToString(", "), o.get("countrycode")?.asString.orEmpty(), o.get("bitrate")?.asInt ?: 0,
        )
    }.distinctBy { it.name.lowercase() + it.country }

    /** The most listened-to stations in a country ("GB", "IE"). Off the main thread. */
    fun country(code: String): List<Station> = parse(get("/json/stations/bycountrycodeexact/$code?hidebroken=true&order=clickcount&reverse=true&limit=80"))

    /** Stations by name, or by a kind of music ("jazz"). Off the main thread. */
    fun search(words: String): List<Station> {
        val q = URLEncoder.encode(words.trim(), "UTF-8")
        val byName = parse(get("/json/stations/search?name=$q&hidebroken=true&order=clickcount&reverse=true&limit=60"))
        if (byName.size >= 10) return byName
        return (byName + parse(get("/json/stations/search?tag=$q&hidebroken=true&order=clickcount&reverse=true&limit=60"))).distinctBy { it.id }
    }

    /** Tells the directory a station was played, as it asks of the apps that use it. */
    fun counted(s: Station) {
        Thread { runCatching { get("/json/url/" + s.id) } }.start()
    }

    fun favourites(): List<Station> = Sync.entries.filter { it.key.startsWith("radio:") && it.value.value.isNotEmpty() }.toList()
        .sortedBy { it.second.at }.mapNotNull { runCatching { gson.fromJson(it.second.value, Station::class.java) }.getOrNull() }

    fun isFavourite(id: String): Boolean = Sync.get("radio:$id") != null

    fun flipFavourite(s: Station) = Sync.put("radio:" + s.id, if (isFavourite(s.id)) "" else gson.toJson(s))
}

/**
 * YouTube and YouTube Music links, played in YouTube's own player on KueSounds' screen. YouTube's rules allow
 * that, with its player as it is, while it is on screen; they don't allow playing with the screen off.
 */
object YouTube {
    data class Link(val video: String, val list: String, val original: String)
    data class Seen(val url: String, val title: String, val at: Long)

    private val gson = Gson()
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    /** The video and playlist in a YouTube or YouTube Music link, or null when it isn't one. */
    fun read(text: String): Link? {
        val raw = Regex("https?://\\S+").find(text)?.value ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (!host.endsWith("youtube.com") && !host.endsWith("youtu.be")) return null
        val segs = uri.pathSegments
        val video = when {
            host.endsWith("youtu.be") -> segs.firstOrNull()
            segs.firstOrNull() in listOf("shorts", "live", "embed") -> segs.getOrNull(1)
            else -> uri.getQueryParameter("v")
        }.orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val list = uri.getQueryParameter("list").orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (video.isEmpty() && list.isEmpty()) return null
        return Link(video, list, raw)
    }

    /** The video's title, from YouTube. Off the main thread. */
    fun title(link: Link): String = runCatching {
        val req = Request.Builder().url("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(link.original, "UTF-8")).build()
        http.newCall(req).execute().use { r -> JsonParser.parseString(r.body.string()).asJsonObject.get("title").asString }
    }.getOrDefault(if (link.list.isNotEmpty() && link.video.isEmpty()) "YouTube playlist" else "YouTube")

    /** The page YouTube's player runs in. */
    fun page(link: Link): String {
        val vars = buildList {
            add("autoplay=1"); add("playsinline=1"); add("rel=0")
            if (link.list.isNotEmpty()) { add("list=" + link.list); add("listType=playlist") }
        }.joinToString("&")
        val src = "https://www.youtube.com/embed/" + link.video + "?" + vars
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head><body>" +
            "<iframe src=\"$src\" allow=\"autoplay; encrypted-media; picture-in-picture; fullscreen\" allowfullscreen referrerpolicy=\"strict-origin-when-cross-origin\"></iframe>" +
            "</body></html>"
    }

    /** A video found for a song. */
    data class Found(val id: String, val title: String, val channel: String, val artistTick: Boolean)

    /**
     * Finds a song's own video, with no key and nothing to pay (Kevin, 25 November 2026: it opened YouTube's search
     * page, with an advert on top, in place of the group's own video). YouTube's search page is read the way the page
     * itself reads it, and the videos put in order: the artist's own channel first (YouTube's artist tick, the channel
     * named for the artist or its VEVO), then ones called official, with live, cover, karaoke and lyric videos last.
     * Off the main thread.
     */
    fun find(artist: String, title: String): List<Found> {
        val words = listOf(artist.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), Library.withoutBrackets(title)).filter { it.isNotBlank() }.joinToString(" ")
        val req = Request.Builder().url("https://www.youtube.com/results?hl=en&gl=GB&search_query=" + URLEncoder.encode(words, "UTF-8"))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .header("Accept-Language", "en-GB,en;q=0.9")
            // Past the cookie question YouTube asks people in the UK and Europe first.
            .header("Cookie", "SOCS=CAI; CONSENT=YES+1")
            .build()
        val html = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("YouTube answered " + r.code)
            r.body.string()
        }
        return rank(videosIn(html), artist, title)
    }

    /** Every video on a YouTube search page, in YouTube's order. */
    fun videosIn(html: String): List<Found> {
        val at = html.indexOf("ytInitialData")
        if (at < 0) return emptyList()
        val open = html.indexOf('{', at)
        if (open < 0) return emptyList()
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(html.substring(open))).apply { isLenient = true }
        val root = runCatching { JsonParser.parseReader(reader) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Found>()
        fun text(e: com.google.gson.JsonElement?): String {
            val o = e?.takeIf { it.isJsonObject }?.asJsonObject ?: return ""
            o.get("simpleText")?.let { return it.asString }
            return o.getAsJsonArray("runs")?.joinToString("") { r -> r.asJsonObject.get("text")?.asString.orEmpty() }.orEmpty()
        }
        fun walk(e: com.google.gson.JsonElement) {
            if (out.size >= 40) return
            when {
                e.isJsonObject -> for ((k, v) in e.asJsonObject.entrySet()) {
                    if (k == "videoRenderer" && v.isJsonObject) {
                        val o = v.asJsonObject
                        val id = o.get("videoId")?.asString.orEmpty()
                        if (id.isNotEmpty() && out.none { it.id == id }) {
                            val tick = o.getAsJsonArray("ownerBadges")?.any { b ->
                                b.toString().contains("VERIFIED_ARTIST") || b.toString().contains("OFFICIAL_ARTIST")
                            } == true
                            out += Found(id, text(o.get("title")), text(o.get("ownerText")).ifEmpty { text(o.get("longBylineText")) }, tick)
                        }
                    } else walk(v)
                }
                e.isJsonArray -> for (v in e.asJsonArray) walk(v)
            }
        }
        walk(root)
        return out
    }

    /** [found] in the order to try them for [artist]'s [title]. */
    fun rank(found: List<Found>, artist: String, title: String): List<Found> {
        val who = Catalogue.compact(artist)
        val song = Catalogue.compact(Library.withoutBrackets(title))
        fun score(f: Found): Int {
            val channel = Catalogue.compact(f.channel)
            val named = Catalogue.compact(f.title)
            var n = 0
            if (f.artistTick) n += 6
            if (who.isNotEmpty() && (channel == who || channel == who + "vevo" || channel == who + "official" || channel == "official" + who)) n += 5
            else if (who.isNotEmpty() && channel.contains(who)) n += 3
            if (song.isNotEmpty() && named.contains(song)) n += 3
            if (named.contains("officialvideo") || named.contains("officialmusicvideo")) n += 2 else if (named.contains("official")) n += 1
            for (word in listOf("cover", "karaoke", "lyric", "reaction", "tutorial", "remix", "live", "instrumental")) {
                if (named.contains(word) && !song.contains(word)) n -= 3
            }
            return n
        }
        return found.withIndex().sortedWith(compareByDescending<IndexedValue<Found>> { score(it.value) }.thenBy { it.index }).map { it.value }
    }

    /**
     * The page for a found video, with YouTube's own player that says when a video can't be played inside another
     * app: then the next found one is tried, and when none can, [failed] opens it in the YouTube app instead.
     */
    fun pageFor(ids: List<String>): String {
        val list = ids.joinToString(",") { "'" + it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } + "'" }
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#000}#p{width:100%;height:100%}</style></head><body><div id=\"p\"></div>" +
            "<script>var ids=[" + list + "];var i=0;var player;" +
            "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:ids[0]," +
            "playerVars:{autoplay:1,playsinline:1,rel:0,modestbranding:1}," +
            "events:{onReady:function(e){e.target.playVideo();}," +
            "onError:function(e){i++;if(i<ids.length){player.loadVideoById(ids[i]);Kue.trying(ids[i]);}else{Kue.failed(ids[0]);}}}});}" +
            "</script><script src=\"https://www.youtube.com/iframe_api\"></script></body></html>"
    }

    /** The links played lately, newest first, kept in the Drive's file. */
    fun recent(): List<Seen> = Sync.entries.filter { it.key.startsWith("yt:") && it.value.value.isNotEmpty() }.values
        .mapNotNull { runCatching { gson.fromJson(it.value, Seen::class.java) }.getOrNull() }.sortedByDescending { it.at }.take(30)

    fun remember(link: Link, title: String) = Sync.put("yt:" + (link.video + link.list).take(60), gson.toJson(Seen(link.original, title, System.currentTimeMillis())))

    fun forget(url: String) {
        val l = read(url) ?: return
        Sync.put("yt:" + (l.video + l.list).take(60), "")
    }
}

/**
 * Spotify, for anyone with Spotify Premium: KueSounds drives the Spotify app on the same device, which does
 * the playing. Spotify only allows this through a free developer key ("Client ID") made with a Premium
 * account, so each person enters their own in Settings; it stays on that device.
 */
object SpotifyLink {
    const val REDIRECT = "kuesounds://spotify"
    const val SPOTIFY_APP = "com.spotify.music"

    private lateinit var app: Context
    private var remote: com.spotify.android.appremote.api.SpotifyAppRemote? = null

    var connected by mutableStateOf(false)
        private set
    var problem by mutableStateOf("")
        private set
    var track by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var paused by mutableStateOf(true)
        private set
    /** What Spotify offers to play: recently played, your playlists and so on, each with its first few items. */
    var shelves by mutableStateOf<List<Pair<String, List<com.spotify.protocol.types.ListItem>>>>(emptyList())
        private set

    private fun prefs() = app.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun clientId(): String = prefs().getString("clientId", "").orEmpty()
    fun clientId(value: String) = prefs().edit().putString("clientId", value.trim()).apply()

    fun installed(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(SPOTIFY_APP, 0) }.isSuccess

    fun connect(context: Context) {
        init(context)
        val id = clientId()
        if (id.isEmpty()) { problem = "Put in your Spotify Client ID first (below)."; return }
        if (!installed(context)) { problem = "The Spotify app isn't on this device. Install it and sign in, then try again."; return }
        problem = ""
        val params = com.spotify.android.appremote.api.ConnectionParams.Builder(id).setRedirectUri(REDIRECT).showAuthView(true).build()
        com.spotify.android.appremote.api.SpotifyAppRemote.connect(context, params, object : com.spotify.android.appremote.api.Connector.ConnectionListener {
            override fun onConnected(r: com.spotify.android.appremote.api.SpotifyAppRemote) {
                remote = r
                connected = true
                r.playerApi.subscribeToPlayerState().setEventCallback { s ->
                    track = s.track?.name.orEmpty()
                    artist = s.track?.artist?.name.orEmpty()
                    paused = s.isPaused
                    // One thing plays at a time: KueSounds' own player stops when Spotify plays.
                    if (!s.isPaused && Playing.isPlaying) Playing.toggle()
                }
                loadShelves()
            }

            override fun onFailure(error: Throwable) {
                connected = false
                problem = when (error.javaClass.simpleName) {
                    "CouldNotFindSpotifyApp" -> "The Spotify app isn't on this device."
                    "NotLoggedInException" -> "Sign in to the Spotify app first."
                    "UserNotAuthorizedException" -> "Spotify said no: check the Client ID, and that this app's details are added to it (see the steps below)."
                    "SpotifyConnectionTerminatedException" -> "The connection to Spotify closed. Tap Connect again."
                    else -> "Spotify didn't connect: " + (error.message ?: error.javaClass.simpleName)
                }
            }
        })
    }

    fun disconnect() {
        remote?.let { com.spotify.android.appremote.api.SpotifyAppRemote.disconnect(it) }
        remote = null
        connected = false
    }

    private fun loadShelves() {
        val r = remote ?: return
        r.contentApi.getRecommendedContentItems(com.spotify.android.appremote.api.ContentApi.ContentType.DEFAULT).setResultCallback { top ->
            val out = ArrayList<Pair<String, List<com.spotify.protocol.types.ListItem>>>()
            val wanted = top.items.take(8)
            if (wanted.isEmpty()) { shelves = emptyList(); return@setResultCallback }
            var left = wanted.size
            for (item in wanted) {
                if (item.playable && !item.hasChildren) {
                    synchronized(out) { out += item.title to listOf(item) }
                    if (--left == 0) shelves = out.toList()
                    continue
                }
                r.contentApi.getChildrenOfItem(item, 20, 0).setResultCallback { kids ->
                    synchronized(out) { out += item.title to kids.items.toList() }
                    if (--left == 0) shelves = out.sortedBy { s -> wanted.indexOfFirst { it.title == s.first } }
                }.setErrorCallback { if (--left == 0) shelves = out.toList() }
            }
        }.setErrorCallback { problem = "Spotify's lists didn't come: " + it.message }
    }

    fun play(item: com.spotify.protocol.types.ListItem) {
        remote?.contentApi?.playContentItem(item)
    }

    /** A Spotify link ("https://open.spotify.com/playlist/…") played: true when it was one. */
    fun playLink(text: String): Boolean {
        val m = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]+)").find(text)
            ?: Regex("spotify:(track|album|playlist|artist|episode|show):([A-Za-z0-9]+)").find(text) ?: return false
        remote?.playerApi?.play("spotify:" + m.groupValues[1] + ":" + m.groupValues[2])
        return true
    }

    fun toggle() { remote?.playerApi?.let { if (paused) it.resume() else it.pause() } }
    fun next() { remote?.playerApi?.skipNext() }
    fun previous() { remote?.playerApi?.skipPrevious() }
}
