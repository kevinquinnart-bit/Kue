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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
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
    /** The page: dark blue, or black when chosen in Settings › Look and feel (CloudPlayer's black theme). */
    val Page: Color get() = if (PlayPrefs.changes >= 0 && PlayPrefs.black) Color.Black else Color(0xFF0E1626)
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
    data class GenrePage(val name: String) : Screen()
    data class ComposerPage(val name: String) : Screen()
    data object NowPlaying : Screen()
    data object Settings : Screen()
    data object PodcastFind : Screen()
    /** Name that song: the microphone, or what another app on the phone is playing (26 November 2026). */
    data object NameSong : Screen()
    data class ShowPage(val feed: String) : Screen()
    data class EpisodePage(val id: String) : Screen()
    data class PlaylistPage(val id: String) : Screen()
    /** YouTube's player. [tries]: found videos for a song, tried in turn when one can't play inside KueSounds. */
    data class YouTubePlayer(val url: String, val tries: List<String> = emptyList(), val name: String = "") : Screen()
}

/** The parts of the music, shown under Music (Kevin, 25 November 2026: all music under one category). */
// Singles are shown among the songs, each with its single's cover (Kevin, 25 November 2026: one less part).
val musicViews = listOf(Tab.ALBUMS, Tab.SONGS, Tab.ARTISTS, Tab.GENRES, Tab.COMPOSERS, Tab.PLAYLISTS, Tab.FAVOURITES, Tab.ON_PHONE, Tab.FOLDERS)

/** The categories along the top: Music, then everything that isn't your own music. */
val topTabs = listOf(Tab.PODCASTS, Tab.RADIO, Tab.SPOTIFY, Tab.YOUTUBE)

enum class Tab(val label: String) {
    // The music first (Kevin, 25 November 2026), then everything else, in the order it was before.
    ALBUMS("Albums"), SONGS("Songs"), ARTISTS("Artists"), GENRES("Genres"), COMPOSERS("Composers"), PLAYLISTS("Playlists"), FAVOURITES("Favourites"), ON_PHONE("On phone"),
    PODCASTS("Podcasts"), RADIO("Radio"), SPOTIFY("Spotify"), YOUTUBE("YouTube"), FOLDERS("Folders"),
}

object Ui {
    /** Where Settings opens next: a group and section ("app|Your music"), set by tapping the message on the main screen. */
    var settingsAt by mutableStateOf<String?>(null)

    /** A link shared to KueSounds from another app, to open. */
    var sharedLink by mutableStateOf<String?>(null)

    /** A song's video found by Watch it on YouTube, to open in KueSounds' own YouTube player. */
    var watch by mutableStateOf<Screen.YouTubePlayer?>(null)

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
            noEmoji(text), modifier = modifier, color = colour, fontSize = size.sp, fontFamily = K.Poppins,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis,
            textAlign = if (centre) TextAlign.Center else null, lineHeight = (size * 1.3f).sp,
        )
    }

    /**
     * Words with any emoji taken out (Kevin, 26 November 2026: "never use stock emojis in apps I build", which takes in
     * the ones in other people's names for things, such as Spotify playlists called by a picture). Signs such as ©
     * and ® stay.
     */
    fun noEmoji(text: String): String = Plain.noEmoji(text)

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
        // Each screen in the stack keeps what it had (what was searched, how far down a list was scrolled), so Back
        // or Close returns to it just as it was left (Kevin, 26 November 2026: back to the search results, not the start).
        val kept = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val keyAt = { i: Int, sc: Screen -> "$i:$sc" }
        val go: (Screen) -> Unit = { stack = stack + it }
        val back: () -> Unit = {
            if (stack.size > 1) {
                kept.removeState(keyAt(stack.lastIndex, stack.last()))
                stack = stack.dropLast(1)
            }
        }
        // A link shared to KueSounds from YouTube, YouTube Music or Spotify.
        val shared = sharedLink
        LaunchedEffect(shared) {
            if (shared == null) return@LaunchedEffect
            sharedLink = null
            if (YouTube.read(shared) != null) go(Screen.YouTubePlayer(shared))
            else if (!SpotifyLink.playLink(shared)) Unit
        }
        BackHandler(enabled = stack.size > 1, onBack = back)
        val wanted = watch
        LaunchedEffect(wanted) {
            if (wanted == null) return@LaunchedEffect
            watch = null
            go(wanted)
        }

        val data = Scanner.data
        val view = Scanner.view
        val albums = view.albums
        val artists = view.artists
        val songs = view.songs

        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                val top = stack.last()
                kept.SaveableStateProvider(keyAt(stack.lastIndex, top)) {
                when (val s = top) {
                    Screen.Home -> Home(albums, artists, songs, go)
                    is Screen.AlbumPage -> (view.byKey[s.key] ?: Scanner.renamed[s.key]?.let { id -> albums.firstOrNull { al -> al.tracks.any { it.id == id } } })
                        ?.let { AlbumPage(it, back, go) } ?: Gone(back)
                    is Screen.ArtistPage -> artists.firstOrNull { it.name == s.name }?.let { ArtistPage(it, back, go) } ?: Gone(back)
                    is Screen.FolderPage -> FolderPage(s, data, back, go)
                    is Screen.GenrePage -> GenrePage(s.name, albums, back, go)
                    is Screen.ComposerPage -> ComposerPage(s.name, back, go)
                    Screen.NowPlaying -> NowPlaying(back, go)
                    Screen.Settings -> Settings(back, go)
                    Screen.PodcastFind -> PodUi.Find(back, go)
                    Screen.NameSong -> NameUi.Page(back)
                    is Screen.ShowPage -> PodUi.ShowPage(s.feed, back, go)
                    is Screen.EpisodePage -> PodUi.EpisodePage(s.id, back, go)
                    is Screen.PlaylistPage -> ListUi.Page(s.id, back)
                    is Screen.YouTubePlayer -> OnlineUi.YouTubePlayer(s.url, back, s.tries, s.name)
                }
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
        // Opens on the part chosen in Settings (CloudPlayer's "default screen").
        var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.name == PlayPrefs.startPart } ?: Tab.ALBUMS) }
        var query by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The K and the name take you home: Albums, nothing searched (Kevin, 25 November 2026).
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Home") { tab = Tab.ALBUMS; query = "" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mark(36.dp)
                        Spacer(Modifier.width(10.dp))
                        Brand(24)
                    }
                    // Name that song, beside Settings (Kevin, 26 November 2026).
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable(onClickLabel = "Name that song") { go(Screen.NameSong) },
                        contentAlignment = Alignment.Center,
                    ) { KueSettings.Icon(KueSettings.Icons.MIC, 22.dp, K.Ink, 2f) }
                    Spacer(Modifier.width(8.dp))
                    RoundButton(Glyph.GEAR, "Settings") { go(Screen.Settings) }
                }
                SearchBox(query) { query = it }
                if (query.isBlank()) {
                    var choosingView by remember { mutableStateOf(false) }
                    // The music part last shown, so Music opens where it was left.
                    var lastMusic by rememberSaveable { mutableStateOf(if (tab in musicViews) tab else Tab.ALBUMS) }
                    if (tab in musicViews) lastMusic = tab
                    val inMusic = tab in musicViews
                    // The Kue rows of choices, the same in every Kue app (KueSettings.MainChip and SubChip).
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { KueSettings.MainChip("Music", inMusic, look) { tab = lastMusic } }
                        items(topTabs) { t -> KueSettings.MainChip(t.label, t == tab, look) { tab = t } }
                    }
                    // Under Music: its parts, and how the one showing is laid out.
                    if (inMusic) Row(verticalAlignment = Alignment.CenterVertically) {
                        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // The parts chosen in Settings (CloudPlayer's "navigation drawer: show or hide items").
                            val hidden = PlayPrefs.changes.let { PlayPrefs.hiddenParts }
                            items(musicViews.filter { it.name !in hidden || it == tab }) { t -> KueSettings.SubChip(t.label, t == tab, look) { tab = t } }
                        }
                        // How this part is shown: a list with small pictures, or large covers (Kevin, 25 Nov 2026).
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
                    tab == Tab.ALBUMS -> AlbumsPart(albums, Tab.ALBUMS, go)
                    tab == Tab.ARTISTS -> ArtistList(artists, Views.layout(Tab.ARTISTS), go)
                    tab == Tab.GENRES -> GenreList(albums, Views.layout(Tab.GENRES), go)
                    tab == Tab.COMPOSERS -> ComposerList(Views.layout(Tab.COMPOSERS), go)
                    tab == Tab.SONGS -> SongsPart(songs, Tab.SONGS, go)
                    tab == Tab.FAVOURITES -> {
                        val ids = Sync.favouriteIds()
                        val favs = remember(ids, songs) { songs.filter { it.id in ids } }
                        if (favs.isEmpty()) Note("Tap the heart on a playing song to keep it here.") else SongsPart(favs, Tab.FAVOURITES, go)
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
                            // Already downloaded and written into the installer out of sight, so it goes on at once.
                            // Through Android's own installer, so from now on updates go on by themselves.
                            updating = "Putting version " + offer.name + " on…"
                            scope.launch {
                                val word = Updates.updateByHand(context) { updating = it }
                                if (Updates.offer == null) Updates.offer = offer.takeIf { !word.startsWith("Putting") }
                                updating = if (word.startsWith("Putting")) "" else word
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
                Fixer.status.isNotEmpty() -> "app|Your music"
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
        // Once looking up is done the line moves on to fixing (Kevin, 25 November 2026): what is left to fix, and
        // songs stuck, opening Fix the files.
        val view = Scanner.view
        val data = Scanner.data
        val stuck = remember(data) { data.tracks.count { it.id in data.fixProblems && !(it.fixed && it.fixedFully) } }
        if (status.isEmpty() && Scanner.problem.isEmpty() && (view.leftToFix > 0 || stuck > 0)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open Fix the files") { settingsAt = "app|Your music"; go(Screen.Settings) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KueSettings.Icon(KueSettings.Icons.FIX, 16.dp, K.Accent, 2f)
                Spacer(Modifier.width(10.dp))
                Words(
                    listOfNotNull(
                        if (view.leftToFix > 0) count(view.leftToFix, "album") + " ready to fix" else null,
                        if (stuck > 0) count(stuck, "song") + " stuck" else null,
                    ).joinToString(" · ") + ". Tap to fix.",
                    14, K.Soft, modifier = Modifier.weight(1f),
                )
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
    private fun Shelf(things: List<Item>, layout: Views.Layout, chosen: Set<String>? = null, onChoose: (String) -> Unit = {}) {
        if (layout == Views.Layout.COVERS) {
            val across = PlayPrefs.changes.let { PlayPrefs.coverColumns }
            LazyVerticalGrid(
                columns = if (across > 0) GridCells.Fixed(across) else GridCells.Adaptive(150.dp),
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
                    val picking = chosen != null
                    val picked = chosen?.contains(x.key) == true
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (picked) K.Raised else if (x.highlight) K.Panel else Color.Transparent)
                            .combinedClickable(onClick = if (picking) ({ onChoose(x.key) }) else x.onClick, onLongClick = if (picking) null else x.onHold)
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(x.cover(), Modifier.size(56.dp), corner = 10.dp, glyphSize = 24.dp, glyph = x.glyph, round = x.round)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Words(x.title, 16, if (x.highlight) K.Accent else K.Ink, bold = x.highlight)
                            if (x.below.isNotEmpty()) Words(x.below, 13, K.Soft)
                        }
                        if (picking) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            KueSettings.Icon(if (picked) KueSettings.Icons.BOX_TICK else KueSettings.Icons.BOX, 22.dp, if (picked) K.Accent else K.Soft, 2f)
                        } else if (x.onHold != null) MoreButton("Everything you can do with it", x.onHold)
                    }
                }
            }
        }
    }

    /**
     * The row over a list of songs or albums (Kevin, 25 November 2026, CloudPlayer as the least): how many, Sort (by
     * what, and the other way round), Select to pick several, and Shuffle all.
     */
    @Composable
    private fun ListTools(tab: Tab, sorts: List<String>, howMany: String, selecting: Boolean?, onSelect: () -> Unit, onShuffle: () -> Unit) {
        var choosing by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Words(howMany, 13, K.Soft, modifier = Modifier.weight(1f))
            KueSettings.SubChip("Sort: " + sorts[Views.sortOf(tab).coerceIn(0, sorts.lastIndex)] + if (Views.reversedOf(tab)) ", reversed" else "", false, look) { choosing = true }
            if (selecting != null) KueSettings.SubChip(if (selecting) "Done" else "Select", selecting, look, onClick = onSelect)
            Box(Modifier.size(38.dp).clip(CircleShape).background(K.Accent).clickable(onClickLabel = "Shuffle all", onClick = onShuffle), contentAlignment = Alignment.Center) {
                Sign(Glyph.SHUFFLE, 20.dp, K.Page)
            }
        }
        if (choosing) HoldBox("Sort by", "", { choosing = false }) {
            sorts.forEachIndexed { i, name ->
                Button(name, main = Views.sortOf(tab) == i, icon = KueSettings.Icons.SORT, modifier = Modifier.fillMaxWidth()) { Views.setSort(tab, i); choosing = false }
            }
            KueSettings.Toggle("Reverse the order", "Z to A, oldest first, longest first.", Views.reversedOf(tab), look) { Views.setReversed(tab, it) }
        }
    }

    /** Songs with their row of tools: sorted, picked several at a time, shuffled. */
    @Composable
    private fun SongsPart(songs: List<Track>, tab: Tab, go: (Screen) -> Unit) {
        val sorted = remember(songs, Views.sortOf(tab), Views.reversedOf(tab)) { Views.sortSongs(songs, tab) }
        var chosen by remember { mutableStateOf<Set<String>?>(null) }
        Column(Modifier.fillMaxSize()) {
            ListTools(tab, Views.songSorts, count(songs.size, "song"), chosen != null, { chosen = if (chosen == null) emptySet() else null }) { Playing.play(sorted, 0, shuffled = true) }
            Box(Modifier.weight(1f)) { SongList(sorted, go, Views.layout(tab), chosen) { id -> chosen = chosen?.let { if (id in it) it - id else it + id } } }
            chosen?.takeIf { it.isNotEmpty() }?.let { ids -> Chosen(sorted.filter { it.id in ids }) { chosen = null } }
        }
    }

    /** What can be done with several songs picked at once (CloudPlayer's Select items). */
    @Composable
    private fun Chosen(songs: List<Track>, onDone: () -> Unit) {
        val context = LocalContext.current
        var adding by remember { mutableStateOf(false) }
        if (adding) ListUi.AddBox(count(songs.size, "song"), songs.map { it.id }, onClose = { adding = false; onDone() })
        Column(Modifier.fillMaxWidth().background(K.Panel).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Words(count(songs.size, "song") + " picked", 14, K.Accent, bold = true)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Button("Play", glyph = Glyph.PLAY, modifier = Modifier.height(44.dp)) { Playing.play(songs, 0); onDone() } }
                item { Button("Play next", main = false, icon = KueSettings.Icons.PLAY_NEXT, modifier = Modifier.height(44.dp)) { Playing.playNext(songs); onDone() } }
                item { Button("Queue", main = false, icon = KueSettings.Icons.QUEUE, modifier = Modifier.height(44.dp)) { Playing.addToQueue(songs); onDone() } }
                item { Button("Playlist", main = false, icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.height(44.dp)) { adding = true } }
                item { Button("Save to phone", main = false, icon = KueSettings.Icons.PHONE, modifier = Modifier.height(44.dp)) { Offline.save(context, songs); onDone() } }
            }
        }
    }

    /** Albums with their row of tools: sorted and shuffled. */
    @Composable
    private fun AlbumsPart(albums: List<Album>, tab: Tab, go: (Screen) -> Unit) {
        val sorted = remember(albums, Views.sortOf(tab), Views.reversedOf(tab)) { Views.sortAlbums(albums, tab) }
        Column(Modifier.fillMaxSize()) {
            ListTools(tab, Views.albumSorts, count(albums.size, "album"), null, {}) { Playing.play(albums.flatMap { it.tracks }, 0, shuffled = true) }
            Box(Modifier.weight(1f)) { AlbumShelf(sorted, Views.layout(tab), go) }
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
                // An artist's own photo when one is found (Kevin, 25 November 2026), else their first album's cover.
                Item("a:" + a.name, a.name, count(a.albums.size, "album") + " · " + count(a.songCount, "song"), Glyph.PERSON,
                    { Scanner.artistPicture(a.name) ?: a.albums.firstNotNullOfOrNull { Scanner.artFor(it) } }, round = true, onHold = { held = a }) { go(Screen.ArtistPage(a.name)) }
            }
        }
        Shelf(things, layout)
    }

    /** Kinds of music, each with how many albums and songs (Kevin, 25 November 2026). */
    @Composable
    private fun GenreList(albums: List<Album>, layout: Views.Layout, go: (Screen) -> Unit) {
        val data = Scanner.data
        val kinds = remember(albums, data.facts) { Library.genres(albums, data) }
        if (kinds.isEmpty()) {
            Note(if (Scanner.running) "Kinds of music show here once step 4 has looked them up." else "No kinds of music known yet. They are looked up at step 4 in Settings › KueSounds › Your music.")
            return
        }
        val things = remember(kinds) {
            kinds.map { (name, list) ->
                val songs = list.sumOf { al -> al.tracks.count { Library.genreOf(it, data).equals(name, ignoreCase = true) } }
                Item("g:" + name, name, count(list.size, "album") + " · " + count(songs, "song"), Glyph.DISC,
                    { list.firstNotNullOfOrNull { Scanner.artFor(it) } }) { go(Screen.GenrePage(name)) }
            }
        }
        Shelf(things, layout)
    }

    /** One kind of music: its albums, and its songs to play or mix. */
    @Composable
    private fun GenrePage(name: String, albums: List<Album>, back: () -> Unit, go: (Screen) -> Unit) {
        val data = Scanner.data
        val list = remember(albums, data.facts, name) { Library.genres(albums, data).firstOrNull { it.first.equals(name, ignoreCase = true) }?.second.orEmpty() }
        val songs = remember(list) { list.flatMap { al -> al.tracks.filter { Library.genreOf(it, data).equals(name, ignoreCase = true) } } }
        AlbumGrid(list, go) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Glyph.BACK, "Back", onClick = back)
                Words(name, 24, bold = true, lines = 2)
                Words(count(list.size, "album") + " · " + count(songs.size, "song"), 15, K.Soft)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Play all", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) { Playing.play(songs, 0) }
                    Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.weight(1f)) { Playing.play(songs, 0, shuffled = true) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    /** Who wrote the songs, from the files (Kevin, 25 November 2026, as CloudPlayer has). */
    @Composable
    private fun ComposerList(layout: Views.Layout, go: (Screen) -> Unit) {
        val data = Scanner.data
        val writers = remember(data.tracks) { Library.composers(data.tracks) }
        if (writers.isEmpty()) {
            val left = data.tracks.count { !it.composerRead }
            Note(if (left > 0) "Who wrote each song is read from the files at step 4 in Settings: " + count(left, "song") + " still to read." else "None of your songs' files name who wrote them.")
            return
        }
        val things = remember(writers) {
            writers.map { (name, songs) ->
                Item("c:" + name, name, count(songs.map { it.album }.distinct().size, "album") + " · " + count(songs.size, "song"), Glyph.PERSON,
                    { songs.firstNotNullOfOrNull { Scanner.artForTrack(it) } }) { go(Screen.ComposerPage(name)) }
            }
        }
        Shelf(things, layout)
    }

    @Composable
    private fun ComposerPage(name: String, back: () -> Unit, go: (Screen) -> Unit) {
        val songs = remember(Scanner.data.tracks, name) { Library.composers(Scanner.data.tracks).firstOrNull { it.first == name }?.second.orEmpty() }
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Glyph.BACK, "Back", onClick = back)
                    Spacer(Modifier.width(14.dp))
                    Words(name, 20, bold = true, lines = 2, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Play all", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) { Playing.play(songs, 0) }
                    Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.weight(1f)) { Playing.play(songs, 0, shuffled = true) }
                }
            }
            Box(Modifier.weight(1f)) { SongList(songs, go) }
        }
    }

    private fun count(n: Int, word: String): String = String.format("%,d", n) + " " + word + if (n == 1) "" else "s"

    @Composable
    private fun SongList(songs: List<Track>, go: (Screen) -> Unit, layout: Views.Layout = Views.Layout.LIST, chosen: Set<String>? = null, onChoose: (String) -> Unit = {}) {
        // A tap plays; a hold or the ⋮ gives the song's every action (see SongBox).
        var held by remember { mutableStateOf<Track?>(null) }
        val playing = Playing.currentId
        val things = remember(songs, playing) {
            songs.mapIndexed { i, t ->
                // A song that came out as a single shows its single's cover and says so; the rest show their album's
                // (Kevin, 25 November 2026: Singles is part of Songs now).
                val single = t.id in Scanner.data.singleTitles
                Item(t.id, t.title, listOf(t.artist, if (single) "single" else t.album).filter { it.isNotBlank() }.joinToString(" · "), Glyph.NOTE,
                    { (if (single) Scanner.singleArtFor(t) else null) ?: Scanner.artForTrack(t) ?: Scanner.singleArtFor(t) }, highlight = t.id == playing, onHold = { held = t }) { Playing.play(songs, i) }
            }
        }
        Shelf(things, layout, chosen, onChoose)
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
            "add" -> ListUi.AddBox("\"" + t.title + "\"", listOf(t.id), onClose = onClose)
            "edit" -> EditSong(t, onClose)
            "info" -> SongInfo(t, onClose)
            "match" -> MatchBox(listOf(t), onClose)
            "delete" -> DeleteSong(t, onClose)
            "ring" -> RingtoneBox(t, onClose)
            "cover" -> Scanner.albumOf(t)?.let { CoverPicker(it, onClose) } ?: onClose()
            else -> HoldBox(t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), onClose) {
                Button("Add to a playlist", icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.fillMaxWidth()) { doing = "add" }
                Button("Play it now", main = false, glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(listOf(t), 0); onClose() }
                // A mix started from this song: it, then songs from your music that go with it (Kevin, 26 November 2026).
                Button("Play songs like this", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.fillMaxWidth()) {
                    Playing.play(listOf(t) + Smart.suggest(listOf(t), Scanner.data.tracks, Scanner.data, 40).shuffled(), 0)
                    onClose()
                }
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
                Button("Set as ringtone", main = false, icon = KueSettings.Icons.RING, modifier = Modifier.fillMaxWidth()) { doing = "ring" }
                Button("Choose the cover", main = false, icon = KueSettings.Icons.DISC, modifier = Modifier.fillMaxWidth()) { doing = "cover" }
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
        // What the catalogue says about its album, the label and the rest asked for now if not yet (Kevin, 25 Nov 2026).
        val album0 = remember(t.id) { Scanner.albumOf(t) }
        val facts by androidx.compose.runtime.produceState(album0?.let { Scanner.data.facts[it.key] }, t.id) {
            if (album0 != null && value?.detailed != true) value = withContext(Dispatchers.IO) { runCatching { Scanner.detailsFor(album0) }.getOrNull() } ?: value
        }
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
                    val kind = Library.genreOf(t, Scanner.data)
                    if (kind.isNotBlank()) add("Kind of music" to kind)
                    val more = facts?.genres.orEmpty().map { Library.tidyGenre(it) }.filter { !it.equals(kind, ignoreCase = true) }
                    if (more.isNotEmpty()) add("Also" to more.joinToString(", "))
                    if (!t.composer.isNullOrBlank()) add("Written by" to t.composer!!)
                    if (t.durationMs > 0) add("Length" to Library.clock(t.durationMs))
                    facts?.firstOut?.takeIf { it.isNotBlank() }?.let { add("First out" to Library.date(it)) }
                    facts?.released?.takeIf { it.isNotBlank() && it != facts?.firstOut }?.let { add("This release" to Library.date(it)) }
                    facts?.type?.takeIf { it.isNotBlank() }?.let { add("Album type" to it) }
                    facts?.label?.takeIf { it.isNotBlank() }?.let { add("Record label" to it + (facts?.catalogueNo?.takeIf { c -> c.isNotBlank() }?.let { c -> " · $c" } ?: "")) }
                    facts?.country?.takeIf { it.isNotBlank() }?.let { add("Released in" to (java.util.Locale("", it).displayCountry.ifBlank { it })) }
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
                // The artist's own video, found and played here, with KueSounds' sound stopped (Kevin, 25 November 2026).
                val scope = rememberCoroutineScope()
                var finding by remember { mutableStateOf(false) }
                Button(if (finding) "Finding the video…" else "Watch it on YouTube", main = false, icon = KueSettings.Icons.VIDEO, modifier = Modifier.fillMaxWidth()) {
                    if (finding) return@Button
                    finding = true
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { runCatching { YouTube.find(t.artist, t.title) }.getOrDefault(emptyList()) }
                        finding = false
                        if (found.isEmpty()) {
                            Scanner.note("YouTube: no video read for " + t.artist + " · " + t.title + "; opened YouTube's search instead")
                            open("https://www.youtube.com/results?search_query=$words")
                        } else {
                            Scanner.note("YouTube: " + t.title + " → " + found.first().title + " (" + found.first().channel + ")")
                            watch = Screen.YouTubePlayer("https://www.youtube.com/watch?v=" + found.first().id, found.take(5).map { it.id }, t.title + " · " + t.artist)
                            onClose()
                        }
                    }
                }
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
                                    Words(if (best.from == "Shazam") "Named by Shazam" else "Sure: " + (best.score * 100).toInt() + "%", 13, K.Soft)
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
                                Scanner.markSoundMatched(usable.map { it.id }.toSet())
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

    /** Set as ringtone: asked first, then done, saying how it went. */
    @Composable
    private fun RingtoneBox(t: Track, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf("") }
        HoldBox("Make \"" + t.title + "\" your ringtone?", t.artist, { if (!busy) onClose() }) {
            Words("It's copied into the phone's Ringtones folder and set as the ringtone for calls.", 14, K.Soft, lines = 3)
            if (busy) Working("Setting it…")
            if (said.isNotEmpty()) Words(said, 14, K.Accent, lines = 5)
            if (!busy && said.isEmpty()) Button("Set it", icon = KueSettings.Icons.RING, modifier = Modifier.fillMaxWidth()) {
                busy = true
                scope.launch {
                    said = withContext(Dispatchers.IO) { runCatching { Ringtone.set(context.applicationContext, t) }.getOrElse { "It didn't work: " + (it.message ?: "the song couldn't be fetched") } }
                    busy = false
                }
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
        if (adding) { ListUi.AddBox("the album", al.tracks.map { it.id }, onClose = onClose); return }
        var picking by remember { mutableStateOf(false) }
        if (picking) { CoverPicker(al, onClose); return }
        HoldBox(al.title, listOf(al.artist, count(al.tracks.size, "song")).joinToString(" · "), onClose) {
            Button("Play", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(al.tracks, 0); onClose() }
            Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.fillMaxWidth()) { Playing.play(al.tracks, 0, shuffled = true); onClose() }
            CommonChoices(al.tracks, { adding = true }, onClose)
            if (Fixer.hasWork(al)) Button("Fix the files", main = false, icon = KueSettings.Icons.FIX, modifier = Modifier.fillMaxWidth()) { Fixer.fix(context, listOf(al)); onClose() }
            Button("Edit album name and artist", main = false, icon = KueSettings.Icons.EDIT, modifier = Modifier.fillMaxWidth()) { editing = true }
            Button("Choose the cover", main = false, icon = KueSettings.Icons.DISC, modifier = Modifier.fillMaxWidth()) { picking = true }
            Button("Match every song by sound", main = false, icon = KueSettings.Icons.EQUALISER, modifier = Modifier.fillMaxWidth()) { matching = true }
            if (Scanner.view.artists.any { it.name == al.artist }) Button("Go to the artist", main = false, glyph = Glyph.PERSON, modifier = Modifier.fillMaxWidth()) { onClose(); go(Screen.ArtistPage(al.artist)) }
        }
    }

    /** What can be done with a held artist: everything of theirs (Kevin, 24 November 2026). */
    @Composable
    private fun ArtistBox(a: Artist, onClose: () -> Unit) {
        val all = remember(a) { a.albums.flatMap { it.tracks } }
        var adding by remember { mutableStateOf(false) }
        if (adding) { ListUi.AddBox(a.name, all.map { it.id }, onClose = onClose); return }
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
                    var info by remember { mutableStateOf(false) }
                    Button("Album info", main = false, icon = KueSettings.Icons.INFO, modifier = Modifier.fillMaxWidth()) { info = true }
                    if (info) AlbumInfo(al) { info = false }
                    Spacer(Modifier.height(12.dp))
                    var picking by remember { mutableStateOf(false) }
                    Button("Choose the cover", main = false, icon = KueSettings.Icons.DISC, modifier = Modifier.fillMaxWidth()) { picking = true }
                    if (picking) CoverPicker(al) { picking = false }
                    Spacer(Modifier.height(12.dp))
                    var adding by remember { mutableStateOf(false) }
                    Button("Add to a playlist", main = false, icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.fillMaxWidth()) { adding = true }
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

    /**
     * Pick a cover by hand (Kevin, 26 November 2026: for any album not recognised): covers found online by the album's
     * name and artist (changeable), or a picture from the phone. The one tapped is kept and shown at once.
     */
    @Composable
    private fun CoverPicker(al: Album, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var words by remember { mutableStateOf(listOf(al.artist.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), al.title).filter { it.isNotBlank() }.joinToString(" ")) }
        var found by remember { mutableStateOf<List<Smart.CoverChoice>?>(null) }
        var saying by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val search = {
            found = null
            scope.launch { found = withContext(Dispatchers.IO) { Smart.findCovers(words) } }
            Unit
        }
        LaunchedEffect(Unit) { search() }
        val keep = { bytes: ByteArray? ->
            if (bytes != null && Scanner.setCover(al, bytes)) { android.widget.Toast.makeText(context, "Cover changed.", android.widget.Toast.LENGTH_SHORT).show(); onClose() }
            else saying = "That picture couldn't be used. Try another."
        }
        val fromPhone = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch { keep(withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }) }
        }
        Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Glyph.BACK, "Back", onClick = onClose)
                    Spacer(Modifier.width(12.dp))
                    Words("Choose the cover", 19, bold = true, modifier = Modifier.weight(1f))
                }
                Words(al.title + " · " + al.artist, 14, K.Soft, lines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { EditField(words) { words = it } }
                    Button("Search", modifier = Modifier.height(52.dp), onClick = search)
                }
                Button("A picture from your phone", main = false, icon = KueSettings.Icons.PHONE, modifier = Modifier.fillMaxWidth()) {
                    fromPhone.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (saying.isNotEmpty()) Words(saying, 14, K.Accent, lines = 2)
                if (busy) Working("Keeping it…")
                val list = found
                when {
                    list == null -> Working("Looking for covers…")
                    list.isEmpty() -> Words("No covers found for that. Change the words and search again.", 15, K.Soft, lines = 2)
                    else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.big }) { c ->
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                    if (busy) return@clickable
                                    busy = true
                                    scope.launch { val b = withContext(Dispatchers.IO) { Smart.download(c.big) }; busy = false; keep(b) }
                                },
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                AsyncImage(model = c.small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(K.Panel))
                                Words(c.title, 12, bold = true, lines = 2)
                                Words(c.artist, 11, K.Soft)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The same song held more than once (Kevin, 26 November 2026: a duplicate finder for the whole library): each set of
     * copies with where each is and its quality; Keep this one sends the others to the Drive's bin (kept 30 days).
     */
    @Composable
    private fun DuplicatesBox(go: (Screen) -> Unit, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val groups = remember(Scanner.data.tracks) { Smart.duplicates(Scanner.data.tracks) }
        var held by remember { mutableStateOf<Track?>(null) }
        held?.let { t -> SongBox(t, go) { held = null } }
        var sure by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { if (busy.isEmpty()) onClose() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Glyph.BACK, "Back") { if (busy.isEmpty()) onClose() }
                    Spacer(Modifier.width(12.dp))
                    Words(if (groups.isEmpty()) "No duplicates" else count(groups.size, "song") + " held more than once", 19, bold = true, lines = 2, modifier = Modifier.weight(1f))
                }
                if (busy.isNotEmpty()) Working(busy)
                if (groups.isEmpty()) Words("Every song is in your music once.", 15, K.Soft)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(groups, key = { g -> g.joinToString { it.id } }) { g ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Words(g.first().title + " · " + g.first().artist, 16, bold = true, lines = 2)
                            for (t in g) {
                                val folder = Scanner.data.folders.firstOrNull { it.id == t.folderId }?.name.orEmpty()
                                val kbps = if (t.size > 0 && t.durationMs > 0) ((t.size * 8 / (t.durationMs / 1000.0)) / 1000).toInt().toString() + " kbps" else ""
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(K.Raised).clickable { held = t }.padding(10.dp)) {
                                    Words(t.album.ifBlank { "No album" } + (if (folder.isNotEmpty()) " · folder " + folder else ""), 14, lines = 2)
                                    Words(listOf(Library.ending(t.fileName).uppercase(), kbps, if (t.durationMs > 0) Library.clock(t.durationMs) else "", if (t.id in Offline.saved) "on phone" else "").filter { it.isNotEmpty() }.joinToString(" · "), 12, K.Soft)
                                    val key = g.joinToString { it.id } + "|" + t.id
                                    if (sure == key) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                        Button("Yes, delete " + (g.size - 1) + " other" + if (g.size > 2) "s" else "", modifier = Modifier.weight(1f)) {
                                            sure = null
                                            val others = g.filter { it.id != t.id }
                                            val go2 = {
                                                busy = "Sending " + others.size + " to the Drive's bin…"
                                                scope.launch {
                                                    val failed = withContext(Dispatchers.IO) {
                                                        others.count { o ->
                                                            runCatching {
                                                                Drive.trash(o.id, Google.editToken() ?: throw Drive.SignedOut())
                                                                Offline.remove(listOf(o))
                                                                Scanner.removeTracks(setOf(o.id))
                                                                Scanner.note("duplicate deleted (to the Drive's bin): " + o.fileName)
                                                            }.isFailure
                                                        }
                                                    }
                                                    busy = ""
                                                    android.widget.Toast.makeText(context, if (failed == 0) "Done. The others are in your Drive's bin for 30 days." else "$failed couldn't be deleted.", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                Unit
                                            }
                                            if (Google.canEdit) go2() else Fixer.askPermission { go2() }
                                        }
                                        Button("Cancel", main = false, modifier = Modifier.weight(1f)) { sure = null }
                                    } else Words("Keep this one", 13, K.Accent, bold = true, modifier = Modifier.padding(top = 4.dp).clickable { sure = key })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Everything about an album (Kevin, 25 November 2026): its cover, name and artist, when it first came out and when
     * this release did, its label and catalogue number, country, barcode, kinds of music, songs and length.
     */
    @Composable
    private fun AlbumInfo(al: Album, onClose: () -> Unit) {
        val context = LocalContext.current
        val facts by androidx.compose.runtime.produceState(Scanner.data.facts[al.key], al.key) {
            if (value?.detailed != true) value = withContext(Dispatchers.IO) { runCatching { Scanner.detailsFor(al) }.getOrNull() } ?: value
        }
        val release = Scanner.data.releases[al.key]?.split('|')?.firstOrNull()?.takeIf { it.isNotEmpty() }
        Dialog(onDismissRequest = onClose) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Cover(Scanner.artFor(al), Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp, glyphSize = 72.dp)
                Words(al.title, 22, bold = true, lines = 3)
                Words(al.artist, 17, K.Accent, lines = 2)
                val f = facts
                val kinds = al.tracks.map { Library.genreOf(it, Scanner.data) }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                    .ifEmpty { f?.genres.orEmpty().map { Library.tidyGenre(it) } }
                val rows = buildList {
                    if (kinds.isNotEmpty()) add("Kind of music" to kinds.joinToString(", "))
                    f?.firstOut?.takeIf { it.isNotBlank() }?.let { add("First out" to Library.date(it)) }
                    if (f?.firstOut.isNullOrBlank() && al.year > 0) add("Year" to al.year.toString())
                    f?.released?.takeIf { it.isNotBlank() && it != f.firstOut }?.let { add("This release" to Library.date(it)) }
                    f?.type?.takeIf { it.isNotBlank() }?.let { add("Type" to it) }
                    f?.label?.takeIf { it.isNotBlank() }?.let { add("Record label" to it) }
                    f?.catalogueNo?.takeIf { it.isNotBlank() }?.let { add("Catalogue number" to it) }
                    f?.country?.takeIf { it.isNotBlank() }?.let { add("Released in" to (java.util.Locale("", it).displayCountry.ifBlank { it })) }
                    f?.barcode?.takeIf { it.isNotBlank() }?.let { add("Barcode" to it) }
                    add("Songs" to al.tracks.size.toString())
                    val ms = al.tracks.sumOf { it.durationMs }
                    if (ms > 0) add("Length" to Library.clock(ms))
                    add("On this phone" to if (al.tracks.all { it.id in Offline.saved }) "Yes" else if (al.tracks.any { it.id in Offline.saved }) "Some songs" else "No")
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((label, value) in rows) Row {
                        Words(label, 14, K.Soft, modifier = Modifier.width(130.dp))
                        Words(value, 14, lines = 3, modifier = Modifier.weight(1f))
                    }
                }
                if (f == null && release != null) Working("Asking the catalogue…")
                Words(if (release != null) "Matched in the MusicBrainz music catalogue." else "Not matched in the music catalogue.", 14, K.Soft, lines = 2)
                if (release != null) Button("Its record in the catalogue", main = false, icon = KueSettings.Icons.OPEN, modifier = Modifier.fillMaxWidth()) {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://musicbrainz.org/release/$release")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Button("Close", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth(), onClick = onClose)
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
                // Their photo when one was found (Kevin, 25 November 2026), round, beside their name.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(Scanner.artistPicture(a.name) ?: a.albums.firstNotNullOfOrNull { Scanner.artFor(it) }, Modifier.size(96.dp), glyphSize = 40.dp, glyph = Glyph.PERSON, round = true)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Words(a.name, 24, bold = true, lines = 2)
                        Words(count(a.albums.size, "album") + " · " + count(a.songCount, "song"), 15, K.Soft)
                    }
                }
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
    private fun NowPlaying(close: () -> Unit, go: (Screen) -> Unit) {
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
        // A short line saying what a button just did, for 2 seconds (Kevin, 25 November 2026: "pop up info").
        var said by remember { mutableStateOf("") }
        var saidCount by remember { mutableStateOf(0) }
        val say = { text: String -> said = text; saidCount++ }
        LaunchedEffect(saidCount) { if (said.isNotEmpty()) { delay(2_000); said = "" } }
        val scope = rememberCoroutineScope()
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Glyph.DOWN, "Close", onClick = close)
                Words(if (Playing.album.isNotEmpty()) "Playing from " + Playing.album else "", 14, K.Soft, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), centre = true)
                // Everything for the song playing (CloudPlayer's menu as the least): its album, artist, playlists,
                // ringtone, info and the rest.
                val song = Scanner.data.tracks.firstOrNull { it.id == Playing.currentId }
                var menu by remember { mutableStateOf(false) }
                if (song != null) MoreButton("Everything for this song") { menu = true } else Spacer(Modifier.size(44.dp))
                if (menu && song != null) SongBox(song, go) { menu = false }
            }
            // The cover as big as the space allows and never bigger, so it can't cover "Playing from" above it or the
            // song's name below (Kevin, 25 November 2026: the square ran over both on a shorter screen). A swipe left
            // or right on it changes song.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                val slide = remember { androidx.compose.animation.core.Animatable(0f) }
                val swipes = !Playing.isRadio && !Playing.isPodcast
                Box(
                    Modifier.size(side)
                        .graphicsLayer { translationX = slide.value; alpha = 1f - (kotlin.math.abs(slide.value) / (size.width * 1.4f)).coerceIn(0f, 0.6f) }
                        .clip(RoundedCornerShape(20.dp)).background(K.Panel)
                        .then(
                            if (!swipes) Modifier else Modifier.pointerInput(Unit) {
                                val far = size.width * 0.22f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val x = slide.value
                                        if (x < -far) Playing.next() else if (x > far) Playing.previous()
                                        scope.launch { slide.animateTo(0f) }
                                    },
                                    onDragCancel = { scope.launch { slide.animateTo(0f) } },
                                ) { change, dx ->
                                    change.consume()
                                    scope.launch { slide.snapTo(slide.value + dx) }
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
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
                if (adding) ListUi.AddBox("this song", listOf(Playing.currentId), onAdded = { say("Added to " + it) }) { adding = false }
                if (songNow) Spacer(Modifier.width(10.dp))
                if (songNow) Box(
                    Modifier.size(52.dp).clip(CircleShape).background(K.Panel)
                        .clickable(onClickLabel = if (fav) "Take out of favourites" else "Add to favourites") {
                            Sync.flipFavourite(Playing.currentId)
                            say(if (fav) "Taken out of favourites" else "Added to favourites")
                        },
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
                    if (track != null) ToolChip(KueSettings.Icons.LYRICS, "Lyrics", SingAlong.on, Modifier.weight(1f)) { tool = "lyrics" }
                    if (track != null) ToolChip(KueSettings.Icons.INFO, "Info", false, Modifier.weight(1f)) { tool = "info" }
                }
            }
            when (tool) {
                "sound" -> SoundBox(Sound.kindOf(Playing.currentId)) { tool = "" }
                "queue" -> UpNext { tool = "" }
                "info" -> Scanner.data.tracks.firstOrNull { it.id == Playing.currentId }?.let { SongInfo(it) { tool = "" } } ?: run { tool = "" }
                "lyrics" -> Scanner.data.tracks.firstOrNull { it.id == Playing.currentId }?.let { LyricsBox(it) { tool = "" } } ?: run { tool = "" }
            }
            if (Playing.isRadio) OnlineUi.RadioControls() else
            if (Playing.isPodcast) PodUi.Controls() else
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Control(Glyph.SHUFFLE, "Shuffle", 48.dp, if (Playing.shuffle) K.Accent else K.Soft) {
                    say(if (Playing.shuffle) "Shuffle off: in order" else "Shuffle on: mixed up")
                    Playing.flipShuffle()
                }
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
                ) {
                    say(
                        when (Playing.repeat) {
                            androidx.media3.common.Player.REPEAT_MODE_OFF -> "Repeat on: the whole list again"
                            androidx.media3.common.Player.REPEAT_MODE_ALL -> "Repeat this song"
                            else -> "Repeat off"
                        },
                    )
                    Playing.nextRepeat()
                }
            }
        }
            if (said.isNotEmpty()) Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp).clip(RoundedCornerShape(20.dp)).background(K.Raised)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Words(said, 15, K.Ink, bold = true) }
        }
    }

    /**
     * The song's words, line by line as it is sung, for singing along (Kevin, 25 November 2026): the line being sung big
     * and yellow in the middle, the list moving with the song, a tap on a line going to it, and Sing along to turn the
     * singer down.
     */
    @Composable
    private fun LyricsBox(t: Track, onClose: () -> Unit) {
        var loading by remember(t.id) { mutableStateOf(true) }
        var problem by remember(t.id) { mutableStateOf("") }
        val words by androidx.compose.runtime.produceState<Lyrics.Words?>(null, t.id) {
            value = withContext(Dispatchers.IO) {
                runCatching { Lyrics.get(t.id, t.artist, t.title, t.album, t.durationMs / 1000) }
                    .onFailure { problem = "The lyrics library couldn't be reached. Check the internet and try again." }
                    .getOrNull()
            }
            loading = false
        }
        var position by remember { mutableLongStateOf(Playing.positionMs()) }
        LaunchedEffect(t.id) {
            while (true) {
                position = Playing.positionMs()
                delay(200)
            }
        }
        var singAlong by remember { mutableStateOf(SingAlong.on) }
        Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Glyph.DOWN, "Close", onClick = onClose)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Words(t.title, 18, bold = true)
                        Words(t.artist, 14, K.Soft)
                    }
                }
                KueSettings.Toggle("Sing along", "Turns the singer down so you can sing. Some bass goes with it.", singAlong, look) { on ->
                    singAlong = on
                    SingAlong.on = on
                }
                val w = words
                val timed = w?.timed.orEmpty()
                when {
                    loading -> Working("Finding the words…")
                    problem.isNotEmpty() -> Words(problem, 15, K.Accent, lines = 3)
                    w == null || w.none -> Words("No words found for this song in the lyrics library.", 15, K.Soft, lines = 3)
                    w.instrumental && timed.isEmpty() && w.plain.isBlank() -> Words("This one has no words: it's an instrumental.", 15, K.Soft, lines = 2)
                    timed.isNotEmpty() -> {
                        val now = Lyrics.lineAt(timed, position + 300)
                        val list = androidx.compose.foundation.lazy.rememberLazyListState()
                        LaunchedEffect(now) { if (now >= 0) runCatching { list.animateScrollToItem((now - 3).coerceAtLeast(0)) } }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 40.dp)) {
                            itemsIndexed(timed) { i, line ->
                                val on = i == now
                                Text(
                                    noEmoji(line.words).ifBlank { "· · ·" },
                                    color = if (on) K.Accent else if (i < now) K.Soft.copy(alpha = 0.6f) else K.Ink,
                                    fontSize = if (on) 26.sp else 20.sp, fontFamily = K.Poppins,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, lineHeight = if (on) 32.sp else 26.sp,
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Playing.seekTo(line.at) }.padding(vertical = 4.dp, horizontal = 6.dp),
                                )
                            }
                        }
                    }
                    else -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Words("These words don't have their timings, so they don't move with the song.", 13, K.Soft, lines = 2)
                            Words(w.plain, 19, lines = 400)
                        }
                    }
                }
                Words("Words from LRCLIB, the free lyrics library.", 12, K.Soft)
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

    /**
     * What is lined up to play (Kevin, 25 November 2026, CloudPlayer as the least): tap one to play it, drag its handle
     * to move it, the cross to take it out; Save as a playlist and Clear at the bottom.
     */
    @Composable
    private fun UpNext(onClose: () -> Unit) {
        var lined by remember { mutableStateOf(Playing.queue()) }
        var saving by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        var sureClear by remember { mutableStateOf(false) }
        val rowHeight = 60.dp
        val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowHeight.toPx() }
        val list = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(Unit) { lined.indexOfFirst { it.now }.takeIf { it > 1 }?.let { list.scrollToItem(it - 1) } }
        Dialog(onDismissRequest = onClose) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("Up next · " + lined.size, 19, bold = true)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), state = list) {
                    items(lined, key = { it.index.toString() + it.title }) { q ->
                        var dragged by remember(q.index) { mutableFloatStateOf(0f) }
                        Row(
                            Modifier.fillMaxWidth().height(rowHeight).graphicsLayer { translationY = dragged }
                                .clip(RoundedCornerShape(10.dp)).background(if (q.now) K.Panel else if (dragged != 0f) K.Raised else Color.Transparent)
                                .clickable { Playing.jumpTo(q.index); lined = Playing.queue() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The handle: hold and drag up or down to move the song.
                            Box(
                                Modifier.size(44.dp).pointerInput(q.index) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            val steps = Math.round(dragged / rowPx)
                                            val to = (q.index + steps).coerceIn(0, lined.size - 1)
                                            dragged = 0f
                                            if (to != q.index) { Playing.move(q.index, to); lined = Playing.queue() }
                                        },
                                        onDragCancel = { dragged = 0f },
                                    ) { change, dy -> change.consume(); dragged += dy }
                                },
                                contentAlignment = Alignment.Center,
                            ) { KueSettings.Icon(KueSettings.Icons.DRAG, 20.dp, K.Soft, 2.2f) }
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
                if (saving) {
                    Words("The playlist's name", 13, K.Soft)
                    EditField(name) { name = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button("Save it", modifier = Modifier.weight(1f)) {
                            val ids = Playing.queueIds()
                            if (name.isNotBlank() && ids.isNotEmpty()) { Playlists.create(name.trim(), ids); saving = false; name = "" }
                        }
                        Button("Cancel", main = false, modifier = Modifier.weight(1f)) { saving = false }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button("Save as playlist", main = false, icon = KueSettings.Icons.PLAYLIST, modifier = Modifier.weight(1f)) { saving = true }
                    Button(if (sureClear) "Sure? Clear" else "Clear", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.weight(1f)) {
                        if (!sureClear) sureClear = true else { Playing.clearQueue(); lined = Playing.queue(); sureClear = false }
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
        // Ten bands as a graphic equaliser: drag each bar up or down (Kevin, 25 November 2026).
        GraphicEq(p.levels) { band, v ->
            Sound.change(kind) { it.copy(eqOn = true, preset = Sound.OWN, own = (if (it.preset == Sound.OWN) it.own else it.levels).toMutableList().also { l -> l[band] = v }) }
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
        if (kind == Sound.Kind.MUSIC) {
            @Suppress("UNUSED_VARIABLE") val seen = PlayPrefs.changes
            KueSettings.Toggle("Even loudness", "Quiet songs brought up and loud ones down to the same level, so the volume needn't be touched between them.", PlayPrefs.evenLoudness, look) { PlayPrefs.evenLoudness = it }
        }
        if (kind == Sound.Kind.PODCASTS) {
            KueSettings.Toggle("Skip silences", "Gaps in an episode are skipped over, so it takes less time.", p.skipSilence, look) { on -> Sound.change(kind) { it.copy(skipSilence = on) } }
        }
        Button("Back to how it was made", main = false, modifier = Modifier.fillMaxWidth()) { Sound.save(kind, Sound.Profile()) }
        if (Sound.missing.isNotEmpty()) KueSettings.Note("This phone doesn't have " + Sound.missing.joinToString(", ") + ", so those don't change anything.", look)
    }

    /** The ten bars of the graphic equaliser: each shows its level and is set by dragging it up or down. */
    @Composable
    private fun GraphicEq(levels: List<Float>, onSet: (Int, Float) -> Unit) {
        Row(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(14.dp)).background(K.Page).padding(vertical = 10.dp, horizontal = 4.dp)) {
            levels.forEachIndexed { b, v ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Words((if (v > 0) "+" else "") + String.format("%.0f", v), 11, if (v != 0f) K.Accent else K.Soft)
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).pointerInput(b) {
                            val set = { y: Float ->
                                val t = (y / size.height).coerceIn(0f, 1f)
                                onSet(b, Math.round(Sound.RANGE - t * 2 * Sound.RANGE).toFloat())
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                set(down.position.y)
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull() ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    set(c.position.y)
                                }
                            }
                        },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val x = size.width / 2
                            val mid = size.height / 2
                            val y = mid - (v / Sound.RANGE) * mid
                            drawLine(K.Raised, Offset(x, 0f), Offset(x, size.height), strokeWidth = 6f, cap = StrokeCap.Round)
                            drawLine(K.Soft.copy(alpha = 0.4f), Offset(x - 10f, mid), Offset(x + 10f, mid), strokeWidth = 2f)
                            drawLine(K.Accent, Offset(x, mid), Offset(x, y), strokeWidth = 6f, cap = StrokeCap.Round)
                            drawCircle(K.Ink, 11f, Offset(x, y))
                        }
                    }
                    Words(Sound.bandNames[b], 11, K.Soft)
                }
            }
        }
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
                    (if (!Scanner.running && Scanner.problem.isNotEmpty()) "\n" + Scanner.problem else "") +
                    // Why it isn't done, when it has stopped short (Kevin, 26 November 2026: "needs error message").
                    (if (!Scanner.running && albums > 0 && view.lookedUp < albums) "\n" + n(albums - view.lookedUp) +
                        (if (albums - view.lookedUp == 1) " album isn't" else " albums aren't") + " looked up yet: the music catalogue didn't answer for " +
                        (if (albums - view.lookedUp == 1) "it" else "them") + ". Asked again at the next look (Look for new music, below)." else ""),
            )
            // Step 4 (Kevin, 25 November 2026): kinds of music, dates, artist pictures and who wrote each song.
            val withFacts = view.albums.count { it.key in data.facts }
            val matchedAlbums = view.albums.count { it.key in data.releases }
            val pictures = data.artistPictures.values.count { it.isNotEmpty() }
            val composersLeft = data.tracks.count { !it.composerRead }
            Step(
                4, "Kinds of music, dates, artist pictures", "",
                state = if (stage == 4) NOW else if (matchedAlbums > 0 && withFacts >= matchedAlbums && composersLeft == 0) DONE else WAITING,
                progress = n(withFacts) + " of " + n(matchedAlbums) + " matched albums · " + n(pictures) + " artist pictures" +
                    (if (composersLeft > 0) "\nWho wrote each song: " + n(songs - composersLeft) + " of " + n(songs) + " read" else ""),
            )
            // Step 5, fixing the files, is part of the same flow (Kevin, 26 November 2026: "integrate").
            val stuck = remember(data) { data.tracks.count { it.id in data.fixProblems && !(it.fixed && it.fixedFully) } }
            Step(
                5, "Fix the files", "",
                state = if (Fixer.running) NOW else if (view.matched.isNotEmpty() && view.leftToFix == 0 && stuck == 0) DONE else WAITING,
                progress = "",
            )
            Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { FixPanel(view, go) }
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
                "Matched by their sound on Wi-Fi by themselves; once matched they leave this list. Tap one to open it and put its name right.",
                missed, { showMissed = false },
                action = if (!Scanner.running) ("Match these by sound now" to { showMissed = false; Scanner.scan() }) else null,
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
                        if (KueApp.mayRunLocked(androidx.compose.ui.platform.LocalContext.current)) "Carries on with the phone locked or KueSounds in the background."
                        else "Carries on in the background once the phone lets it: see Notifications in Settings.",
                        13, K.Soft, lines = 2,
                    )
                }
            }
        }
    }

    /** Every Kue app's settings in the same eight groups (see KueSettings). One group open at a time. */
    internal val look = KueSettings.Look(
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
        KueSettings.Place("app", "KueSounds", "Your music", "Fix the files"),
        KueSettings.Place("app", "KueSounds", "Your music", "Albums still to fix"),
        KueSettings.Place("app", "KueSounds", "Match by sound", "AcoustID key"),
        KueSettings.Place("app", "KueSounds", "Match by sound", "Match by sound, like Shazam"),
        KueSettings.Place("app", "KueSounds", "Your music", "Start the library again"),
        KueSettings.Place("app", "KueSounds", "Your music", "Find duplicate songs"),
        KueSettings.Place("app", "KueSounds", "Playing", "Play over mobile data"),
        KueSettings.Place("app", "KueSounds", "Playing", "Carry on long songs where you left off"),
        KueSettings.Place("app", "KueSounds", "Playing", "Remember shuffle"),
        KueSettings.Place("app", "KueSounds", "Playing", "Pause for other apps and calls"),
        KueSettings.Place("app", "KueSounds", "Playing", "Play when headphones are plugged in"),
        KueSettings.Place("app", "KueSounds", "Playing", "Play when Bluetooth connects"),
        KueSettings.Place("app", "KueSounds", "Playing", "Even loudness"),
        KueSettings.Place("app", "KueSounds", "Playing", "Songs kept to play again"),
        KueSettings.Place("look", "Look and feel", null, "Black background"),
        KueSettings.Place("look", "Look and feel", null, "Keep upright"),
        KueSettings.Place("look", "Look and feel", null, "Large covers across"),
        KueSettings.Place("look", "Look and feel", null, "Opens on"),
        KueSettings.Place("look", "Look and feel", null, "Parts shown under Music"),
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
        KueSettings.Place("privacy", "Privacy and storage", "Space on this phone", "Saved songs kept small"),
        KueSettings.Place("privacy", "Privacy and storage", "Space on this phone", "Room for saved songs"),
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
        // One open section a line. Not "|" between them: a section's own id has a "|" in it ("app|Your music"),
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
                // What is left to fix shows on the closed section (Kevin, 25 November 2026); fixing is step 5 inside it.
                val stuck = data.tracks.count { it.id in data.fixProblems && !(it.fixed && it.fixedFully) }
                val fixNote = when {
                    Scanner.running -> "Step " + Scanner.stage + " of 5"
                    Fixer.running -> "Fixing now"
                    view.leftToFix > 0 -> String.format("%,d", view.leftToFix) + " to fix" + if (stuck > 0) " · $stuck stuck" else ""
                    stuck > 0 -> "$stuck stuck"
                    view.matched.isNotEmpty() -> "All fixed"
                    else -> ""
                }
                KueSettings.Section("Your music", sectionOpen("app|Your music"), look, { flip("app|Your music") }, note = fixNote) {
                    MusicSteps(view, go)
                    if (!Scanner.running) Button("Look for new music", main = false, modifier = Modifier.fillMaxWidth()) { Scanner.scan() }
                    // CloudPlayer's "library cleanup" and "reset": songs gone from the Drive drop out at every look; this
                    // starts the whole library again from the Drive, keeping favourites, playlists and saved songs.
                    var sureAgain by remember { mutableStateOf(false) }
                    if (!Scanner.running && !Fixer.running) Button(if (sureAgain) "Yes, start it again" else "Start the library again", main = false, modifier = Modifier.fillMaxWidth()) {
                        if (!sureAgain) sureAgain = true else { sureAgain = false; Scanner.startAgain() }
                    }
                    var dupes by remember { mutableStateOf(false) }
                    Button("Find duplicate songs", main = false, icon = KueSettings.Icons.SEARCH, modifier = Modifier.fillMaxWidth()) { dupes = true }
                    if (dupes) DuplicatesBox(go) { dupes = false }
                    if (sureAgain) Words("Every song's details, covers and lookups are read again from your Drive, which takes hours. Favourites, playlists and songs on the phone stay.", 13, K.Soft, lines = 4)
                }
                KueSettings.Section("Match by sound", sectionOpen("app|Match by sound"), look, { flip("app|Match by sound") }) {
                    var key by remember { mutableStateOf(SoundMatch.key) }
                    Words("KueSounds listens to a song and names it, using Shazam's song finder. Nothing to sign up for.", 14, K.Soft, lines = 3)
                    Words("AcoustID key (a second song database, tried when Shazam doesn't know a song)", 13, K.Soft, lines = 2)
                    EditField(key) { key = it.trim(); SoundMatch.key = key }
                    Words(if (key.isBlank()) "No key: Shazam only." else "Key saved.", 13, K.Soft, lines = 2)
                }
                // How songs play (Kevin, 25 November 2026: CloudPlayer's settings as the least).
                @Suppress("UNUSED_VARIABLE") val seenPrefs = PlayPrefs.changes
                KueSettings.Section("Playing", sectionOpen("app|Playing"), look, { flip("app|Playing") }) {
                    KueSettings.Toggle("Play over mobile data", "Off: away from Wi-Fi only songs saved on the phone play, so no data is used.", PlayPrefs.mobileData, look) { PlayPrefs.mobileData = it }
                    KueSettings.Toggle("Carry on long songs where you left off", "Songs of " + PlayPrefs.keepPlaceMinutes + " minutes or more (mixes, live sets) start where they were left.", PlayPrefs.keepPlace, look) { PlayPrefs.keepPlace = it }
                    if (PlayPrefs.keepPlace) KueSettings.Choices("Long means", listOf("5 min", "10 min", "20 min", "30 min"), listOf(5, 10, 20, 30).indexOf(PlayPrefs.keepPlaceMinutes).coerceAtLeast(0), look) { PlayPrefs.keepPlaceMinutes = listOf(5, 10, 20, 30)[it] }
                    KueSettings.Toggle("Remember shuffle", "Shuffle stays on or off from one time to the next.", PlayPrefs.rememberShuffle, look) { PlayPrefs.rememberShuffle = it }
                    KueSettings.Toggle("Pause for other apps and calls", "Off: KueSounds keeps playing when another app makes a sound.", PlayPrefs.audioFocus, look) { PlayPrefs.audioFocus = it }
                    KueSettings.Toggle("Play when headphones are plugged in", "Carries on with what was playing.", PlayPrefs.headsetPlay, look) { PlayPrefs.headsetPlay = it }
                    KueSettings.Toggle("Play when Bluetooth connects", "Headphones, a speaker or the car. Carries on with what was playing.", PlayPrefs.bluetoothPlay, look) { PlayPrefs.bluetoothPlay = it }
                    KueSettings.Toggle("Even loudness", "Quiet songs brought up and loud ones down to the same level.", PlayPrefs.evenLoudness, look) { PlayPrefs.evenLoudness = it }
                    KueSettings.Choices("Songs kept to play again", listOf("200 MB", "500 MB", "1 GB", "2 GB"), listOf(200, 500, 1000, 2000).indexOf(PlayPrefs.cacheMb).coerceAtLeast(1), look) { PlayPrefs.cacheMb = listOf(200, 500, 1000, 2000)[it] }
                    KueSettings.Note("Songs play one into the next with no gap when the files allow it. The space kept changes the next time KueSounds starts.", look)
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
                @Suppress("UNUSED_VARIABLE") val seenLook = PlayPrefs.changes
                KueSettings.Toggle("Black background", "Black in place of the dark blue. Saves battery on most phones.", PlayPrefs.black, look) { PlayPrefs.black = it }
                KueSettings.Toggle("Keep upright", "KueSounds stays upright when the phone is turned.", PlayPrefs.portraitLock, look) { on ->
                    PlayPrefs.portraitLock = on
                    (context as? android.app.Activity)?.requestedOrientation = if (on) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                KueSettings.Choices("Large covers across", listOf("By screen", "2", "3", "4"), listOf(0, 2, 3, 4).indexOf(PlayPrefs.coverColumns).coerceAtLeast(0), look) { PlayPrefs.coverColumns = listOf(0, 2, 3, 4)[it] }
                var startOpen by remember { mutableStateOf(false) }
                KueSettings.FanOut("Opens on", musicViews.map { it.label }, musicViews.indexOfFirst { it.name == PlayPrefs.startPart }.coerceAtLeast(0), startOpen, look, { startOpen = it }) { i ->
                    startOpen = false
                    PlayPrefs.startPart = musicViews[i].name
                }
                KueSettings.Heading("Parts shown under Music", look)
                for (t in musicViews) {
                    val shown = t.name !in PlayPrefs.hiddenParts
                    KueSettings.Toggle(t.label, "", shown, look) { on -> PlayPrefs.hiddenParts = if (on) PlayPrefs.hiddenParts - t.name else PlayPrefs.hiddenParts + t.name }
                }
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
                // Long jobs carry on with the phone locked only once the phone stops putting KueSounds to sleep (Kevin,
                // 26 November 2026: the find music flow must carry on in the background).
                val free = KueApp.mayRunLocked(context)
                KueSettings.Toggle("Keep working with the phone locked", "Finding, fixing and saving music carry on in the background.", free, look) { KueApp.askToRunLocked(context) }
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
                    // Saved songs kept small (Kevin, 26 November 2026: offline with very little storage).
                    @Suppress("UNUSED_VARIABLE") val seenSave = PlayPrefs.changes
                    KueSettings.Choices("Saved songs kept", listOf("As they are", "Small", "Smallest"), PlayPrefs.saveSize, look) { PlayPrefs.saveSize = it }
                    KueSettings.Note(
                        when (PlayPrefs.saveSize) {
                            1 -> "Small: about a third of the room. Sounds the same to most ears."
                            2 -> "Smallest: about a sixth of the room. Fine on headphones on the move."
                            else -> "Each song's own file, at its full quality."
                        } + " Changes songs saved from now on.", look,
                    )
                    KueSettings.Choices("Room for saved songs", listOf("No limit", "1 GB", "2 GB", "5 GB"), listOf(0, 1000, 2000, 5000).indexOf(PlayPrefs.saveLimitMb).coerceAtLeast(0), look) { PlayPrefs.saveLimitMb = listOf(0, 1000, 2000, 5000)[it] }
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
