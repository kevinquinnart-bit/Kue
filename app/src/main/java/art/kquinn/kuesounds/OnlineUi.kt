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
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) K.Accent else K.Panel).clickable(onClick = onClick).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Ui.Words(label, 15, if (on) K.Page else K.Ink, bold = on) }
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
        var showing by rememberSaveable { mutableStateOf("GB") } // GB, IE, FAV or SEARCH
        var words by rememberSaveable { mutableStateOf("") }
        var list by remember { mutableStateOf<List<Radio.Station>?>(null) }
        var problem by remember { mutableStateOf("") }
        val favs = remember(Sync.entries) { Radio.favourites() }
        LaunchedEffect(showing) {
            if (showing == "FAV" || showing == "SEARCH") return@LaunchedEffect
            list = null
            problem = ""
            list = withContext(Dispatchers.IO) { runCatching { Radio.country(showing) }.getOrNull() }
            if (list == null) problem = "The radio directory didn't answer. Check the internet and try again."
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
                    if (words.isNotBlank()) item { Chip("Search", showing == "SEARCH", onClick = find) }
                }
            }
            if (problem.isNotEmpty()) item { Ui.Words(problem, 15, K.Accent, lines = 3) }
            when {
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
    fun YouTubePlayer(url: String, back: () -> Unit) {
        val link = remember(url) { YouTube.read(url) }
        var title by remember { mutableStateOf("") }
        LaunchedEffect(url) {
            Playing.pause()
            if (SpotifyLink.connected && !SpotifyLink.paused) SpotifyLink.toggle()
            if (link != null) {
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
                            // A web address of KueSounds' own as the page's home, so YouTube sees where its player is shown.
                            loadDataWithBaseURL("https://kevinquinnart-bit.github.io/kuesounds/", YouTube.page(link), "text/html", "UTF-8", null)
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
        var steps by remember { mutableStateOf(SpotifyLink.clientId().isEmpty()) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Ui.Words(
                    "With Spotify Premium, KueSounds can play your Spotify through the Spotify app on this device.", 15, K.Soft, lines = 3,
                )
            }
            if (SpotifyLink.problem.isNotEmpty()) item { Ui.Words(SpotifyLink.problem, 15, K.Accent, lines = 4) }
            if (!SpotifyLink.connected) {
                item { Ui.Button("Connect to Spotify", modifier = Modifier.fillMaxWidth()) { SpotifyLink.clientId(typed); SpotifyLink.connect(context) } }
            } else {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Raised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Ui.Words(if (SpotifyLink.track.isEmpty()) "Nothing playing in Spotify" else SpotifyLink.track, 17, bold = true, lines = 2)
                        if (SpotifyLink.artist.isNotEmpty()) Ui.Words(SpotifyLink.artist, 14, K.Soft)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(CircleShape).clickable { SpotifyLink.previous() }, contentAlignment = Alignment.Center) { Sign(Glyph.PREVIOUS, 26.dp, K.Ink) }
                            Box(Modifier.size(64.dp).clip(CircleShape).background(K.Accent).clickable { SpotifyLink.toggle() }, contentAlignment = Alignment.Center) {
                                Sign(if (SpotifyLink.paused) Glyph.PLAY else Glyph.PAUSE, 28.dp, K.Page)
                            }
                            Box(Modifier.size(52.dp).clip(CircleShape).clickable { SpotifyLink.next() }, contentAlignment = Alignment.Center) { Sign(Glyph.NEXT, 26.dp, K.Ink) }
                        }
                    }
                }
                item {
                    Field("Or paste a Spotify link", link, onDone = { if (SpotifyLink.playLink(link)) link = "" }) { link = it }
                }
                for ((name, list) in SpotifyLink.shelves) {
                    item { Ui.Words(name, 18, bold = true, modifier = Modifier.padding(top = 6.dp)) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(list, key = { it.id }) { it ->
                                Column(Modifier.width(130.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { SpotifyLink.play(it) }.padding(10.dp)) {
                                    Ui.Words(it.title, 14, bold = true, lines = 2)
                                    Ui.Words(it.subtitle.orEmpty(), 12, K.Soft, lines = 2)
                                }
                            }
                        }
                    }
                }
                item { Ui.Button("Disconnect", main = false, modifier = Modifier.fillMaxWidth()) { SpotifyLink.disconnect() } }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.clickable { steps = !steps }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.rotate(if (steps) 90f else 0f)) { KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Ink, 2.2f) }
                        Spacer(Modifier.width(6.dp))
                        Ui.Words("Your Spotify Client ID", 16, bold = true)
                    }
                    Field("Client ID", typed) { typed = it; SpotifyLink.clientId(it) }
                    if (steps) Ui.Words(
                        "Spotify only lets apps like KueSounds in with a free developer key, made once with a Premium account:\n" +
                            "1. On a computer, go to developer.spotify.com/dashboard and sign in with your Premium account.\n" +
                            "2. Create app. Any name and description. Tick \"Android\".\n" +
                            "3. Redirect URI: " + SpotifyLink.REDIRECT + "\n" +
                            "4. Android package: art.kquinn.kuesounds\n" +
                            "5. Package SHA1 fingerprint: 17:05:AF:FC:99:35:B9:25:31:9E:4C:10:54:85:A8:99:C9:69:29:2B\n" +
                            "6. Save, then copy the Client ID into the box above and tap Connect.",
                        14, K.Soft, lines = 20,
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
