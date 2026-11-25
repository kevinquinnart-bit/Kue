package art.kquinn.kuesounds

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** KueSounds' colours: KueTV's dark blue page, deeper blue boxes, and its yellow. */
object K {
    val Page = Color(0xFF0E1626)
    val Panel = Color(0xFF16213A)
    val Raised = Color(0xFF263A63)
    val Accent = Color(0xFFFFD23F)
    val Ink = Color.White
    /** Soft grey text; nearly white with Stronger contrast on (Settings › Look and feel). */
    val Soft: Color get() = if (KueSettings.contrast) Color(0xFFE8EDF5) else Color(0xFFB9C2D3)
    /** The light blue of the Kue K: the second word of every Kue app's name ("TV", "Sounds"). */
    val KueBlue = Color(0xFF5CCEFF)
    val Poppins = FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_bold, FontWeight.Bold),
        Font(R.font.poppins_bold, FontWeight.SemiBold),
    )
}

/** The screens a person can be on, newest last. */
sealed class Screen {
    data object Home : Screen()
    data class AlbumPage(val key: String) : Screen()
    data class ArtistPage(val name: String) : Screen()
    data class FolderPage(val id: String?, val name: String) : Screen()
    data object NowPlaying : Screen()
    data object Settings : Screen()
    data object PodcastFind : Screen()
    data class ShowPage(val feed: String) : Screen()
    data class EpisodePage(val id: String) : Screen()
    data class PlaylistPage(val id: String) : Screen()
    data class YouTubePlayer(val url: String) : Screen()
}

/** The parts of the music, shown under Music (Kevin, 25 November 2026: all music under one category). */
val musicViews = listOf(Tab.ALBUMS, Tab.SINGLES, Tab.SONGS, Tab.ARTISTS, Tab.PLAYLISTS, Tab.FAVOURITES, Tab.ON_PHONE, Tab.FOLDERS)

/** The categories along the top: Music, then everything that isn't your own music. */
val topTabs = listOf(Tab.PODCASTS, Tab.RADIO, Tab.SPOTIFY, Tab.YOUTUBE)

enum class Tab(val label: String) {
    // The music first (Kevin, 25 November 2026), then everything else, in the order it was before.
    ALBUMS("Albums"), SINGLES("Singles"), SONGS("Songs"), ARTISTS("Artists"), PLAYLISTS("Playlists"), FAVOURITES("Favourites"), ON_PHONE("On phone"),
    PODCASTS("Podcasts"), RADIO("Radio"), SPOTIFY("Spotify"), YOUTUBE("YouTube"), FOLDERS("Folders"),
}

object Ui {
    /** Where Settings opens next: a group and section ("app|Your music"), set by tapping the message on the main screen. */
    var settingsAt by mutableStateOf<String?>(null)

    /** A link shared to KueSounds from another app, to open. */
    var sharedLink by mutableStateOf<String?>(null)

    @Composable
    fun App(signedIn: Boolean, signingIn: Boolean, signInProblem: String, onSignIn: () -> Unit) {
        // Settings › Look and feel › Text size: every word in the app drawn bigger or smaller.
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * KueSettings.textScale),
        ) {
            Box(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding()) {
                if (!signedIn) SignIn(signingIn, signInProblem, onSignIn) else Main()
            }
        }
    }

    @Composable
    internal fun Words(
        text: String,
        size: Int = 16,
        colour: Color = K.Ink,
        bold: Boolean = false,
        lines: Int = 1,
        modifier: Modifier = Modifier,
        centre: Boolean = false,
    ) {
        Text(
            text, modifier = modifier, color = colour, fontSize = size.sp, fontFamily = K.Poppins,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis,
            textAlign = if (centre) TextAlign.Center else null, lineHeight = (size * 1.3f).sp,
        )
    }

    /** "Kue" in white and "Sounds" in the Kue blue, as KueTV's name is written. */
    @Composable
    private fun Brand(size: Int, modifier: Modifier = Modifier) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("Kue")
                // A soft dark shadow keeps the light blue clear on any background.
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = K.KueBlue,
                        shadow = androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(0f, 2f), 6f),
                    ),
                ) { append("Sounds") }
            },
            modifier = modifier, color = K.Ink, fontSize = size.sp, fontFamily = K.Poppins, fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }

    /** The Kue K, the same mark as KueTV's. */
    @Composable
    private fun Mark(size: Dp) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.kue_mark),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }

    /** A big yellow button, or a blue one when it is the second choice. */
    @Composable
    internal fun Button(label: String, main: Boolean = true, glyph: Glyph? = null, modifier: Modifier = Modifier, icon: String? = null, onClick: () -> Unit) {
        Row(
            modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (main) K.Accent else K.Raised)
                .clickable(onClick = onClick).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (glyph != null) {
                Sign(glyph, 20.dp, if (main) K.Page else K.Ink)
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                KueSettings.Icon(icon, 20.dp, if (main) K.Page else K.Ink, 2f, filled = icon == KueSettings.Icons.PLAY)
                Spacer(Modifier.width(8.dp))
            }
            Words(label, 16, if (main) K.Page else K.Ink, bold = main)
        }
    }

    @Composable
    internal fun RoundButton(glyph: Glyph, label: String, size: Dp = 44.dp, onClick: () -> Unit) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Sign(glyph, size * 0.5f, K.Ink) }
    }

    // ---------------------------------------------------------------------------------------------
    // Signing in

    @Composable
    private fun SignIn(busy: Boolean, problem: String, onSignIn: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Mark(84.dp)
            Spacer(Modifier.height(20.dp))
            Brand(30)
            Spacer(Modifier.height(10.dp))
            Words("Your music, straight from Google Drive.", 17, K.Soft, lines = 3, centre = true)
            Spacer(Modifier.height(32.dp))
            if (busy) {
                CircularProgressIndicator(color = K.Accent)
            } else {
                Button("Sign in with Google", modifier = Modifier.fillMaxWidth(), onClick = onSignIn)
                Spacer(Modifier.height(12.dp))
                Words("Pick the Google account your music is in.", 15, K.Soft, lines = 3, centre = true)
            }
            if (problem.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Words(problem, 16, K.Accent, lines = 7, centre = true)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The library

    @Composable
    private fun Main() {
        var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
        val go: (Screen) -> Unit = { stack = stack + it }
        val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
        // A link shared to KueSounds from YouTube, YouTube Music or Spotify.
        val shared = sharedLink
        LaunchedEffect(shared) {
            if (shared == null) return@LaunchedEffect
            sharedLink = null
            if (YouTube.read(shared) != null) go(Screen.YouTubePlayer(shared))
            else if (!SpotifyLink.playLink(shared)) Unit
        }
        BackHandler(enabled = stack.size > 1, onBack = back)

        val data = Scanner.data
        val view = Scanner.view
        val albums = view.albums
        val artists = view.artists
        val songs = view.songs

        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                when (val s = stack.last()) {
                    Screen.Home -> Home(albums, artists, songs, go)
                    is Screen.AlbumPage -> (view.byKey[s.key] ?: Scanner.renamed[s.key]?.let { id -> albums.firstOrNull { al -> al.tracks.any { it.id == id } } })
                        ?.let { AlbumPage(it, back, go) } ?: Gone(back)
                    is Screen.ArtistPage -> artists.firstOrNull { it.name == s.name }?.let { ArtistPage(it, back, go) } ?: Gone(back)
                    is Screen.FolderPage -> FolderPage(s, data, back, go)
                    Screen.NowPlaying -> NowPlaying(back)
                    Screen.Settings -> Settings(back, go)
                    Screen.PodcastFind -> PodUi.Find(back, go)
                    is Screen.ShowPage -> PodUi.ShowPage(s.feed, back, go)
                    is Screen.EpisodePage -> PodUi.EpisodePage(s.id, back, go)
                    is Screen.PlaylistPage -> ListUi.Page(s.id, back)
                    is Screen.YouTubePlayer -> OnlineUi.YouTubePlayer(s.url, back)
                }
            }
            if (stack.last() != Screen.NowPlaying && Playing.active) MiniPlayer { go(Screen.NowPlaying) }
        }
    }

    @Composable
    private fun Gone(back: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            RoundButton(Glyph.BACK, "Back", onClick = back)
            Spacer(Modifier.height(20.dp))
            Words("This is no longer in your music.", 17, K.Soft)
        }
    }

    @Composable
    private fun Home(albums: List<Album>, artists: List<Artist>, songs: List<Track>, go: (Screen) -> Unit) {
        var tab by rememberSaveable { mutableStateOf(Tab.ALBUMS) }
        var query by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mark(36.dp)
                    Spacer(Modifier.width(10.dp))
                    Brand(24, Modifier.weight(1f))
                    RoundButton(Glyph.GEAR, "Settings") { go(Screen.Settings) }
                }
                SearchBox(query) { query = it }
                if (query.isBlank()) {
                    var choosingView by remember { mutableStateOf(false) }
                    // The music part last shown, so Music opens where it was left.
                    var lastMusic by rememberSaveable { mutableStateOf(if (tab in musicViews) tab else Tab.ALBUMS) }
                    if (tab in musicViews) lastMusic = tab
                    val inMusic = tab in musicViews
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Box(
                                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (inMusic) K.Accent else K.Panel)
                                    .clickable { tab = lastMusic }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Words("Music", 15, if (inMusic) K.Page else K.Ink, bold = inMusic) }
                        }
                        items(topTabs) { t ->
                            val on = t == tab
                            Box(
                                Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) K.Accent else K.Panel)
                                    .clickable { tab = t }.padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Words(t.label, 15, if (on) K.Page else K.Ink, bold = on) }
                        }
                    }
                    // Under Music: its parts, and how the one showing is laid out.
                    if (inMusic) Row(verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(musicViews) { t ->
                                val on = t == tab
                                Box(
                                    Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                                        .background(if (on) K.Raised else Color.Transparent)
                                        .then(if (on) Modifier else Modifier.border(1.dp, K.Raised, RoundedCornerShape(17.dp)))
                                        .clickable { tab = t }.padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Words(t.label, 14, if (on) K.Accent else K.Soft, bold = on) }
                            }
                        }
                        // How this part is shown: a list, a list with small pictures, or large covers (Kevin, 25 Nov 2026).
                        if (tab in Views.defaults) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(K.Panel).clickable(onClickLabel = "How it's shown") { choosingView = true },
                                contentAlignment = Alignment.Center,
                            ) { KueSettings.Icon(Views.layout(tab).icon, 18.dp, K.Ink, 2f) }
                        }
                    }
                    if (choosingView) HoldBox("Show " + tab.label.lowercase() + " as", "", { choosingView = false }) {
                        for (l in Views.Layout.entries) {
                            Button(l.label, main = Views.layout(tab) == l, icon = l.icon, modifier = Modifier.fillMaxWidth()) { Views.set(tab, l); choosingView = false }
                        }
                    }
                }
                Status(go)
            }
            Box(Modifier.weight(1f)) {
                when {
                    // Podcasts don't need any music in the Drive.
                    tab == Tab.PODCASTS && query.isBlank() -> PodUi.Home(go)
                    tab == Tab.RADIO && query.isBlank() -> OnlineUi.RadioTab()
                    tab == Tab.SPOTIFY && query.isBlank() -> OnlineUi.SpotifyTab()
                    tab == Tab.YOUTUBE && query.isBlank() -> OnlineUi.YouTubeTab(go)
                    tab == Tab.PLAYLISTS && query.isBlank() && Scanner.data.tracks.isNotEmpty() -> ListUi.Home(go, Views.layout(Tab.PLAYLISTS))
                    Scanner.data.tracks.isEmpty() -> Empty()
                    query.isNotBlank() -> {
                        val results = remember(query, albums) { Library.search(query, Scanner.data.tracks, albums, artists) }
                        SearchResults(results, go)
                    }
                    tab == Tab.ALBUMS -> AlbumShelf(albums, Views.layout(Tab.ALBUMS), go)
                    tab == Tab.ARTISTS -> ArtistList(artists, Views.layout(Tab.ARTISTS), go)
                    tab == Tab.SONGS -> SongList(songs, go, Views.layout(Tab.SONGS))
                    tab == Tab.SINGLES -> {
                        val titles = Scanner.data.singleTitles
                        val singles = remember(titles, songs) {
                            songs.filter { it.id in titles }.sortedWith(compareBy({ Library.sortName(it.artist) }, { Library.sortName(it.title) }))
                        }
                        if (singles.isEmpty()) Note("Songs that came out as singles show here, with the single's cover. They are found at step 3 in Settings.")
                        else SingleList(singles, titles, Views.layout(Tab.SINGLES), go)
                    }
                    tab == Tab.FAVOURITES -> {
                        val ids = Sync.favouriteIds()
                        val favs = remember(ids, songs) { songs.filter { it.id in ids } }
                        if (favs.isEmpty()) Note("Tap the heart on a playing song to keep it here.") else SongList(favs, go, Views.layout(Tab.FAVOURITES))
                    }
                    tab == Tab.ON_PHONE -> {
                        val saved = Offline.saved
                        val onPhone = remember(saved, albums) { albums.filter { al -> al.tracks.any { it.id in saved } } }
                        if (onPhone.isEmpty()) Note("Save an album to play it with no internet. Open an album and tap Save to phone.")
                        else AlbumShelf(onPhone, Views.layout(Tab.ON_PHONE), go)
                    }
                    else -> FolderList(null, Scanner.data, go)
                }
            }
        }
    }

    @Composable
    internal fun Note(text: String) {
        Box(Modifier.fillMaxSize().padding(28.dp)) { Words(text, 17, K.Soft, lines = 4) }
    }

    @Composable
    private fun SearchBox(query: String, onChange: (String) -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Sign(Glyph.SEARCH, 20.dp, K.Soft)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Words("Search songs, artists, albums", 16, K.Soft)
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = K.Ink, fontSize = 16.sp, fontFamily = K.Poppins),
                    cursorBrush = SolidColor(K.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(36.dp).clickable(onClickLabel = "Clear") { onChange("") }, contentAlignment = Alignment.Center) {
                    Sign(Glyph.CLOSE, 18.dp, K.Soft)
                }
            }
        }
    }

    /** One line saying what the scan is doing, or what went wrong, or that a newer KueSounds is ready. */
    @Composable
    private fun Status(go: (Screen) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var updating by remember { mutableStateOf("") }
        val offer = Updates.offer
        if (offer != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Raised).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Words(if (updating.isNotEmpty()) updating else "KueSounds " + offer.name + " is ready. After this one, updates go on by themselves.", 15, lines = 3, modifier = Modifier.weight(1f))
                if (updating.isEmpty()) {
                    Button("Update", modifier = Modifier.height(44.dp)) {
                        if (!Updates.mayInstall(context)) {
                            Updates.openInstallPermission(context)
                        } else {
                            updating = "Downloading · 0%"
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    runCatching { Updates.downloadChecked(context) { p -> updating = "Downloading · $p%" } }.getOrNull()
                                }
                                if (file != null) {
                                    updating = ""
                                    // Through Android's own installer, so from now on updates go on by themselves.
                                    runCatching { Updates.installSession(context, file) }.onFailure { Updates.install(context, file) }
                                } else {
                                    updating = "The download didn't work. Try again later."
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(40.dp).clickable(onClickLabel = "Not now") { Updates.putOff = offer.code; Updates.offer = null }, contentAlignment = Alignment.Center) {
                        Sign(Glyph.CLOSE, 18.dp, K.Soft)
                    }
                }
            }
        }
        val status = Scanner.status.ifEmpty { Fixer.status }.ifEmpty { Offline.status }.ifEmpty { Updates.doing }
        if (status.isNotEmpty()) {
            // Tapped, it opens the part of Settings that shows it in full (Kevin, 25 November 2026).
            val where = when {
                Scanner.status.isNotEmpty() -> "app|Your music"
                Fixer.status.isNotEmpty() -> "app|Fix the files"
                Offline.status.isNotEmpty() -> "privacy|Space on this phone"
                else -> "help|Version and updates"
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open in Settings") { settingsAt = where; go(Screen.Settings) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(color = K.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Words(status, 14, K.Soft, modifier = Modifier.weight(1f))
                KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Soft, 2.2f)
            }
        }
        if (Scanner.problem.isNotEmpty() && status.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Words(Scanner.problem, 14, K.Accent, lines = 2, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button("Try again", main = false, modifier = Modifier.height(44.dp)) { Scanner.scan() }
            }
        }
    }

    @Composable
    private fun Empty() {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (Scanner.running) {
                CircularProgressIndicator(color = K.Accent)
                Spacer(Modifier.height(16.dp))
                Words("Looking through your Google Drive.", 17, K.Soft, lines = 2, centre = true)
            } else {
                Words("No music found in this Google Drive.", 18, lines = 2, centre = true)
                Spacer(Modifier.height(8.dp))
                Words(Google.email, 15, K.Soft, centre = true)
                Spacer(Modifier.height(20.dp))
                Button("Look again") { Scanner.scan() }
            }
        }
    }

    @Composable
    private fun Cover(file: File?, modifier: Modifier, corner: Dp = 14.dp, glyphSize: Dp = 40.dp, glyph: Glyph = Glyph.DISC, round: Boolean = false) {
        Box(modifier.clip(if (round) CircleShape else RoundedCornerShape(corner)).background(K.Panel), contentAlignment = Alignment.Center) {
            if (file != null) {
                AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Sign(glyph, glyphSize, K.Soft)
            }
        }
    }

    /** One thing in a list: its name, the line under it, its picture, and what a tap, a hold and its ⋮ do. */
    private class Item(
        val key: String,
        val title: String,
        val below: String,
        val glyph: Glyph,
        val cover: () -> File?,
        val highlight: Boolean = false,
        val round: Boolean = false,
        val onHold: (() -> Unit)? = null,
        val onClick: () -> Unit,
    )

    /** The ⋮ on a row or cover: every action for it (Kevin, 25 November 2026). */
    @Composable
    private fun MoreButton(label: String, onClick: () -> Unit) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            KueSettings.Icon(KueSettings.Icons.MORE, 22.dp, K.Soft, 2f, filled = true)
        }
    }

    /**
     * A part of the music section shown the chosen way (Settings button by the parts, Kevin 25 November 2026): a
     * plain list, a list with small pictures, or large covers. A tap opens or plays; a hold or the ⋮ gives every action.
     */
    @Composable
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun Shelf(things: List<Item>, layout: Views.Layout) {
        if (layout == Views.Layout.COVERS) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(things, key = { it.key }) { x ->
                    Column(Modifier.combinedClickable(onClick = x.onClick, onLongClick = x.onHold), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Cover(x.cover(), Modifier.fillMaxWidth().aspectRatio(1f), glyph = x.glyph, round = x.round)
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                                Words(x.title, 15, if (x.highlight) K.Accent else K.Ink, bold = true)
                                if (x.below.isNotEmpty()) Words(x.below, 13, K.Soft)
                            }
                            if (x.onHold != null) MoreButton("Everything you can do with it", x.onHold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                items(things, key = { it.key }) { x ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = if (layout == Views.Layout.PICTURES) 72.dp else 60.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (x.highlight) K.Panel else Color.Transparent)
                            .combinedClickable(onClick = x.onClick, onLongClick = x.onHold)
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (layout == Views.Layout.PICTURES) Cover(x.cover(), Modifier.size(56.dp), corner = 10.dp, glyphSize = 24.dp, glyph = x.glyph, round = x.round)
                        else Box(Modifier.size(44.dp).clip(if (x.round) CircleShape else RoundedCornerShape(10.dp)).background(K.Panel), contentAlignment = Alignment.Center) { Sign(x.glyph, 22.dp, K.Soft) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Words(x.title, 16, if (x.highlight) K.Accent else K.Ink, bold = x.highlight)
                            if (x.below.isNotEmpty()) Words(x.below, 13, K.Soft)
                        }
                        if (x.onHold != null) MoreButton("Everything you can do with it", x.onHold)
                    }
                }
            }
        }
    }

    /** Albums, shown the chosen way. */
    @Composable
    private fun AlbumShelf(albums: List<Album>, layout: Views.Layout, go: (Screen) -> Unit) {
        var held by remember { mutableStateOf<Album?>(null) }
        held?.let { al -> AlbumBox(al, go) { held = null } }
        val things = remember(albums) {
            albums.map { al ->
                Item(
                    al.key, al.title, listOfNotNull(al.artist, al.year.takeIf { it > 0 }?.toString()).joinToString(" · "), Glyph.DISC, { Scanner.artFor(al) },
                    onHold = { held = al },
                ) { go(Screen.AlbumPage(al.key)) }
            }
        }
        Shelf(things, layout)
    }

    @Composable
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun AlbumGrid(albums: List<Album>, go: (Screen) -> Unit, header: (@Composable () -> Unit)? = null) {
        // Hold an album: play, queue, add to a playlist, save to the phone, fix its files, go to its artist.
        var heldAlbum by remember { mutableStateOf<Album?>(null) }
        heldAlbum?.let { al -> AlbumBox(al, go) { heldAlbum = null } }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(albums, key = { it.key }) { al ->
                Column(Modifier.combinedClickable(onClick = { go(Screen.AlbumPage(al.key)) }, onLongClick = { heldAlbum = al }), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cover(Scanner.artFor(al), Modifier.fillMaxWidth().aspectRatio(1f))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 2.dp)) {
                            Words(al.title, 15, bold = true)
                            Words(listOfNotNull(al.artist, al.year.takeIf { it > 0 }?.toString()).joinToString(" · "), 13, K.Soft)
                        }
                        MoreButton("Everything you can do with it") { heldAlbum = al }
                    }
                }
            }
        }
    }

    @Composable
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun Row2(top: String, below: String, glyph: Glyph?, highlight: Boolean = false, lead: String = "", onHold: (() -> Unit)? = null, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
                .background(if (highlight) K.Panel else Color.Transparent)
                .then(if (onHold != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onHold) else Modifier.clickable(onClick = onClick))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (lead.isNotEmpty()) {
                Words(lead, 15, if (highlight) K.Accent else K.Soft, modifier = Modifier.width(30.dp))
                Spacer(Modifier.width(8.dp))
            } else if (glyph != null) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(K.Panel), contentAlignment = Alignment.Center) { Sign(glyph, 22.dp, K.Soft) }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Words(top, 16, if (highlight) K.Accent else K.Ink, bold = highlight)
                if (below.isNotEmpty()) Words(below, 13, K.Soft)
            }
            if (onHold != null) MoreButton("Everything you can do with it", onHold)
        }
    }

    @Composable
    private fun ArtistList(artists: List<Artist>, layout: Views.Layout, go: (Screen) -> Unit) {
        // Hold an artist, or its ⋮: play or queue everything of theirs, add it to a playlist, save it to the phone.
        var held by remember { mutableStateOf<Artist?>(null) }
        held?.let { a -> ArtistBox(a) { held = null } }
        val things = remember(artists) {
            artists.map { a ->
                // An artist's picture: the cover of their first album that has one.
                Item("a:" + a.name, a.name, count(a.albums.size, "album") + " · " + count(a.songCount, "song"), Glyph.PERSON,
                    { a.albums.firstNotNullOfOrNull { Scanner.artFor(it) } }, round = true, onHold = { held = a }) { go(Screen.ArtistPage(a.name)) }
            }
        }
        Shelf(things, layout)
    }

    private fun count(n: Int, word: String): String = String.format("%,d", n) + " " + word + if (n == 1) "" else "s"

    @Composable
    private fun SongList(songs: List<Track>, go: (Screen) -> Unit, layout: Views.Layout = Views.Layout.LIST) {
        // A tap plays; a hold or the ⋮ gives the song's every action (see SongBox).
        var held by remember { mutableStateOf<Track?>(null) }
        val playing = Playing.currentId
        val things = remember(songs, playing) {
            songs.mapIndexed { i, t ->
                Item(t.id, t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), Glyph.NOTE,
                    { Scanner.artForTrack(t) ?: Scanner.singleArtFor(t) }, highlight = t.id == playing, onHold = { held = t }) { Playing.play(songs, i) }
            }
        }
        Shelf(things, layout)
        held?.let { t -> SongBox(t, go) { held = null } }
    }

    /** A hold menu: the name of what was held, a line under it, its choices, then Cancel. Scrolls on a small phone. */
    @Composable
    private fun HoldBox(title: String, below: String, onClose: () -> Unit, choices: @Composable () -> Unit) {
        Dialog(onDismissRequest = onClose) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Words(title, 18, bold = true, lines = 2)
                if (below.isNotEmpty()) Words(below, 14, K.Soft)
                choices()
                Button("Cancel", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }

    /** Play next, add to the queue, add to a playlist and save to (or take off) the phone: the same on songs, albums and artists. */
    @Composable
    private fun CommonChoices(songs: List<Track>, onAdd: () -> Unit, onClose: () -> Unit) {
        val context = LocalContext.current
        Button("Play next", main = false, icon = KueSettings.Icons.PLAY_NEXT, modifier = Modifier.fillMaxWidth()) { Playing.playNext(songs); onClose() }
        Button("Add to the queue", main = false, icon = KueSettings.Icons.QUEUE, modifier = Modifier.fillMaxWidth()) { Playing.addToQueue(songs); onClose() }
        Button("Add to a playlist", main = false, icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.fillMaxWidth(), onClick = onAdd)
        if (songs.isNotEmpty() && songs.all { it.id in Offline.saved }) {
            Button("Take off the phone", main = false, icon = KueSettings.Icons.BIN, modifier = Modifier.fillMaxWidth()) { Offline.remove(songs); onClose() }
        } else {
            Button("Save to the phone", main = false, icon = KueSettings.Icons.PHONE, modifier = Modifier.fillMaxWidth()) { Offline.save(context, songs); onClose() }
        }
    }

    /**
     * What can be done with a held song. Add to a playlist stays first, as before; then playing it next or later,
     * favourites, saving it to the phone, going to its album or artist, and putting its name right (Kevin, 24 Nov 2026).
     */
    @Composable
    fun SongBox(t: Track, go: ((Screen) -> Unit)? = null, onClose: () -> Unit) {
        var doing by remember { mutableStateOf("") }
        when (doing) {
            "add" -> ListUi.AddBox("\"" + t.title + "\"", listOf(t.id), onClose)
            "edit" -> EditSong(t, onClose)
            "info" -> SongInfo(t, onClose)
            "match" -> MatchBox(listOf(t), onClose)
            "delete" -> DeleteSong(t, onClose)
            else -> HoldBox(t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), onClose) {
                Button("Add to a playlist", icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.fillMaxWidth()) { doing = "add" }
                Button("Play it now", main = false, glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(listOf(t), 0); onClose() }
                Button("Play next", main = false, icon = KueSettings.Icons.PLAY_NEXT, modifier = Modifier.fillMaxWidth()) { Playing.playNext(listOf(t)); onClose() }
                Button("Add to the queue", main = false, icon = KueSettings.Icons.QUEUE, modifier = Modifier.fillMaxWidth()) { Playing.addToQueue(listOf(t)); onClose() }
                val fav = Sync.isFavourite(t.id)
                Button(if (fav) "Take out of favourites" else "Add to favourites", main = false, glyph = if (fav) Glyph.HEART_FULL else Glyph.HEART, modifier = Modifier.fillMaxWidth()) {
                    Sync.flipFavourite(t.id); onClose()
                }
                val context = LocalContext.current
                if (t.id in Offline.saved) Button("Take off the phone", main = false, icon = KueSettings.Icons.BIN, modifier = Modifier.fillMaxWidth()) { Offline.remove(listOf(t)); onClose() }
                else Button("Save to the phone", main = false, icon = KueSettings.Icons.PHONE, modifier = Modifier.fillMaxWidth()) { Offline.save(context, listOf(t)); onClose() }
                if (go != null) {
                    val album = Scanner.view.albums.firstOrNull { al -> al.tracks.any { it.id == t.id } }
                    if (album != null) Button("Go to the album", main = false, icon = KueSettings.Icons.DISC, modifier = Modifier.fillMaxWidth()) { onClose(); go(Screen.AlbumPage(album.key)) }
                    val artist = album?.artist ?: t.artist
                    if (Scanner.view.artists.any { it.name == artist }) Button("Go to the artist", main = false, glyph = Glyph.PERSON, modifier = Modifier.fillMaxWidth()) { onClose(); go(Screen.ArtistPage(artist)) }
                }
                Button("Song info", main = false, icon = KueSettings.Icons.INFO, modifier = Modifier.fillMaxWidth()) { doing = "info" }
                Button("Match by sound", main = false, icon = KueSettings.Icons.EQUALISER, modifier = Modifier.fillMaxWidth()) { doing = "match" }
                Button("Edit name and artist", main = false, icon = KueSettings.Icons.EDIT, modifier = Modifier.fillMaxWidth()) { doing = "edit" }
                Button("Delete from your Drive", main = false, icon = KueSettings.Icons.BIN, modifier = Modifier.fillMaxWidth()) { doing = "delete" }
            }
        }
    }

    /**
     * Everything about one song, as Shazam shows it (Kevin, 25 November 2026): its cover big, its name, artist and
     * album, the file's own details, what the music catalogue matched it to, and a way to see it on YouTube or the web.
     */
    @Composable
    fun SongInfo(t: Track, onClose: () -> Unit) {
        val context = LocalContext.current
        val open = { url: String ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Unit
        }
        val words = java.net.URLEncoder.encode(listOf(t.artist.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), t.title).filter { it.isNotBlank() }.joinToString(" "), "UTF-8")
        Dialog(onDismissRequest = onClose) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Cover(Scanner.artForTrack(t) ?: Scanner.singleArtFor(t), Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp, glyphSize = 72.dp)
                Words(t.title, 22, bold = true, lines = 3)
                Words(t.artist, 17, K.Accent, lines = 2)
                val album = listOfNotNull(t.album.takeIf { it.isNotBlank() }, t.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
                if (album.isNotEmpty()) Words(album, 15, K.Soft, lines = 2)
                val facts = buildList {
                    if (t.albumArtist.isNotBlank() && t.albumArtist != t.artist) add("Album artist" to t.albumArtist)
                    if (t.trackNo > 0) add("Song" to (t.trackNo.toString() + if (t.discNo > 0) " on disc " + t.discNo else ""))
                    if (t.genre.isNotBlank()) add("Kind of music" to t.genre)
                    if (t.durationMs > 0) add("Length" to Library.clock(t.durationMs))
                    Scanner.data.singleTitles[t.id]?.let { add("Came out as a single" to it) }
                    add("File" to t.fileName)
                    add("Type" to Library.ending(t.fileName).uppercase())
                    if (t.size > 0) add("Size" to String.format("%.1f MB", t.size / 1_000_000.0))
                    if (t.size > 0 && t.durationMs > 0) add("Quality" to ((t.size * 8 / (t.durationMs / 1000.0)) / 1000).toInt().toString() + " kbps")
                    Scanner.data.folders.firstOrNull { it.id == t.folderId }?.let { add("Folder in your Drive" to it.name) }
                    add("On this phone" to if (t.id in Offline.saved) "Yes, plays with no internet" else "No")
                    add("Files fixed" to if (t.fixed) "Yes" else "Not yet")
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value) in facts) Row {
                        Words(label, 14, K.Soft, modifier = Modifier.width(130.dp))
                        Words(value, 14, lines = 3, modifier = Modifier.weight(1f))
                    }
                }
                val release = Scanner.releaseFor(t)
                Words(if (release != null) "Matched in the MusicBrainz music catalogue." else "Not matched in the music catalogue.", 14, K.Soft, lines = 2)
                Button("Play it", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(listOf(t), 0); onClose() }
                Button("Watch it on YouTube", main = false, icon = KueSettings.Icons.VIDEO, modifier = Modifier.fillMaxWidth()) { open("https://www.youtube.com/results?search_query=$words") }
                Button("Look it up on the web", main = false, icon = KueSettings.Icons.WEB, modifier = Modifier.fillMaxWidth()) { open("https://www.google.com/search?q=$words") }
                if (release != null) Button("Its record in the catalogue", main = false, icon = KueSettings.Icons.OPEN, modifier = Modifier.fillMaxWidth()) { open("https://musicbrainz.org/release/" + release.first) }
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }

    /**
     * Match by sound, for one song or every song on an album (Kevin, 25 November 2026): KueSounds listens to each file,
     * says what it is and how sure it is, then Use these puts the names right in the files and the songs on their album.
     */
    @Composable
    private fun MatchBox(songs: List<Track>, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val found = remember { androidx.compose.runtime.mutableStateMapOf<String, List<SoundMatch.Found>>() }
        val problems = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
        var listening by remember { mutableStateOf(0) }
        var saving by remember { mutableStateOf("") }
        var done by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            for ((i, t) in songs.withIndex()) {
                listening = i + 1
                val got = withContext(Dispatchers.IO) { runCatching { SoundMatch.identify(t) } }
                got.onSuccess { found[t.id] = it }.onFailure { problems[t.id] = it.message ?: "It didn't work." }
                // A wrong key stops the lot: every song would say the same.
                if (got.exceptionOrNull()?.message?.contains("key") == true) break
            }
            listening = 0
        }
        // The album most of the songs are on, for an album's worth: every matched song goes on it when it lists it.
        val album = remember(found.size) {
            found.values.mapNotNull { it.firstOrNull()?.albums }.flatten().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }
        Dialog(onDismissRequest = { if (saving.isEmpty()) onClose() }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Words("Match by sound", 19, bold = true)
                if (listening > 0) Working("Listening to " + (if (songs.size == 1) "the song" else "song $listening of ${songs.size}") + "…")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(songs, key = { it.id }) { t ->
                        val best = found[t.id]?.firstOrNull()
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).padding(12.dp)) {
                            Words(t.title + " · " + t.artist, 13, K.Soft, lines = 2)
                            when {
                                best != null -> {
                                    Words(best.title, 16, bold = true, lines = 2)
                                    Words(best.artist, 14, K.Accent, lines = 2)
                                    val on = when {
                                        album != null && album in best.albums -> "On " + album
                                        best.albums.isNotEmpty() -> "On " + best.albums.first()
                                        best.single != null -> "Single: " + best.single
                                        else -> ""
                                    }
                                    if (on.isNotEmpty()) Words(on, 13, K.Soft, lines = 2)
                                    Words("Sure: " + (best.score * 100).toInt() + "%", 13, K.Soft)
                                }
                                t.id in problems -> Words(problems[t.id].orEmpty(), 13, K.Accent, lines = 3)
                                t.id in found -> Words("Not in the sound database.", 13, K.Soft)
                                else -> Words("Waiting", 13, K.Soft)
                            }
                        }
                    }
                }
                if (saving.isNotEmpty()) Working(saving)
                val usable = songs.filter { found[it.id]?.firstOrNull() != null }
                if (listening == 0 && saving.isEmpty() && !done && usable.isNotEmpty()) {
                    Words("Use these: puts each name and artist into its file in your Drive, and the songs on their album. Google keeps the old version for 30 days.", 13, K.Soft, lines = 4)
                    Button("Use these", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth()) {
                        val go = {
                            scope.launch {
                                for ((i, t) in usable.withIndex()) {
                                    val best = found[t.id]!!.first()
                                    saving = "Saving " + (i + 1) + " of " + usable.size + "…"
                                    withContext(Dispatchers.IO) { Fixer.rename(context.applicationContext, t, best.title, best.artist) }
                                }
                                // Each song on the album most of them share, or its own first album, or its single.
                                val byAlbum = usable.groupBy { t ->
                                    val best = found[t.id]!!.first()
                                    when { album != null && album in best.albums -> album; best.albums.isNotEmpty() -> best.albums.first(); else -> best.single }
                                }
                                for ((name, list) in byAlbum) if (name != null) Scanner.setSongsAlbum(list.map { it.id }.toSet(), name, found[list.first().id]!!.first().artist)
                                saving = ""
                                done = true
                            }
                            Unit
                        }
                        if (Google.canEdit) go() else Fixer.askPermission { go() }
                    }
                }
                if (done) Words("Saved. The albums are looked up again with the right names.", 14, K.Accent, lines = 2)
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth()) { if (saving.isEmpty()) onClose() }
            }
        }
    }

    /** Deletes a song: its file goes to the Drive's bin (kept 30 days there), and it leaves KueSounds. Asked first. */
    @Composable
    private fun DeleteSong(t: Track, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var saying by remember { mutableStateOf("") }
        HoldBox("Delete \"" + t.title + "\"?", t.artist, { if (!busy) onClose() }) {
            Words("Its file goes to your Google Drive's bin. Google keeps it there for 30 days, so it can be put back from drive.google.com.", 14, K.Soft, lines = 4)
            if (saying.isNotEmpty()) Words(saying, 14, K.Accent, lines = 3)
            Button(if (busy) "Deleting…" else "Delete it", icon = KueSettings.Icons.BIN, modifier = Modifier.fillMaxWidth()) {
                if (busy) return@Button
                val go = {
                    busy = true
                    scope.launch {
                        val said = withContext(Dispatchers.IO) {
                            runCatching {
                                Drive.trash(t.id, Google.editToken() ?: throw Drive.SignedOut())
                                Offline.remove(listOf(t))
                                Scanner.removeTracks(setOf(t.id))
                                Scanner.note("deleted by hand (to the Drive's bin): " + t.fileName)
                            }.exceptionOrNull()
                        }
                        busy = false
                        if (said == null) {
                            android.widget.Toast.makeText(context, "Deleted. It's in your Drive's bin for 30 days.", android.widget.Toast.LENGTH_SHORT).show()
                            onClose()
                        } else saying = if (said is Drive.SignedOut) "Not deleted: Google needs the edit permission. Try again." else "Not deleted: " + (said.message ?: "it didn't work")
                    }
                    Unit
                }
                if (Google.canEdit) go() else Fixer.askPermission { go() }
            }
        }
    }

    /** What can be done with a held album (Kevin, 24 November 2026). */
    @Composable
    private fun AlbumBox(al: Album, go: (Screen) -> Unit, onClose: () -> Unit) {
        val context = LocalContext.current
        var adding by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        if (editing) { EditAlbum(al, onClose); return }
        var matching by remember { mutableStateOf(false) }
        if (matching) { MatchBox(al.tracks, onClose); return }
        if (adding) { ListUi.AddBox("the album", al.tracks.map { it.id }, onClose); return }
        HoldBox(al.title, listOf(al.artist, count(al.tracks.size, "song")).joinToString(" · "), onClose) {
            Button("Play", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(al.tracks, 0); onClose() }
            Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.fillMaxWidth()) { Playing.play(al.tracks, 0, shuffled = true); onClose() }
            CommonChoices(al.tracks, { adding = true }, onClose)
            if (Fixer.hasWork(al)) Button("Fix the files", main = false, icon = KueSettings.Icons.FIX, modifier = Modifier.fillMaxWidth()) { Fixer.fix(context, listOf(al)); onClose() }
            Button("Edit album name and artist", main = false, icon = KueSettings.Icons.EDIT, modifier = Modifier.fillMaxWidth()) { editing = true }
            Button("Match every song by sound", main = false, icon = KueSettings.Icons.EQUALISER, modifier = Modifier.fillMaxWidth()) { matching = true }
            if (Scanner.view.artists.any { it.name == al.artist }) Button("Go to the artist", main = false, glyph = Glyph.PERSON, modifier = Modifier.fillMaxWidth()) { onClose(); go(Screen.ArtistPage(al.artist)) }
        }
    }

    /** What can be done with a held artist: everything of theirs (Kevin, 24 November 2026). */
    @Composable
    private fun ArtistBox(a: Artist, onClose: () -> Unit) {
        val all = remember(a) { a.albums.flatMap { it.tracks } }
        var adding by remember { mutableStateOf(false) }
        if (adding) { ListUi.AddBox(a.name, all.map { it.id }, onClose); return }
        HoldBox(a.name, count(a.albums.size, "album") + " · " + count(a.songCount, "song"), onClose) {
            Button("Play all", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(all, 0); onClose() }
            Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.fillMaxWidth()) { Playing.play(all, 0, shuffled = true); onClose() }
            CommonChoices(all, { adding = true }, onClose)
        }
    }

    /**
     * Puts an album's name and artist right by hand (Kevin, 25 November 2026: an album the catalogue didn't find is
     * usually a spelling). Kept in KueSounds at once and looked up again; written into the files when they are fixed.
     */
    @Composable
    private fun EditAlbum(al: Album, onClose: () -> Unit) {
        var title by remember { mutableStateOf(al.title) }
        var artist by remember { mutableStateOf(al.artist) }
        var saying by remember { mutableStateOf("") }
        Dialog(onDismissRequest = onClose) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("Edit the album", 18, bold = true)
                Words("Album name", 13, K.Soft)
                EditField(title) { title = it }
                Words("Artist", 13, K.Soft)
                EditField(artist) { artist = it }
                Words("It's looked up again straight away. The files get the new name when you next press Fix.", 13, K.Soft, lines = 3)
                if (saying.isNotEmpty()) Words(saying, 14, K.Accent, lines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Save", modifier = Modifier.weight(1f)) {
                        val name = title.trim()
                        val who = artist.trim()
                        if (name.isEmpty() || who.isEmpty()) { saying = "The album needs a name and an artist."; return@Button }
                        Scanner.renameAlbum(al, name, who)
                        onClose()
                    }
                    Button("Cancel", main = false, modifier = Modifier.weight(1f), onClick = onClose)
                }
            }
        }
    }

    /**
     * Puts a song's name and artist right by hand, and writes them into its file in the Drive with everything
     * else inside taken out (old comments, wrong album names, wrong pictures). Kevin, 23 November 2026.
     */
    @Composable
    private fun EditSong(t: Track, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var title by remember { mutableStateOf(t.title) }
        var artist by remember { mutableStateOf(t.artist) }
        var saying by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if (!busy) onClose() }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("Edit the song", 18, bold = true)
                Words("Song name", 13, K.Soft)
                EditField(title) { title = it }
                val plain = Library.withoutBrackets(title)
                if (plain != title.trim()) Button("Take out the part in brackets", main = false, modifier = Modifier.fillMaxWidth()) { title = plain }
                Words("Artist", 13, K.Soft)
                EditField(artist) { artist = it }
                Words(
                    "Saving writes this into the file in your Drive and takes everything else out of it: old comments, wrong album " +
                        "names and wrong pictures. Its album, year and the album's own cover stay. Google keeps the old version for 30 days.",
                    13, K.Soft, lines = 6,
                )
                if (saying.isNotEmpty()) Words(saying, 14, K.Accent, lines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(if (busy) "Saving…" else "Save", modifier = Modifier.weight(1f)) {
                        val name = title.trim()
                        val who = artist.trim()
                        if (busy) return@Button
                        if (name.isEmpty() || who.isEmpty()) { saying = "The song needs a name and an artist."; return@Button }
                        val go = {
                            busy = true
                            saying = ""
                            scope.launch {
                                val said = withContext(Dispatchers.IO) { Fixer.rename(context.applicationContext, t, name, who) }
                                busy = false
                                if (said.startsWith("Saved")) {
                                    android.widget.Toast.makeText(context, said, android.widget.Toast.LENGTH_SHORT).show()
                                    onClose()
                                } else saying = said
                            }
                            Unit
                        }
                        if (Google.canEdit) go() else Fixer.askPermission { go() }
                    }
                    Button("Cancel", main = false, modifier = Modifier.weight(1f)) { if (!busy) onClose() }
                }
            }
        }
    }

    @Composable
    private fun EditField(value: String, onChange: (String) -> Unit) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = TextStyle(color = K.Ink, fontSize = 16.sp, fontFamily = K.Poppins),
                cursorBrush = SolidColor(K.Accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    /** Songs that came out as singles, each with its single's cover and name. */
    @Composable
    private fun SingleList(songs: List<Track>, titles: Map<String, String>, layout: Views.Layout, go: (Screen) -> Unit) {
        var held by remember { mutableStateOf<Track?>(null) }
        val playing = Playing.currentId
        val things = remember(songs, titles, playing) {
            songs.mapIndexed { i, t ->
                Item(t.id, t.title, t.artist + " · single, " + (titles[t.id] ?: t.title), Glyph.DISC, { Scanner.singleArtFor(t) },
                    highlight = t.id == playing, onHold = { held = t }) { Playing.play(songs, i) }
            }
        }
        Shelf(things, layout)
        held?.let { t -> SongBox(t, go) { held = null } }
    }

    @Composable
    private fun FolderList(folder: String?, data: LibraryData, go: (Screen) -> Unit) {
        val subs = remember(data, folder) { Library.foldersIn(folder, data.folders, data.tracks) }
        val songs = remember(data, folder) { Library.songsIn(folder, data.folders, data.tracks) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            items(subs, key = { "f:" + it.id }) { f -> Row2(f.name, "", Glyph.FOLDER) { go(Screen.FolderPage(f.id, f.name)) } }
            itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                Row2(t.title, t.artist, Glyph.NOTE, highlight = t.id == Playing.currentId) { Playing.play(songs, i) }
            }
        }
    }

    @Composable
    private fun SearchResults(r: Library.Results, go: (Screen) -> Unit) {
        if (r.artists.isEmpty() && r.albums.isEmpty() && r.songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp)) { Words("Nothing matches that.", 17, K.Soft) }
            return
        }
        // Holding works here too: the same menus as in the lists.
        var heldArtist by remember { mutableStateOf<Artist?>(null) }
        var heldAlbum by remember { mutableStateOf<Album?>(null) }
        var heldSong by remember { mutableStateOf<Track?>(null) }
        heldArtist?.let { a -> ArtistBox(a) { heldArtist = null } }
        heldAlbum?.let { al -> AlbumBox(al, go) { heldAlbum = null } }
        heldSong?.let { t -> SongBox(t, go) { heldSong = null } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            if (r.artists.isNotEmpty()) item { Heading("Artists") }
            items(r.artists, key = { "a:" + it.name }) { a -> Row2(a.name, count(a.albums.size, "album"), Glyph.PERSON, onHold = { heldArtist = a }) { go(Screen.ArtistPage(a.name)) } }
            if (r.albums.isNotEmpty()) item { Heading("Albums") }
            items(r.albums, key = { "l:" + it.key }) { al -> Row2(al.title, al.artist, Glyph.DISC, onHold = { heldAlbum = al }) { go(Screen.AlbumPage(al.key)) } }
            if (r.songs.isNotEmpty()) item { Heading("Songs") }
            itemsIndexed(r.songs, key = { _, t -> "s:" + t.id }) { i, t ->
                Row2(t.title, t.artist + " · " + t.album, Glyph.NOTE, highlight = t.id == Playing.currentId, onHold = { heldSong = t }) { Playing.play(r.songs, i) }
            }
        }
    }

    @Composable
    private fun Heading(text: String) {
        Words(text, 19, bold = true, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
    }

    // ---------------------------------------------------------------------------------------------
    // Album, artist and folder pages

    @Composable
    private fun AlbumPage(al: Album, back: () -> Unit, go: (Screen) -> Unit) {
        // Hold a song: add it to a playlist, or put its name right.
        var held by remember { mutableStateOf<Track?>(null) }
        held?.let { t -> SongBox(t, go) { held = null } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth()) { RoundButton(Glyph.BACK, "Back", onClick = back) }
                    Spacer(Modifier.height(12.dp))
                    Cover(Scanner.artFor(al), Modifier.size(220.dp), corner = 18.dp, glyphSize = 72.dp)
                    Spacer(Modifier.height(14.dp))
                    Words(al.title, 22, bold = true, lines = 2, centre = true)
                    val bits = listOfNotNull(al.artist, al.year.takeIf { it > 0 }?.toString(), count(al.tracks.size, "song"))
                    Words(bits.joinToString(" · "), 15, K.Soft, lines = 2, centre = true, modifier = Modifier.clickable { go(Screen.ArtistPage(al.artist)) })
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Play", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) { Playing.play(al.tracks, 0) }
                        Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.weight(1f)) { Playing.play(al.tracks, 0, shuffled = true) }
                    }
                    Spacer(Modifier.height(12.dp))
                    var adding by remember { mutableStateOf(false) }
                    Button("Add to a playlist", main = false, modifier = Modifier.fillMaxWidth()) { adding = true }
                    if (adding) ListUi.AddBox("the album", al.tracks.map { it.id }) { adding = false }
                    Spacer(Modifier.height(12.dp))
                    var editingAlbum by remember { mutableStateOf(false) }
                    Button("Edit album name and artist", main = false, icon = KueSettings.Icons.EDIT, modifier = Modifier.fillMaxWidth()) { editingAlbum = true }
                    if (editingAlbum) EditAlbum(al) { editingAlbum = false }
                    Spacer(Modifier.height(12.dp))
                    var matchingAlbum by remember { mutableStateOf(false) }
                    Button("Match every song by sound", main = false, icon = KueSettings.Icons.EQUALISER, modifier = Modifier.fillMaxWidth()) { matchingAlbum = true }
                    if (matchingAlbum) MatchBox(al.tracks) { matchingAlbum = false }
                    Spacer(Modifier.height(12.dp))
                    SaveToPhone(al)
                    if (Fixer.canFix(al)) {
                        Spacer(Modifier.height(12.dp))
                        FixFiles(listOf(al))
                    }
                }
            }
            itemsIndexed(al.tracks, key = { _, t -> t.id }) { i, t ->
                Box(Modifier.padding(horizontal = 12.dp)) {
                    val len = if (t.durationMs > 0) Library.clock(t.durationMs) else ""
                    // The song's own artist shows only when it isn't the album's (a guest, or a compilation).
                    val onPhone = if (t.id in Offline.saved) "On phone" else ""
                    val sub = listOf(t.artist.takeIf { it != al.artist }.orEmpty(), len, onPhone).filter { it.isNotEmpty() }.joinToString(" · ")
                    Row2(t.title, sub, null, highlight = t.id == Playing.currentId, lead = if (t.trackNo > 0) t.trackNo.toString() else (i + 1).toString(), onHold = { held = t }) {
                        Playing.play(al.tracks, i)
                    }
                }
            }
        }
    }

    /** "Save to phone": keeps the album's songs on the phone to play with no internet. */
    @Composable
    private fun SaveToPhone(al: Album) {
        val context = LocalContext.current
        val saved = Offline.saved
        val all = al.tracks.all { it.id in saved }
        when {
            all -> Row(verticalAlignment = Alignment.CenterVertically) {
                Words("Saved on this phone.", 14, K.Soft, modifier = Modifier.weight(1f))
                Button("Remove", main = false, modifier = Modifier.height(44.dp)) { Offline.remove(al.tracks) }
            }
            Offline.isWaiting(al) || Offline.status.contains(al.tracks.firstOrNull { it.id !in saved }?.title ?: "\u0000") ->
                Words(
                    "Saving to phone · " + al.tracks.count { it.id in saved } + " of " + al.tracks.size,
                    14, K.Accent, centre = true,
                )
            else -> Button("Save to phone", main = false, glyph = Glyph.SAVE, modifier = Modifier.fillMaxWidth()) {
                Offline.save(context, al.tracks)
            }
        }
    }

    /**
     * "Fix the files" for one album or many: asks once to be sure, then puts the right cover and album
     * details into the files in the Drive.
     */
    @Composable
    private fun FixFiles(albums: List<Album>, label: String = "Fix the files", main: Boolean = false) {
        val context = LocalContext.current
        var sure by remember { mutableStateOf(false) }
        val todo = albums.filter { Fixer.hasWork(it) }
        val songs = todo.sumOf { al -> Fixer.toDo(al).size }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                // Nothing matched yet: nothing has been fixed either, so it mustn't say so.
                albums.isEmpty() -> Words(if (Scanner.running) "Nothing to fix until the albums are looked up." else "No albums matched yet.", 14, K.Soft, lines = 2)
                todo.isEmpty() -> Words("Fixed.", 14, K.Soft, centre = albums.size == 1)
                Fixer.running && (todo.any { Fixer.isWaiting(it) } || Fixer.status.contains(todo.first().title)) -> Working(if (Fixer.status.contains(todo.first().title)) Fixer.status.removePrefix("Fixing files · ") else "Waiting its turn")
                // Settings shows it as the yellow button (Kevin, 24 Nov 2026: the blue one on a panel looked like text).
                !sure -> Button(label, main = main, modifier = Modifier.fillMaxWidth()) { sure = true }
                else -> {
                    Words(count(songs, "song") + ": the right name, artist, album and cover written into each file in your Drive. Google keeps the old version for 30 days.", 14, K.Soft, lines = 4)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Fix them", modifier = Modifier.weight(1f)) {
                            sure = false
                            Fixer.fix(context, todo)
                        }
                        Button("Cancel", main = false, modifier = Modifier.weight(1f)) { sure = false }
                    }
                }
            }
            if (Fixer.permissionProblem.isNotEmpty()) Words(Fixer.permissionProblem, 14, K.Accent, lines = 6)
        }
    }

    /** A spinner and what is being done: shown whenever KueSounds is working on something (Kevin, 25 November 2026). */
    @Composable
    private fun Working(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Words(text, 14, K.Accent, lines = 2, modifier = Modifier.weight(1f))
        }
    }

    /**
     * Settings › KueSounds › Fix the files, in one place (Kevin, 25 November 2026: it was shown twice, with long words):
     * what is left, one button, a spinner while it works, and the songs whose files can't be changed.
     */
    @Composable
    private fun FixPanel(view: Scanner.LibraryView, go: (Screen) -> Unit) {
        val data = Scanner.data
        // Songs whose file didn't fix last time, with why, to put right (Kevin, 25 November 2026: never marked done).
        val notFixed = remember(data) { data.tracks.filter { it.id in data.fixProblems && !(it.fixed && it.fixedFully) } }
        var held by remember { mutableStateOf<Track?>(null) }
        held?.let { t -> SongBox(t, go) { held = null } }
        when {
            Fixer.running -> Working(Fixer.status.removePrefix("Fixing files · ").ifBlank { "Fixing" })
            view.matched.isEmpty() -> Words(if (Scanner.running) "Nothing to fix until the albums are looked up." else "No albums matched yet.", 15, K.Soft, lines = 2)
            view.leftToFix == 0 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KueSettings.Icon(KueSettings.Icons.TICK, 22.dp, K.Accent, 2.6f)
                Words(if (notFixed.isEmpty()) "Every matched album is fixed." else "Everything else is fixed.", 15, lines = 2)
            }
            else -> Words(count(view.leftToFix, "album") + " to fix", 17, bold = true)
        }
        if (Fixer.lastResult.isNotEmpty() && !Fixer.running) Words(Fixer.lastResult, 14, K.Soft, lines = 3)
        if (!Fixer.running && view.leftToFix > 0) FixFiles(view.matched, "Fix them", main = true)
        if (notFixed.isNotEmpty() && !Fixer.running) {
            Words("Stuck, skipped: " + count(notFixed.size, "song"), 15, bold = true)
            for (t in notFixed) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).clickable { held = t }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Words(t.title, 15, bold = true)
                        Words(t.artist + " · " + t.fileName, 13, K.Soft)
                        Words(data.fixProblems[t.id].orEmpty(), 13, K.Accent, lines = 3)
                    }
                    MoreButton("Everything you can do with it") { held = t }
                }
            }
            val context = LocalContext.current
            Button("Try the stuck ones again", main = false, icon = KueSettings.Icons.FIX, modifier = Modifier.fillMaxWidth()) { Fixer.retryStuck(context) }
        }
    }

    @Composable
    private fun ArtistPage(a: Artist, back: () -> Unit, go: (Screen) -> Unit) {
        val all = remember(a) { a.albums.flatMap { it.tracks } }
        AlbumGrid(a.albums, go) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Glyph.BACK, "Back", onClick = back)
                Words(a.name, 24, bold = true, lines = 2)
                Words(count(a.albums.size, "album") + " · " + count(a.songCount, "song"), 15, K.Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Play all", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) { Playing.play(all, 0) }
                    Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.weight(1f)) { Playing.play(all, 0, shuffled = true) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    @Composable
    private fun FolderPage(s: Screen.FolderPage, data: LibraryData, back: () -> Unit, go: (Screen) -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyph.BACK, "Back", onClick = back)
                Spacer(Modifier.width(14.dp))
                Words(s.name, 20, bold = true, modifier = Modifier.weight(1f))
            }
            Box(Modifier.weight(1f)) { FolderList(s.id, data, go) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Playing

    @Composable
    private fun MiniPlayer(open: () -> Unit) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Raised)
                .clickable(onClick = open).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                val art = Playing.artwork
                if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Sign(Glyph.NOTE, 22.dp, K.Soft)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Words(Playing.title, 15, bold = true)
                Words(if (Playing.problem.isNotEmpty()) Playing.problem else Playing.artist, 13, if (Playing.problem.isNotEmpty()) K.Accent else K.Soft)
            }
            Box(Modifier.size(48.dp).clickable(onClickLabel = if (Playing.isPlaying) "Pause" else "Play") { Playing.toggle() }, contentAlignment = Alignment.Center) {
                if (Playing.buffering) CircularProgressIndicator(color = K.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                else Sign(if (Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 26.dp, K.Ink)
            }
        }
    }

    @Composable
    private fun NowPlaying(close: () -> Unit) {
        var position by remember { mutableLongStateOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        var dragAt by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                if (!dragging) position = Playing.positionMs()
                delay(500)
            }
        }
        if (!Playing.active) {
            LaunchedEffect(Unit) { close() }
            return
        }
        val duration = Playing.durationMs
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyph.DOWN, "Close", onClick = close)
                Words(if (Playing.album.isNotEmpty()) "Playing from " + Playing.album else "", 14, K.Soft, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), centre = true)
                Spacer(Modifier.size(44.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                    val art = Playing.artwork
                    if (art != null) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Sign(Glyph.DISC, 96.dp, K.Soft)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Words(Playing.title, 24, bold = true, lines = 2)
                    Words(Playing.artist, 17, K.Soft)
                    if (Playing.problem.isNotEmpty()) Words(Playing.problem, 15, K.Accent, lines = 2)
                }
                val fav = Sync.isFavourite(Playing.currentId)
                var adding by remember { mutableStateOf(false) }
                val songNow = !Playing.isPodcast && !Playing.isRadio
                if (songNow) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(K.Panel).clickable(onClickLabel = "Add to a playlist") { adding = true },
                    contentAlignment = Alignment.Center,
                ) { KueSettings.Icon(KueSettings.Icons.PLAYLIST, 26.dp, K.Soft, 2f) }
                if (adding) ListUi.AddBox("this song", listOf(Playing.currentId)) { adding = false }
                if (songNow) Spacer(Modifier.width(10.dp))
                if (songNow) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(K.Panel)
                        .clickable(onClickLabel = if (fav) "Take out of favourites" else "Add to favourites") { Sync.flipFavourite(Playing.currentId) },
                    contentAlignment = Alignment.Center,
                ) { Sign(if (fav) Glyph.HEART_FULL else Glyph.HEART, 26.dp, if (fav) K.Accent else K.Soft) }
            }
            Column {
                Slider(
                    value = if (dragging) dragAt else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { dragging = true; dragAt = it },
                    onValueChangeFinished = {
                        Playing.seekTo((dragAt * duration).toLong())
                        position = (dragAt * duration).toLong()
                        dragging = false
                    },
                    colors = SliderDefaults.colors(thumbColor = K.Ink, activeTrackColor = K.Accent, inactiveTrackColor = K.Raised),
                )
                Row {
                    Words(Library.clock(if (dragging) (dragAt * duration).toLong() else position), 14, K.Soft, modifier = Modifier.weight(1f))
                    Words(Library.clock(duration), 14, K.Soft)
                }
            }
            // The tools under the song (Kevin, 25 November 2026): its sound, what plays next, the sleep timer, its details.
            var tool by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolChip(KueSettings.Icons.EQUALISER, "Sound", Sound.profile(Sound.kindOf(Playing.currentId)).let { it.eqOn || it.bass > 0 || it.surround > 0 || it.louder > 0 }, Modifier.weight(1f)) { tool = "sound" }
                if (!Playing.isRadio) ToolChip(KueSettings.Icons.QUEUE, "Up next", false, Modifier.weight(1f)) { tool = "queue" }
                if (!Playing.isRadio && !Playing.isPodcast) {
                    val sleeping = Playing.sleepAt > 0 || Playing.sleepAtEnd
                    val sleepWords = when {
                        Playing.sleepAtEnd -> "End of song"
                        Playing.sleepAt > 0 -> ((Playing.sleepAt - System.currentTimeMillis()) / 60_000 + 1).toString() + " min"
                        else -> "Sleep"
                    }
                    ToolChip(KueSettings.Icons.SLEEP, sleepWords, sleeping, Modifier.weight(1f)) { Playing.nextMusicSleep() }
                    val track = Scanner.data.tracks.firstOrNull { it.id == Playing.currentId }
                    if (track != null) ToolChip(KueSettings.Icons.INFO, "Info", false, Modifier.weight(1f)) { tool = "info" }
                }
            }
            when (tool) {
                "sound" -> SoundBox(Sound.kindOf(Playing.currentId)) { tool = "" }
                "queue" -> UpNext { tool = "" }
                "info" -> Scanner.data.tracks.firstOrNull { it.id == Playing.currentId }?.let { SongInfo(it) { tool = "" } } ?: run { tool = "" }
            }
            if (Playing.isRadio) OnlineUi.RadioControls() else
            if (Playing.isPodcast) PodUi.Controls() else
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Control(Glyph.SHUFFLE, "Shuffle", 48.dp, if (Playing.shuffle) K.Accent else K.Soft) { Playing.flipShuffle() }
                Control(Glyph.PREVIOUS, "Previous", 56.dp, K.Ink) { Playing.previous() }
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(K.Accent).clickable(onClickLabel = if (Playing.isPlaying) "Pause" else "Play") { Playing.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (Playing.buffering) CircularProgressIndicator(color = K.Page, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    else Sign(if (Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 34.dp, K.Page)
                }
                Control(Glyph.NEXT, "Next", 56.dp, K.Ink) { Playing.next() }
                Control(
                    if (Playing.repeat == androidx.media3.common.Player.REPEAT_MODE_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT,
                    "Repeat", 48.dp, if (Playing.repeat != androidx.media3.common.Player.REPEAT_MODE_OFF) K.Accent else K.Soft,
                ) { Playing.nextRepeat() }
            }
        }
    }

    /** A small button under the song: a drawn icon and a word, yellow when it is on. */
    @Composable
    private fun ToolChip(icon: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Column(
            modifier.clip(RoundedCornerShape(14.dp)).background(if (on) K.Accent else K.Panel).clickable(onClickLabel = label, onClick = onClick).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KueSettings.Icon(icon, 22.dp, if (on) K.Page else K.Ink, 2f)
            Words(label, 12, if (on) K.Page else K.Soft)
        }
    }

    /** What is lined up to play: tap one to play it, the cross to take it out. */
    @Composable
    private fun UpNext(onClose: () -> Unit) {
        var lined by remember { mutableStateOf(Playing.queue()) }
        Dialog(onDismissRequest = onClose) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("Up next", 19, bold = true)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(lined, key = { it.index }) { q ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (q.now) K.Panel else Color.Transparent)
                                .clickable { Playing.jumpTo(q.index); lined = Playing.queue() }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Words(q.title, 15, if (q.now) K.Accent else K.Ink, bold = q.now)
                                if (q.artist.isNotEmpty()) Words(q.artist, 13, K.Soft)
                            }
                            if (!q.now) Box(Modifier.size(40.dp).clickable(onClickLabel = "Take it out") { Playing.removeAt(q.index); lined = Playing.queue() }, contentAlignment = Alignment.Center) {
                                KueSettings.Icon(KueSettings.Icons.CROSS, 18.dp, K.Soft, 2.2f)
                            }
                        }
                    }
                }
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }

    /** The sound settings for music or for podcasts, in a box over Now playing. */
    @Composable
    private fun SoundBox(kind: Sound.Kind, onClose: () -> Unit) {
        Dialog(onDismissRequest = onClose) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Words("Sound for " + kind.label.lowercase(), 19, bold = true)
                SoundPanel(kind)
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }

    /**
     * The graphic equaliser and the rest, for one profile (Kevin, 25 November 2026): on or off, a ready-made setting or
     * your own, the five bands to set by hand, then deep bass, surround and louder; for podcasts, skipping silences.
     */
    @Composable
    fun SoundPanel(kind: Sound.Kind) {
        @Suppress("UNUSED_VARIABLE") val changed = Sound.changes
        val p = Sound.profile(kind)
        var presetsOpen by remember { mutableStateOf(false) }
        KueSettings.Toggle("Equaliser", "Shapes the sound: more bass, clearer voices, and the rest.", p.eqOn, look) { on -> Sound.change(kind) { it.copy(eqOn = on) } }
        KueSettings.FanOut("Setting", Sound.presets.map { it.name }, p.preset, presetsOpen, look, { presetsOpen = it }) { i ->
            presetsOpen = false
            // Picking your own starts from the setting in use, so it can be changed a little.
            Sound.change(kind) { it.copy(eqOn = true, preset = i, own = if (i == Sound.OWN && it.preset != Sound.OWN) it.levels else it.own) }
        }
        val levels = p.levels
        Sound.bands.forEachIndexed { b, hz ->
            val shown = if (p.eqOn) levels[b] else 0f
            Column {
                Row {
                    Words(Sound.bandNames[b] + " · " + (if (hz >= 1000) (if (hz % 1000 == 0) (hz / 1000).toString() else String.format("%.1f", hz / 1000f)) + " kHz" else "$hz Hz"), 14, K.Soft, modifier = Modifier.weight(1f))
                    Words((if (shown > 0) "+" else "") + String.format("%.0f", shown) + " dB", 14, if (shown != 0f) K.Accent else K.Soft)
                }
                Slider(
                    value = shown, valueRange = -Sound.RANGE..Sound.RANGE, steps = (Sound.RANGE * 2).toInt() - 1,
                    onValueChange = { v -> Sound.change(kind) { it.copy(eqOn = true, preset = Sound.OWN, own = it.levels.toMutableList().also { l -> l[b] = v }) } },
                    colors = SliderDefaults.colors(thumbColor = K.Ink, activeTrackColor = K.Accent, inactiveTrackColor = K.Raised),
                )
            }
        }
        val extra: @Composable (String, Int, (Int) -> Sound.Profile) -> Unit = { label, value, set ->
            Column {
                Row {
                    Words(label, 14, K.Soft, modifier = Modifier.weight(1f))
                    Words(if (value == 0) "Off" else "$value%", 14, if (value > 0) K.Accent else K.Soft)
                }
                Slider(
                    value = value.toFloat(), valueRange = 0f..100f, steps = 9,
                    onValueChange = { v -> Sound.save(kind, set(v.toInt())) },
                    colors = SliderDefaults.colors(thumbColor = K.Ink, activeTrackColor = K.Accent, inactiveTrackColor = K.Raised),
                )
            }
        }
        extra("Deep bass", p.bass) { p.copy(bass = it) }
        extra("Surround, for headphones", p.surround) { p.copy(surround = it) }
        extra("Louder", p.louder) { p.copy(louder = it) }
        if (kind == Sound.Kind.PODCASTS) {
            KueSettings.Toggle("Skip silences", "Gaps in an episode are skipped over, so it takes less time.", p.skipSilence, look) { on -> Sound.change(kind) { it.copy(skipSilence = on) } }
        }
        Button("Back to how it was made", main = false, modifier = Modifier.fillMaxWidth()) { Sound.save(kind, Sound.Profile()) }
        if (Sound.missing.isNotEmpty()) KueSettings.Note("This phone doesn't have " + Sound.missing.joinToString(", ") + ", so those don't change anything.", look)
    }

    /**
     * Settings › Backup: when KueSounds was last backed up to the Drive, Back up now, and Bring back a backup
     * (asked twice, as it replaces what is on this phone).
     */
    @Composable
    private fun BackupPanel() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var sure by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val at = Backup.lastAt
            Words(if (at == 0L) "Not backed up yet" else "Backed up " + java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.UK).format(java.util.Date(at)), 17)
            Words(
                "Your settings, the podcasts you follow and where you got to, favourites, and your music's details as looked up, kept in your Drive as \"" +
                    Backup.FILE_NAME + "\". Done by itself once a day. Not your sign-in, songs or covers.",
                14, K.Soft, lines = 6,
            )
            if (Backup.busy.isNotEmpty()) Words(Backup.busy, 15, K.Accent)
            if (Backup.problem.isNotEmpty()) Words(Backup.problem, 15, K.Accent, lines = 3)
            Spacer(Modifier.height(10.dp))
            if (Backup.busy.isEmpty()) {
                Button("Back up now", modifier = Modifier.fillMaxWidth()) { scope.launch { withContext(Dispatchers.IO) { runCatching { Backup.now() } } } }
                Spacer(Modifier.height(8.dp))
                Button(if (sure) "Yes, replace what's on this phone" else "Bring back a backup", main = false, modifier = Modifier.fillMaxWidth()) {
                    if (!sure) sure = true
                    else scope.launch {
                        sure = false
                        val ok = withContext(Dispatchers.IO) { Backup.restore() }
                        if (ok) Backup.restart(context)
                    }
                }
                if (sure) Words("This puts back the backup from your Drive in place of this phone's settings, shows and music details, then KueSounds starts again.", 14, K.Soft, lines = 4)
            }
        }
    }

    @Composable
    private fun Control(glyph: Glyph, label: String, size: Dp, colour: Color, onClick: () -> Unit) {
        Box(Modifier.size(size).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Sign(glyph, size * 0.55f, colour)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings

    /**
     * The four steps KueSounds takes with your music, each saying what it does and how far it has got.
     * Steps 1 to 3 only read; the files in the Drive are changed only at step 4, when Fix is pressed.
     */
    @Composable
    private fun MusicSteps(view: Scanner.LibraryView, go: (Screen) -> Unit) {
        val data = Scanner.data
        val songs = data.tracks.size
        val read = data.tracks.count { it.tagged }
        val albums = view.albums.size
        val stage = Scanner.stage
        val n = { x: Int -> String.format("%,d", x) }
        // Short and plain, with a spinner on the step that is working (Kevin, 25 November 2026). Fixing has its own section.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Words("This only reads your music. Files change only when you press Fix.", 14, K.Soft, lines = 2)
            Step(1, "Find your music", "", state = if (stage == 1) NOW else if (songs > 0) DONE else WAITING, progress = if (songs > 0 && stage != 1) count(songs, "song") + " found" else "")
            Step(2, "Read the song details", "", state = if (stage == 2) NOW else if (songs > 0 && read >= songs) DONE else WAITING, progress = n(read) + " of " + n(songs))
            val singlesChecked = data.tracks.count { it.singleChecked }
            Step(
                3, "Look up albums, covers and singles", "",
                state = if (stage == 3) NOW else if (albums > 0 && view.lookedUp >= albums && singlesChecked >= songs) DONE else WAITING,
                progress = n(view.lookedUp) + " of " + n(albums) + " albums · " + n(view.withCover) + " with a cover" +
                    (if (singlesChecked < songs) "\nSingles: " + n(singlesChecked) + " of " + n(songs) + " songs checked" else "") +
                    (if (Scanner.waitingForWifi && !Scanner.running) "\nSingles carry on when the phone is on Wi-Fi." else "") +
                    (if (!Scanner.running && Scanner.problem.isNotEmpty()) "\n" + Scanner.problem else ""),
            )
            // Albums the catalogue didn't find, as a list to put right (Kevin, 25 November 2026: a miss is a spelling).
            val missed = remember(view, data) { Scanner.notFound(view, data) }
            var showMissed by remember { mutableStateOf(false) }
            if (missed.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).clickable { showMissed = true }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Words(count(missed.size, "album") + " not found in the catalogue", 15, bold = true, lines = 2)
                        Words("Usually a name spelt differently. Tap to see them and put the name right.", 13, K.Soft, lines = 2)
                    }
                    KueSettings.Icon(KueSettings.Icons.CHEVRON, 18.dp, K.Soft, 2.2f)
                }
            }
            if (showMissed) AlbumListBox(
                "Not found in the catalogue",
                if (SoundMatch.key.isBlank()) "Add the AcoustID key in Match by sound below, and these are matched by their sound. Tap one to open it."
                else "These are matched by their sound on Wi-Fi, by themselves. Tap one to open it; its ⋮ has Edit album name and artist.",
                missed, { showMissed = false },
                action = if (SoundMatch.key.isNotBlank() && !Scanner.running) ("Match these by sound now" to { showMissed = false; Scanner.scan() }) else null,
            ) { al ->
                showMissed = false
                go(Screen.AlbumPage(al.key))
            }
        }
    }

    /** A list of albums in a box: tapping one opens it. */
    @Composable
    private fun AlbumListBox(title: String, below: String, albums: List<Album>, onClose: () -> Unit, action: Pair<String, () -> Unit>? = null, onPick: (Album) -> Unit) {
        Dialog(onDismissRequest = onClose) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words(title, 19, bold = true, lines = 2)
                Words(below, 14, K.Soft, lines = 3)
                if (action != null) Button(action.first, icon = KueSettings.Icons.EQUALISER, modifier = Modifier.fillMaxWidth(), onClick = action.second)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(albums, key = { it.key }) { al ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(al) }.padding(vertical = 8.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(Scanner.artFor(al), Modifier.size(44.dp), corner = 8.dp, glyphSize = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Words(al.title, 15, bold = true)
                                Words(al.artist + " · " + count(al.tracks.size, "song"), 13, K.Soft)
                            }
                        }
                    }
                }
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }

    private const val DONE = 2
    private const val NOW = 1
    private const val WAITING = 0

    /** One step: its number (a tick once done), its name, what it does, and how far it has got. */
    @Composable
    private fun Step(number: Int, name: String, what: String, state: Int, progress: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (state == WAITING) K.Page else K.Accent),
                contentAlignment = Alignment.Center,
            ) {
                if (state == DONE) KueSettings.Icon(KueSettings.Icons.TICK, 16.dp, K.Page, 2.6f) else Words(number.toString(), 14, if (state == WAITING) K.Soft else K.Page, bold = true)
            }
            Column(Modifier.weight(1f)) {
                Words(name + when (state) { DONE -> " · done"; NOW -> " · now"; else -> "" }, 16, if (state == NOW) K.Accent else K.Ink, bold = true)
                if (what.isNotEmpty()) Words(what, 14, K.Soft, lines = 8)
                if (progress.isNotEmpty()) Words(progress, 14, if (state == NOW) K.Accent else K.Ink, lines = 10)
                if (state == NOW && Scanner.running) {
                    // Turning while it works, and how far through the part it is on.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                        Words(Scanner.status.ifBlank { "Working…" }, 14, K.Accent, lines = 2, modifier = Modifier.weight(1f))
                        val p = Scanner.progress
                        if (p >= 0f) Words((p * 100).toInt().toString() + "%", 16, K.Accent, bold = true)
                    }
                    val p = Scanner.progress
                    if (p >= 0f) androidx.compose.material3.LinearProgressIndicator(
                        progress = { p }, color = K.Accent, trackColor = K.Page, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                    Words(
                        "Keep KueSounds open: if it is stopped, it carries on from here next time.",
                        13, K.Soft, lines = 2,
                    )
                }
            }
        }
    }

    /** Every Kue app's settings in the same eight groups (see KueSettings). One group open at a time. */
    private val look = KueSettings.Look(
        page = K.Page, panel = K.Panel, raised = K.Raised, accent = K.Accent, onAccent = K.Page, ink = K.Ink,
        soft = { K.Soft }, font = K.Poppins,
    )

    /** Every setting, for Search all settings: group id, group name, section (if it has one), setting. */
    private val settingsPlaces = listOf(
        KueSettings.Place("account", "Account", null, "Google account"),
        KueSettings.Place("account", "Account", null, "Sign-in and password"),
        KueSettings.Place("app", "KueSounds", "Your music", "Songs and albums"),
        KueSettings.Place("app", "KueSounds", "Your music", "Covers"),
        KueSettings.Place("app", "KueSounds", "Your music", "Look for new music"),
        KueSettings.Place("app", "KueSounds", "Fix the files", "Fix them"),
        KueSettings.Place("app", "KueSounds", "Fix the files", "Albums still to fix"),
        KueSettings.Place("app", "KueSounds", "Match by sound", "AcoustID key"),
        KueSettings.Place("app", "KueSounds", "Match by sound", "Match by sound, like Shazam"),
        KueSettings.Place("app", "KueSounds", "Sound for music", "Equaliser for music"),
        KueSettings.Place("app", "KueSounds", "Sound for music", "Bass, surround and louder for music"),
        KueSettings.Place("app", "KueSounds", "Sound for podcasts and radio", "Equaliser for podcasts and radio"),
        KueSettings.Place("app", "KueSounds", "Sound for podcasts and radio", "Skip silences in podcasts"),
        KueSettings.Place("look", "Look and feel", null, "Text size"),
        KueSettings.Place("look", "Look and feel", null, "Stronger contrast"),
        KueSettings.Place("notices", "Notifications", null, "Progress in the pull-down"),
        KueSettings.Place("backup", "Backup and sync", "Backup", "Back up now"),
        KueSettings.Place("backup", "Backup and sync", "Backup", "Bring back a backup"),
        KueSettings.Place("backup", "Backup and sync", "Favourites", "Favourites synced with the Drive"),
        KueSettings.Place("privacy", "Privacy and storage", "Space on this phone", "Songs saved to the phone"),
        KueSettings.Place("privacy", "Privacy and storage", "What leaves this phone", "What leaves this phone"),
        KueSettings.Place("privacy", "Privacy and storage", null, "Permissions"),
        KueSettings.Place("kue", "Your Kue apps", null, "KueTV"),
        KueSettings.Place("kue", "Your Kue apps", null, "KueVault"),
        KueSettings.Place("help", "Help and about", "Version and updates", "Version"),
        KueSettings.Place("help", "Help and about", "Version and updates", "Check for updates"),
        KueSettings.Place("help", "Help and about", "Report for Claude", "Copy report for Claude"),
    )

    @Composable
    private fun Settings(back: () -> Unit, go: (Screen) -> Unit) {
        val data = Scanner.data
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val askedFor = remember { settingsAt.also { settingsAt = null } }
        var open by rememberSaveable { mutableStateOf(askedFor?.substringBefore('|') ?: "app") }
        val toggle = { id: String -> open = if (open == id) "" else id }
        val stamp = { at: Long -> java.text.SimpleDateFormat("HH:mm, d MMM", java.util.Locale.UK).format(java.util.Date(at)) }
        // Sections inside a group fold away too (Kevin, 25 November 2026); Search all settings opens the right one.
        var sections by rememberSaveable { mutableStateOf(askedFor?.let { "\n" + it } ?: "") }
        // One open section a line. Not "|" between them: a section's own id has a "|" in it ("app|Fix the files"),
        // so split on "|" no section ever counted as open and none could be opened (Kevin, 25 November 2026).
        val sectionOpen = { id: String -> id in sections.split("\n") }
        val flip = { id: String -> sections = if (sectionOpen(id)) sections.split("\n").filter { it != id }.joinToString("\n") else sections + "\n" + id }
        var query by rememberSaveable { mutableStateOf("") }
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                RoundButton(Glyph.BACK, "Back", onClick = back)
                Spacer(Modifier.width(14.dp))
                Words("Settings", 22, bold = true)
            }
            KueSettings.Search(query, settingsPlaces, look, { query = it }) { p ->
                query = ""
                open = p.group
                if (p.section != null && !sectionOpen(p.group + "|" + p.section)) flip(p.group + "|" + p.section)
            }

            // 1. Account
            KueSettings.Group(KueSettings.Icons.ACCOUNT, "Account", Google.email.ifBlank { "Signed in" }, open == "account", look, { toggle("account") }) {
                KueSettings.Fact("Google account", Google.email.ifBlank { "Signed in" }, look)
                KueSettings.Note("Your password and sign-in are kept by Google, not KueSounds.", look)
            }

            // 2. KueSounds itself
            val view = Scanner.view
            KueSettings.Group(
                KueSettings.Icons.APP, "KueSounds",
                count(data.tracks.size, "song") + " · " + String.format("%,d", view.leftToFix) + " albums to fix",
                open == "app", look, { toggle("app") },
            ) {
                KueSettings.Section("Your music", sectionOpen("app|Your music"), look, { flip("app|Your music") }) {
                    MusicSteps(view, go)
                    if (!Scanner.running) Button("Look for new music", main = false, modifier = Modifier.fillMaxWidth()) { Scanner.scan() }
                }
                KueSettings.Section("Fix the files", sectionOpen("app|Fix the files"), look, { flip("app|Fix the files") }) { FixPanel(view, go) }
                KueSettings.Section("Match by sound", sectionOpen("app|Match by sound"), look, { flip("app|Match by sound") }) {
                    var key by remember { mutableStateOf(SoundMatch.key) }
                    Words("KueSounds listens to a song and finds out what it is, as Shazam does. It uses AcoustID's free song database, which needs its key.", 14, K.Soft, lines = 4)
                    Words("AcoustID key", 13, K.Soft)
                    EditField(key) { key = it.trim(); SoundMatch.key = key }
                    Words(if (key.isBlank()) "No key yet. Get it at acoustid.org, under Applications." else "Key saved.", 13, if (key.isBlank()) K.Accent else K.Soft, lines = 2)
                }
                KueSettings.Section("Sound for music", sectionOpen("app|Sound for music"), look, { flip("app|Sound for music") }) { SoundPanel(Sound.Kind.MUSIC) }
                KueSettings.Section("Sound for podcasts and radio", sectionOpen("app|Sound for podcasts and radio"), look, { flip("app|Sound for podcasts and radio") }) {
                    SoundPanel(Sound.Kind.PODCASTS)
                }
            }

            // 3. Look and feel
            KueSettings.Group(
                KueSettings.Icons.LOOK, "Look and feel",
                "Text size " + (KueSettings.textStep + 1) + " of 5" + if (KueSettings.contrast) " · stronger contrast" else "",
                open == "look", look, { toggle("look") },
            ) {
                KueSettings.Scale("Text size", 5, KueSettings.textStep, look) { KueSettings.chooseTextStep(it) }
                KueSettings.Toggle("Stronger contrast", "Grey writing drawn nearly white, easier to read.", KueSettings.contrast, look) { KueSettings.chooseContrast(it) }
            }

            // 4. Notifications
            val on = Fixer.noticesOn
            KueSettings.Group(KueSettings.Icons.NOTICES, "Notifications", "Progress in the pull-down: " + if (on) "on" else "off", open == "notices", look, { toggle("notices") }) {
                KueSettings.Toggle(
                    "Progress in the pull-down",
                    "Shows how looking through your music, saving to the phone and fixing the files are going, while KueSounds is closed.",
                    on, look,
                ) {
                    if (on) runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else Fixer.askNotices()
                }
                if (on) KueSettings.Note("Turning it off is done in the phone's own settings, which open when you tap it.", look)
            }

            // 5. Backup and sync
            KueSettings.Group(
                KueSettings.Icons.BACKUP, "Backup and sync",
                if (Backup.lastAt == 0L) "Not backed up yet" else "Backed up " + stamp(Backup.lastAt),
                open == "backup", look, { toggle("backup") },
            ) {
                KueSettings.Section("Backup", sectionOpen("backup|Backup"), look, { flip("backup|Backup") }) { BackupPanel() }
                KueSettings.Section("Favourites", sectionOpen("backup|Favourites"), look, { flip("backup|Favourites") }) {
                    val when_ = Sync.syncedAt
                    KueSettings.Note(if (when_ == 0L) "Not synced with the Drive yet." else "Synced with the Drive at " + stamp(when_), look)
                }
            }

            // 6. Privacy and storage
            val saved = Offline.saved
            val bytes = remember(saved) { Offline.bytesUsed() }
            KueSettings.Group(
                KueSettings.Icons.PRIVACY, "Privacy and storage",
                count(saved.size, "song") + " saved · " + String.format("%.1f", bytes / 1_000_000_000.0) + " GB",
                open == "privacy", look, { toggle("privacy") },
            ) {
                KueSettings.Section("Space on this phone", sectionOpen("privacy|Space on this phone"), look, { flip("privacy|Space on this phone") }) {
                    Words(count(saved.size, "song") + " saved · " + String.format("%.1f", bytes / 1_000_000_000.0) + " GB", 17)
                    if (Offline.status.isNotEmpty()) Words(Offline.status, 14, K.Accent, lines = 2)
                }
                KueSettings.Section("What leaves this phone", sectionOpen("privacy|What leaves this phone"), look, { flip("privacy|What leaves this phone") }) {
                    KueSettings.Note(
                        "Your songs stay in your Google Drive. Album and song names go to the MusicBrainz music catalogue and its cover " +
                            "library to look them up. The podcasts, radio stations and videos you choose are fetched from where they are " +
                            "published. KueSounds checks GitHub for new versions. Nothing else.",
                        look,
                    )
                }
                Button("Permissions", main = false, modifier = Modifier.fillMaxWidth()) { KueSettings.openPermissions(context) }
            }

            // 7. Your Kue apps
            KueSettings.Group(KueSettings.Icons.KUE, "Your Kue apps", "KueTV, KueVault", open == "kue", look, { toggle("kue") }) {
                KueSettings.KueApps(context.packageName, look)
            }

            // 8. Help and about
            KueSettings.Group(KueSettings.Icons.HELP, "Help and about", "Version " + BuildConfig.VERSION_NAME, open == "help", look, { toggle("help") }) {
                var copied by remember { mutableStateOf(false) }
                // Checking by hand, as well as the pop-up: finds a newer version and puts it on now.
                var updateSays by remember { mutableStateOf("") }
                var checking by remember { mutableStateOf(false) }
                KueSettings.Section("Version and updates", sectionOpen("help|Version and updates"), look, { flip("help|Version and updates") }) {
                KueSettings.Fact("Version", BuildConfig.VERSION_NAME, look)
                Button(if (checking) "Checking…" else "Check for updates", main = false, modifier = Modifier.fillMaxWidth()) {
                    if (checking) return@Button
                    checking = true
                    scope.launch {
                        updateSays = Updates.updateByHand(context) { updateSays = it }
                        checking = false
                    }
                }
                if (updateSays.isNotEmpty()) Words(updateSays, 14, K.Accent, lines = 3)
                }
                KueSettings.Section("Report for Claude", sectionOpen("help|Report for Claude"), look, { flip("help|Report for Claude") }) {
                Button(if (copied) "Copied. Paste it to Claude." else "Copy report for Claude", main = false, modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        val text = withContext(Dispatchers.Default) { Scanner.report() }
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip?.setPrimaryClip(android.content.ClipData.newPlainText("KueSounds report", text))
                        copied = true
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun Panel(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

/** The small drawn signs used on buttons and rows. */
enum class Glyph { PLAY, PAUSE, NEXT, PREVIOUS, SHUFFLE, REPEAT, REPEAT_ONE, BACK, DOWN, CLOSE, SEARCH, GEAR, NOTE, DISC, FOLDER, PERSON, SAVE, HEART, HEART_FULL }

@Composable
fun Sign(glyph: Glyph, size: Dp, colour: Color) {
    Canvas(Modifier.size(size)) { drawGlyph(glyph, colour) }
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val w = size.width
    val u = w / 24f
    val line = Stroke(width = 2f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false): Path = Path().apply {
        moveTo(pts[0] * u, pts[1] * u)
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i] * u, pts[i + 1] * u)
            i += 2
        }
        if (close) close()
    }
    when (g) {
        Glyph.PLAY -> drawPath(p(7f, 4f, 20f, 12f, 7f, 20f, close = true), c)
        Glyph.PAUSE -> {
            drawRoundRect(c, Offset(6f * u, 4f * u), Size(4f * u, 16f * u), androidx.compose.ui.geometry.CornerRadius(u))
            drawRoundRect(c, Offset(14f * u, 4f * u), Size(4f * u, 16f * u), androidx.compose.ui.geometry.CornerRadius(u))
        }
        Glyph.NEXT -> {
            drawPath(p(5f, 5f, 16f, 12f, 5f, 19f, close = true), c)
            drawRect(c, Offset(16f * u, 5f * u), Size(2.5f * u, 14f * u))
        }
        Glyph.PREVIOUS -> {
            drawPath(p(19f, 5f, 8f, 12f, 19f, 19f, close = true), c)
            drawRect(c, Offset(5.5f * u, 5f * u), Size(2.5f * u, 14f * u))
        }
        Glyph.SHUFFLE -> {
            drawPath(p(3f, 7f, 7f, 7f, 17f, 17f, 21f, 17f), c, style = line)
            drawPath(p(3f, 17f, 7f, 17f, 17f, 7f, 21f, 7f), c, style = line)
            drawPath(p(18f, 4f, 21f, 7f, 18f, 10f), c, style = line)
            drawPath(p(18f, 14f, 21f, 17f, 18f, 20f), c, style = line)
        }
        Glyph.REPEAT, Glyph.REPEAT_ONE -> {
            drawPath(p(4f, 11f, 4f, 7f, 20f, 7f), c, style = line)
            drawPath(p(17f, 4f, 20f, 7f, 17f, 10f), c, style = line)
            drawPath(p(20f, 13f, 20f, 17f, 4f, 17f), c, style = line)
            drawPath(p(7f, 14f, 4f, 17f, 7f, 20f), c, style = line)
            if (g == Glyph.REPEAT_ONE) drawPath(p(11f, 11f, 12.5f, 10f, 12.5f, 14f), c, style = Stroke(1.6f * u, cap = StrokeCap.Round))
        }
        Glyph.BACK -> drawPath(p(15f, 5f, 8f, 12f, 15f, 19f), c, style = line)
        Glyph.DOWN -> drawPath(p(6f, 9f, 12f, 15f, 18f, 9f), c, style = line)
        Glyph.CLOSE -> {
            drawPath(p(6f, 6f, 18f, 18f), c, style = line)
            drawPath(p(18f, 6f, 6f, 18f), c, style = line)
        }
        Glyph.SEARCH -> {
            drawCircle(c, 7f * u, Offset(11f * u, 11f * u), style = line)
            drawPath(p(16f, 16f, 20f, 20f), c, style = line)
        }
        Glyph.GEAR -> {
            drawCircle(c, 3f * u, Offset(12f * u, 12f * u), style = line)
            drawCircle(c, 7.5f * u, Offset(12f * u, 12f * u), style = line)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val x1 = 12f + 7.5f * Math.cos(a).toFloat()
                val y1 = 12f + 7.5f * Math.sin(a).toFloat()
                val x2 = 12f + 10f * Math.cos(a).toFloat()
                val y2 = 12f + 10f * Math.sin(a).toFloat()
                drawPath(p(x1, y1, x2, y2), c, style = Stroke(2.6f * u, cap = StrokeCap.Round))
            }
        }
        Glyph.NOTE -> {
            drawPath(p(9f, 18f, 9f, 5f, 20f, 3f, 20f, 16f), c, style = line)
            drawCircle(c, 3f * u, Offset(6.5f * u, 18f * u))
            drawCircle(c, 3f * u, Offset(17.5f * u, 16f * u))
        }
        Glyph.DISC -> {
            drawCircle(c, 9f * u, Offset(12f * u, 12f * u), style = Stroke(1.5f * u))
            drawCircle(c, 2.5f * u, Offset(12f * u, 12f * u), style = Stroke(1.5f * u))
        }
        Glyph.FOLDER -> drawPath(p(3f, 7f, 3f, 19f, 21f, 19f, 21f, 9f, 11f, 9f, 9f, 6f, 3f, 6f, close = true), c, style = line)
        Glyph.SAVE -> {
            drawPath(p(12f, 4f, 12f, 15f), c, style = line)
            drawPath(p(7f, 10f, 12f, 15f, 17f, 10f), c, style = line)
            drawPath(p(5f, 20f, 19f, 20f), c, style = line)
        }
        Glyph.HEART, Glyph.HEART_FULL -> {
            val heart = Path().apply {
                moveTo(12f * u, 20f * u)
                cubicTo(4f * u, 14.5f * u, 3f * u, 10f * u, 4.5f * u, 7f * u)
                cubicTo(6.5f * u, 3.5f * u, 10.5f * u, 4f * u, 12f * u, 7.5f * u)
                cubicTo(13.5f * u, 4f * u, 17.5f * u, 3.5f * u, 19.5f * u, 7f * u)
                cubicTo(21f * u, 10f * u, 20f * u, 14.5f * u, 12f * u, 20f * u)
                close()
            }
            if (g == Glyph.HEART_FULL) drawPath(heart, c) else drawPath(heart, c, style = line)
        }
        Glyph.PERSON -> {
            drawCircle(c, 4f * u, Offset(12f * u, 8f * u), style = line)
            drawPath(p(4f, 21f, 4f, 19f, 8f, 15f, 16f, 15f, 20f, 19f, 20f, 21f), c, style = line)
        }
    }
}
