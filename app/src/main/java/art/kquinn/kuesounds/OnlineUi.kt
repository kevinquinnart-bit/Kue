package art.kquinn.kuesounds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Radio, YouTube and Spotify tabs, and YouTube's player screen. */
object OnlineUi {

    @Composable
    private fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
        // A second row of choices, under Radio, Spotify or YouTube: the Kue standard (Kevin, 25 November 2026).
        KueSettings.SubChip(label, on, Ui.look, onClick = onClick)
    }

    @Composable
    private fun Field(hint: String, value: String, onDone: () -> Unit = {}, onChange: (String) -> Unit) {
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Ui.Words(hint, 16, K.Soft)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = K.Ink, fontSize = 16.sp, fontFamily = K.Poppins),
                cursorBrush = SolidColor(K.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun Logo(url: String, size: Int) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
            if (url.startsWith("http")) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Sign(Glyph.NOTE, (size / 2).dp, K.Soft)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Radio

    @Composable
    fun RadioTab() {
        val scope = rememberCoroutineScope()
        // GB, IE, MUSIC, TALK, SPORT, PLACES, LANGUAGES, FAV, SEARCH; a pick: "C:FR" (a country), "L:irish" (a
        // language), "T:jazz" (a kind). CloudPlayer's Magic Radio as the least (Kevin, 25 November 2026).
        var showing by rememberSaveable { mutableStateOf("GB") }
        var groups by remember { mutableStateOf<List<Radio.Group>?>(null) }
        var words by rememberSaveable { mutableStateOf("") }
        var list by remember { mutableStateOf<List<Radio.Station>?>(null) }
        var problem by remember { mutableStateOf("") }
        val favs = remember(Sync.entries) { Radio.favourites() }
        LaunchedEffect(showing) {
            if (showing == "FAV" || showing == "SEARCH" || showing == "MUSIC") return@LaunchedEffect
            list = null
            groups = null
            problem = ""
            val got: Any? = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        showing == "PLACES" -> Radio.countries()
                        showing == "LANGUAGES" -> Radio.languages()
                        showing == "TALK" -> (Radio.byTag("talk", "GB") + Radio.byTag("news", "GB")).distinctBy { it.id }
                        showing == "SPORT" -> Radio.byTag("sport", "GB").ifEmpty { Radio.byTag("sport") }
                        showing.startsWith("C:") -> Radio.country(showing.removePrefix("C:"))
                        showing.startsWith("L:") -> Radio.byLanguage(showing.removePrefix("L:"))
                        showing.startsWith("T:") -> Radio.byTag(showing.removePrefix("T:"))
                        else -> Radio.country(showing)
                    }
                }.getOrNull()
            }
            @Suppress("UNCHECKED_CAST")
            when {
                got == null -> problem = "The radio directory didn't answer. Check the internet and try again."
                showing == "PLACES" || showing == "LANGUAGES" -> groups = got as List<Radio.Group>
                else -> list = got as List<Radio.Station>
            }
        }
        val find: () -> Unit = {
            if (words.isNotBlank()) {
                showing = "SEARCH"
                list = null
                problem = ""
                scope.launch {
                    list = withContext(Dispatchers.IO) { runCatching { Radio.search(words) }.getOrNull() }
                    if (list == null) problem = "The radio directory didn't answer. Check the internet and try again."
                }
            }
        }
        val shown = if (showing == "FAV") favs else list
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Field("A station's name, or a kind of music", words, onDone = find) { words = it } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("UK", showing == "GB") { showing = "GB" } }
                    item { Chip("Ireland", showing == "IE") { showing = "IE" } }
                    item { Chip("Favourites", showing == "FAV") { showing = "FAV" } }
                    item { Chip("Music", showing == "MUSIC" || showing.startsWith("T:")) { showing = "MUSIC" } }
                    item { Chip("Talk and news", showing == "TALK") { showing = "TALK" } }
                    item { Chip("Sport", showing == "SPORT") { showing = "SPORT" } }
                    item { Chip("By country", showing == "PLACES" || showing.startsWith("C:")) { showing = "PLACES" } }
                    item { Chip("By language", showing == "LANGUAGES" || showing.startsWith("L:")) { showing = "LANGUAGES" } }
                    if (words.isNotBlank()) item { Chip("Search", showing == "SEARCH", onClick = find) }
                }
            }
            if (problem.isNotEmpty()) item { Ui.Words(problem, 15, K.Accent, lines = 3) }
            // Kinds of music to pick from.
            if (showing == "MUSIC" || showing.startsWith("T:")) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("pop", "rock", "dance", "80s", "90s", "country", "jazz", "classical", "irish", "christmas", "chillout", "hip hop")) { k ->
                        Chip(k.replaceFirstChar { it.uppercaseChar() }, showing == "T:$k") { showing = "T:$k" }
                    }
                }
            }
            val g = groups
            when {
                showing == "MUSIC" -> item { Ui.Words("Pick a kind of music.", 16, K.Soft) }
                showing == "PLACES" || showing == "LANGUAGES" -> when {
                    g == null && problem.isEmpty() -> item { CircularProgressIndicator(color = K.Accent) }
                    g != null -> items(g, key = { "g:" + it.code }) { x ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel)
                                .clickable { showing = (if (showing == "PLACES") "C:" else "L:") + x.code }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ui.Words(x.name, 16, bold = true, modifier = Modifier.weight(1f))
                            Ui.Words(String.format("%,d", x.stations) + " stations", 13, K.Soft)
                        }
                    }
                }
                shown == null -> item { CircularProgressIndicator(color = K.Accent) }
                shown.isEmpty() -> item { Ui.Words(if (showing == "FAV") "Tap the star on a station to keep it here." else "No stations found.", 16, K.Soft, lines = 2) }
                else -> items(shown, key = { "r:" + it.id }) { s -> StationRow(s) }
            }
            item {
                Ui.Words("Stations from Radio Browser, the free directory of the world's radio (radio-browser.info).", 13, K.Soft, lines = 2)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    @Composable
    private fun StationRow(s: Radio.Station) {
        val on = Playing.currentId == "radio:" + s.id
        val fav = remember(Sync.entries, s.id) { Radio.isFavourite(s.id) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) K.Raised else K.Panel)
                .clickable { if (on) Playing.toggle() else Playing.playRadio(s) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(s.logo, 52)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Ui.Words(s.name, 16, if (on) K.Accent else K.Ink, bold = true)
                Ui.Words(listOf(s.tags, if (s.bitrate > 0) s.bitrate.toString() + " kbps" else "").filter { it.isNotBlank() }.joinToString(" · "), 13, K.Soft)
            }
            Box(Modifier.size(44.dp).clickable(onClickLabel = if (fav) "Take out of favourites" else "Add to favourites") { Radio.flipFavourite(s) }, contentAlignment = Alignment.Center) {
                KueSettings.Icon(KueSettings.Icons.STAR, 24.dp, if (fav) K.Accent else K.Soft, filled = fav)
            }
        }
    }

    /** The playing screen's buttons for radio: it's live, so only play and stop. */
    @Composable
    fun RadioControls() {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KueSettings.Icon(KueSettings.Icons.DOT, 12.dp, K.Accent, filled = true)
                Spacer(Modifier.width(6.dp))
                Ui.Words("Live radio", 15, K.Accent, bold = true)
            }
            Box(
                Modifier.size(80.dp).clip(CircleShape).background(K.Accent).clickable(onClickLabel = if (Playing.isPlaying) "Stop" else "Play") { Playing.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (Playing.buffering) CircularProgressIndicator(color = K.Page, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                else Sign(if (Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 34.dp, K.Page)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // YouTube

    @Composable
    fun YouTubeTab(go: (Screen) -> Unit) {
        var typed by rememberSaveable { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val recent = remember(Sync.entries) { YouTube.recent() }
        val open: () -> Unit = {
            if (YouTube.read(typed) != null) { go(Screen.YouTubePlayer(typed.trim())); typed = "" }
            else note = "That isn't a YouTube or YouTube Music link."
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Field("Paste a YouTube or YouTube Music link", typed, onDone = open) { typed = it; note = "" } }
            item { Ui.Button("Play it", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth(), onClick = open) }
            if (note.isNotEmpty()) item { Ui.Words(note, 15, K.Accent) }
            item {
                Ui.Words(
                    "Or in YouTube or YouTube Music, tap Share, then KueSounds. It plays in YouTube's own player while KueSounds is on screen; " +
                        "YouTube doesn't allow it to carry on with the screen off.",
                    14, K.Soft, lines = 5,
                )
            }
            if (recent.isNotEmpty()) item { Ui.Words("Played lately", 19, bold = true, modifier = Modifier.padding(top = 8.dp)) }
            items(recent, key = { "y:" + it.url }) { seen ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { go(Screen.YouTubePlayer(seen.url)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Sign(Glyph.PLAY, 22.dp, K.Accent)
                    Spacer(Modifier.width(12.dp))
                    Ui.Words(seen.title, 15, lines = 2, modifier = Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clickable(onClickLabel = "Take off the list") { YouTube.forget(seen.url) }, contentAlignment = Alignment.Center) {
                        Sign(Glyph.CLOSE, 16.dp, K.Soft)
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    /** YouTube's own player, on KueSounds' screen. It stops when this screen closes. */
    @Composable
    fun YouTubePlayer(url: String, back: () -> Unit, tries: List<String> = emptyList(), name: String = "") {
        val link = remember(url) { YouTube.read(url) }
        var title by remember { mutableStateOf(name) }
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(url) {
            // KueSounds' own sound stops while the video plays (Kevin, 25 November 2026).
            Playing.pause()
            if (SpotifyLink.connected && !SpotifyLink.paused) SpotifyLink.toggle()
            if (link != null && name.isEmpty()) {
                title = withContext(Dispatchers.IO) { YouTube.title(link) }
                YouTube.remember(link, title)
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                Spacer(Modifier.width(12.dp))
                Ui.Words(title, 17, bold = true, lines = 2, modifier = Modifier.weight(1f))
            }
            if (link == null) Ui.Words("That isn't a YouTube link.", 16, K.Soft)
            else {
                var view by remember { mutableStateOf<android.webkit.WebView?>(null) }
                DisposableEffect(Unit) { onDispose { view?.apply { loadUrl("about:blank"); destroy() } } }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = android.webkit.WebChromeClient()
                            setBackgroundColor(android.graphics.Color.BLACK)
                            // A song's found videos: the next is tried when one won't play inside another app, and the
                            // YouTube app opens it when none will.
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface fun trying(id: String) { Scanner.note("YouTube: video wouldn't play here, trying $id") }
                                @android.webkit.JavascriptInterface fun failed(id: String) {
                                    main.post {
                                        Scanner.note("YouTube: none of the found videos would play here; opened in the YouTube app")
                                        val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(app) }.onFailure { runCatching { context.startActivity(web) } }
                                        back()
                                    }
                                }
                            }, "Kue")
                            // A web address of KueSounds' own as the page's home, so YouTube sees where its player is shown.
                            loadDataWithBaseURL("https://kevinquinnart-bit.github.io/kuesounds/", if (tries.isNotEmpty()) YouTube.pageFor(tries) else YouTube.page(link), "text/html", "UTF-8", null)
                            view = this
                        }
                    },
                )
                Ui.Words("Playing in YouTube's own player. It stops when you leave this screen.", 14, K.Soft, lines = 3)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Spotify

    @Composable
    fun SpotifyTab() {
        val context = LocalContext.current
        var typed by rememberSaveable { mutableStateOf(SpotifyLink.clientId()) }
        var link by rememberSaveable { mutableStateOf("") }
        var find by rememberSaveable { mutableStateOf("") }
        var steps by remember { mutableStateOf(SpotifyLink.clientId().isEmpty()) }
        // A playlist, album or shelf opened, with what is in it (Kevin, 26 November 2026: as easy as Spotify's own).
        var opened by remember { mutableStateOf<com.spotify.protocol.types.ListItem?>(null) }
        var inside by remember { mutableStateOf<List<com.spotify.protocol.types.ListItem>?>(null) }
        LaunchedEffect(opened) {
            inside = null
            opened?.let { o -> SpotifyLink.open(o) { inside = it } }
        }
        androidx.activity.compose.BackHandler(enabled = opened != null) { opened = null }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (SpotifyLink.problem.isNotEmpty()) item { Ui.Words(SpotifyLink.problem, 15, K.Accent, lines = 4) }
            if (!SpotifyLink.connected) {
                item { Ui.Words("KueSounds plays your Spotify through the Spotify app on this phone.", 15, K.Soft, lines = 3) }
                item { Ui.Button("Connect to Spotify", modifier = Modifier.fillMaxWidth()) { SpotifyLink.clientId(typed); SpotifyLink.connect(context) } }
                // Anyone, connected or not, Free or Premium: the search opens in the Spotify app (26 November 2026).
                item {
                    Field("Find in Spotify, no connecting", find, onDone = { if (find.isNotBlank()) SpotifyLink.openSearch(context, find) }) { find = it }
                }
                if (find.isNotBlank()) item { Ui.Button("Open in Spotify", main = false, icon = KueSettings.Icons.SEARCH, modifier = Modifier.fillMaxWidth()) { SpotifyLink.openSearch(context, find) } }
            } else {
                // What is playing, with its cover.
                item { SpotifyNowPlaying() }
                if (!SpotifyLink.canChoose) item {
                    Ui.Words("Spotify Free: playlists and albums play shuffled, with Spotify's adverts. Premium plays any song you pick.", 13, K.Soft, lines = 3)
                }
                val o = opened
                if (o != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ui.RoundButton(Glyph.BACK, "Back") { opened = null }
                            Spacer(Modifier.width(12.dp))
                            Ui.Words(o.title, 20, bold = true, lines = 2, modifier = Modifier.weight(1f))
                        }
                    }
                    if (o.playable) item { Ui.Button(if (SpotifyLink.canChoose) "Play" else "Shuffle play", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { SpotifyLink.play(o) } }
                    val kids = inside
                    when {
                        kids == null -> item { CircularProgressIndicator(color = K.Accent) }
                        kids.isEmpty() -> item { Ui.Words("Nothing in here that Spotify lets other apps show.", 15, K.Soft, lines = 2) }
                        else -> items(kids, key = { "k:" + it.id }) { k -> SpotifyRow(k) { if (k.hasChildren) opened = k else SpotifyLink.play(k) } }
                    }
                } else {
                    item { Field("Or paste a Spotify link", link, onDone = { if (SpotifyLink.playLink(link)) link = "" }) { link = it } }
                    for ((name, list) in SpotifyLink.shelves) {
                        item { Ui.Words(name, 20, bold = true, modifier = Modifier.padding(top = 6.dp)) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(list, key = { it.id }) { it -> SpotifyTile(it) { if (it.hasChildren) opened = it else SpotifyLink.play(it) } }
                            }
                        }
                    }
                    item { Ui.Button("Disconnect", main = false, modifier = Modifier.fillMaxWidth()) { SpotifyLink.disconnect() } }
                }
            }
            if (opened == null) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.clickable { steps = !steps }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.rotate(if (steps) 90f else 0f)) { KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Ink, 2.2f) }
                        Spacer(Modifier.width(6.dp))
                        Ui.Words("Spotify setup", 16, bold = true)
                    }
                    if (steps) {
                        Field("Client ID", typed) { typed = it; SpotifyLink.clientId(it) }
                        Ui.Words(
                            "Spotify lets KueSounds in with a free developer key, made once on developer.spotify.com/dashboard:\n" +
                                "1. Create app, tick Android.\n" +
                                "2. Redirect URI: " + SpotifyLink.REDIRECT + "\n" +
                                "3. Android package: art.kquinn.kuesounds\n" +
                                "4. SHA1: 17:05:AF:FC:99:35:B9:25:31:9E:4C:10:54:85:A8:99:C9:69:29:2B\n" +
                                "5. Save, put the Client ID above, tap Connect.\n" +
                                "Someone else using this key: add their Spotify email under User Management on the same page (Spotify allows 5 people). " +
                                "Spotify Free works too, shuffled with adverts. Anyone else can use Find in Spotify above, with no connecting.",
                            14, K.Soft, lines = 24,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    /** The song Spotify is playing: its cover big, its name and artist, and the buttons. */
    @Composable
    private fun SpotifyNowPlaying() {
        val pic = SpotifyLink.playingImage?.raw?.let { SpotifyLink.pictures[it] }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Raised).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                if (pic != null) androidx.compose.foundation.Image(pic.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Sign(Glyph.NOTE, 40.dp, K.Soft)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Ui.Words(SpotifyLink.track.ifEmpty { "Nothing playing in Spotify" }, 17, bold = true, lines = 2)
                if (SpotifyLink.artist.isNotEmpty()) Ui.Words(SpotifyLink.artist, 14, K.Soft)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { SpotifyLink.previous() }, contentAlignment = Alignment.Center) { Sign(Glyph.PREVIOUS, 22.dp, K.Ink) }
                    Box(Modifier.size(52.dp).clip(CircleShape).background(K.Accent).clickable { SpotifyLink.toggle() }, contentAlignment = Alignment.Center) {
                        Sign(if (SpotifyLink.paused) Glyph.PLAY else Glyph.PAUSE, 24.dp, K.Page)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable { SpotifyLink.next() }, contentAlignment = Alignment.Center) { Sign(Glyph.NEXT, 22.dp, K.Ink) }
                    // Brings the playing to this phone when another of the account's devices has it.
                    Box(Modifier.size(44.dp).clip(CircleShape).background(K.Panel).clickable(onClickLabel = "Play on this phone") { SpotifyLink.playHere() }, contentAlignment = Alignment.Center) {
                        KueSettings.Icon(KueSettings.Icons.PHONE, 20.dp, K.Ink, 2f)
                    }
                }
            }
        }
    }

    /** A Spotify playlist, album or mix: its cover, name and the line under it. */
    @Composable
    private fun SpotifyTile(item: com.spotify.protocol.types.ListItem, onClick: () -> Unit) {
        LaunchedEffect(item.id) { SpotifyLink.picture(item.imageUri) }
        val pic = item.imageUri?.raw?.let { SpotifyLink.pictures[it] }
        Column(Modifier.width(150.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                if (pic != null) androidx.compose.foundation.Image(pic.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Sign(Glyph.DISC, 48.dp, K.Soft)
            }
            Ui.Words(item.title, 14, bold = true, lines = 2)
            if (!item.subtitle.isNullOrBlank()) Ui.Words(item.subtitle.orEmpty(), 12, K.Soft, lines = 2)
        }
    }

    /** One song or item inside a playlist or album, as a row with its picture. */
    @Composable
    private fun SpotifyRow(item: com.spotify.protocol.types.ListItem, onClick: () -> Unit) {
        LaunchedEffect(item.id) { SpotifyLink.picture(item.imageUri) }
        val pic = item.imageUri?.raw?.let { SpotifyLink.pictures[it] }
        val on = SpotifyLink.track.isNotEmpty() && Ui.noEmoji(item.title) == Ui.noEmoji(SpotifyLink.track)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) K.Raised else K.Panel).clickable(onClick = onClick).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(K.Raised), contentAlignment = Alignment.Center) {
                if (pic != null) androidx.compose.foundation.Image(pic.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Sign(Glyph.NOTE, 24.dp, K.Soft)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Ui.Words(item.title, 15, if (on) K.Accent else K.Ink, bold = true)
                if (!item.subtitle.isNullOrBlank()) Ui.Words(item.subtitle.orEmpty(), 13, K.Soft)
            }
            if (item.hasChildren) KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Soft, 2.2f)
        }
    }
}
