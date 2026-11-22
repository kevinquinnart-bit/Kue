package art.kquinn.kuesounds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The podcast screens: your shows and their newest episodes, finding shows, a show, an episode, and the player's buttons. */
object PodUi {

    @Composable
    private fun Art(url: String, size: Int) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
            if (url.isNotEmpty()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Sign(Glyph.NOTE, (size / 2).dp, K.Soft)
        }
    }

    @Composable
    private fun Chip(label: String, on: Boolean = false, onClick: () -> Unit) {
        Box(
            Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) K.Accent else K.Panel).clickable(onClick = onClick).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Ui.Words(label, 15, if (on) K.Page else K.Ink, bold = on) }
    }

    @Composable
    private fun Heading(text: String) {
        Ui.Words(text, 19, bold = true, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }

    // ---------------------------------------------------------------------------------------------
    // The Podcasts tab

    @Composable
    fun Home(go: (Screen) -> Unit) {
        val scope = rememberCoroutineScope()
        val touched = Podcasts.touches
        val followed = Podcasts.followed()
        // New episodes looked for on opening, when it hasn't been done for half an hour.
        LaunchedEffect(followed.size) {
            if (followed.isNotEmpty() && Podcasts.refreshIfStale()) withContext(Dispatchers.IO) { Podcasts.refresh() }
        }
        val context = androidx.compose.ui.platform.LocalContext.current
        var moved by remember { mutableStateOf("") }
        // A file of shows from another podcast app (OPML): every show in it is followed here.
        val bringIn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            moved = ""
            scope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty() }
                val feeds = Podcasts.feedsInFile(text)
                if (feeds.isEmpty()) { moved = "No shows were found in that file. In your other app, look for Export or Back up, and pick OPML."; return@launch }
                val (added, failed) = withContext(Dispatchers.IO) { Podcasts.importFeeds(feeds) }
                moved = "$added shows added" + (if (feeds.size - added - failed > 0) ", " + (feeds.size - added - failed) + " already here" else "") +
                    (if (failed > 0) ", $failed couldn't be read" else "") + "."
            }
        }
        // Your shows as a file, to take to another podcast app.
        val sendOut = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Podcasts.opml().toByteArray()) } }.isSuccess }
                moved = if (ok) "Your shows are saved in that file." else "The file couldn't be written."
            }
        }
        val going = remember(touched, Podcasts.episodes, Sync.entries) { Podcasts.inProgress().take(10) }
        val latest = remember(touched, Podcasts.episodes, Sync.entries) { Podcasts.latest() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ui.Button("Find a podcast", glyph = Glyph.SEARCH, modifier = Modifier.weight(1f)) { go(Screen.PodcastFind) }
                    Ui.Button(if (Podcasts.refreshing) "Checking…" else "Check for new", main = false, modifier = Modifier.weight(1f)) {
                        scope.launch { withContext(Dispatchers.IO) { Podcasts.refresh() } }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("Bring in shows from another app") { bringIn.launch(arrayOf("*/*")) } }
                    if (followed.isNotEmpty()) item { Chip("Save shows as a file") { sendOut.launch("KueSounds podcasts.opml") } }
                }
            }
            if (Podcasts.importing.isNotEmpty()) item { Ui.Words(Podcasts.importing, 15, K.Accent) }
            if (moved.isNotEmpty()) item { Ui.Words(moved, 15, K.Accent, lines = 3) }
            if (Podcasts.problem.isNotEmpty()) item { Ui.Words(Podcasts.problem, 15, K.Accent, lines = 3) }
            if (followed.isEmpty()) {
                item {
                    Ui.Words(
                        "Follow a show and its newest episodes show here. Where you got to in each episode is kept, on this phone and the TV alike.",
                        16, K.Soft, lines = 5,
                    )
                }
                return@LazyColumn
            }
            item { Heading("Your shows") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(followed, key = { it.feed }) { s ->
                        val fresh = remember(touched, Podcasts.episodes, Sync.entries) { Podcasts.unplayedCount(s.feed) }
                        Column(Modifier.width(120.dp).clickable { go(Screen.ShowPage(s.feed)) }) {
                            Box {
                                Art(s.art, 120)
                                if (fresh > 0) Box(
                                    Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(K.Accent).padding(horizontal = 8.dp, vertical = 2.dp),
                                ) { Ui.Words(if (fresh > 49) "50+" else fresh.toString(), 13, K.Page, bold = true) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Ui.Words(s.title, 14, lines = 2)
                        }
                    }
                }
            }
            if (going.isNotEmpty()) {
                item { Heading("Carry on listening") }
                items(going, key = { "go:" + it.id }) { e -> EpisodeRow(e, showName = true, list = going, go = go) }
            }
            item { Heading("Newest episodes") }
            items(latest, key = { "new:" + it.id }) { e -> EpisodeRow(e, showName = true, list = latest, go = go) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    /** One episode in a list: its picture, name, date and length, how far it has got, and a play button. */
    @Composable
    private fun EpisodeRow(e: Podcasts.Episode, showName: Boolean, list: List<Podcasts.Episode>, go: (Screen) -> Unit) {
        val touched = Podcasts.touches
        val show = Podcasts.showOf(e)
        val at = remember(touched, Sync.entries, e.id) { Podcasts.position(e.id) }
        val played = remember(touched, Sync.entries, e.id) { Podcasts.isPlayed(e.id) }
        val playingThis = Playing.currentId == "pod:" + e.id
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { go(Screen.EpisodePage(e.id)) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(e.art.ifEmpty { show?.art.orEmpty() }, 56)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (showName) Ui.Words(show?.title.orEmpty(), 13, K.Soft)
                Ui.Words(e.title, 15, if (played) K.Soft else K.Ink, bold = !played, lines = 2)
                val bits = listOfNotNull(
                    Podcasts.dateWords(e.published).ifEmpty { null },
                    remember(touched, Sync.entries, e.id) { Podcasts.lengthWords(e) }.ifEmpty { null },
                    "On phone".takeIf { e.id in Podcasts.saved },
                    Podcasts.saving[e.id]?.let { "Saving $it%" },
                )
                Ui.Words(bits.joinToString(" · "), 13, K.Soft)
                if (at > 0 && !played && e.durationSec > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (at / 1000f / e.durationSec).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = K.Accent, trackColor = K.Raised,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (playingThis) K.Accent else K.Raised).clickable(onClickLabel = "Play") {
                    if (playingThis) Playing.toggle() else Playing.playEpisodes(list, list.indexOf(e).coerceAtLeast(0))
                },
                contentAlignment = Alignment.Center,
            ) { Sign(if (playingThis && Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 22.dp, if (playingThis) K.Page else K.Ink) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finding a show

    @Composable
    fun Find(back: () -> Unit, go: (Screen) -> Unit) {
        val scope = rememberCoroutineScope()
        var query by rememberSaveable { mutableStateOf("") }
        var asked by rememberSaveable { mutableStateOf("") }
        var results by remember { mutableStateOf<List<Podcasts.Found>?>(null) }
        var top by remember { mutableStateOf<List<Podcasts.Found>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var adding by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            top = withContext(Dispatchers.IO) { runCatching { Podcasts.top() }.getOrNull() }
        }
        val isAddress = query.trim().startsWith("http", ignoreCase = true)
        val searchNow: () -> Unit = {
            if (query.isNotBlank() && !isAddress) {
                asked = query
                busy = true
                note = ""
                scope.launch {
                    results = withContext(Dispatchers.IO) { runCatching { Podcasts.search(query) }.getOrNull() }
                    if (results == null) note = "The podcast directory didn't answer. Check the internet and try again."
                    busy = false
                }
            }
        }
        val add: (String) -> Unit = { feed ->
            adding = feed
            note = ""
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Podcasts.follow(feed) } }
                adding = null
                if (ok.isSuccess) go(Screen.ShowPage(feed))
                else note = "That show's feed couldn't be read: " + (ok.exceptionOrNull()?.message ?: "no answer")
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                Spacer(Modifier.width(12.dp))
                Ui.Words("Find a podcast", 22, bold = true)
            }
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Sign(Glyph.SEARCH, 20.dp, K.Soft)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Ui.Words("A show's name, or its web address", 16, K.Soft)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = K.Ink, fontSize = 16.sp, fontFamily = K.Poppins),
                        cursorBrush = SolidColor(K.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { searchNow() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (isAddress) Ui.Button(if (adding != null) "Adding…" else "Follow the show at this address", modifier = Modifier.fillMaxWidth()) { add(query.trim()) }
            else if (query.isNotBlank()) Ui.Button("Search", modifier = Modifier.fillMaxWidth(), onClick = searchNow)
            if (note.isNotEmpty()) Ui.Words(note, 15, K.Accent, lines = 3)
            if (busy) CircularProgressIndicator(color = K.Accent)
            val list = if (asked.isNotEmpty() && query.isNotBlank()) results else top
            val heading = if (asked.isNotEmpty() && query.isNotBlank()) "Shows for \"$asked\"" else "Top podcasts in the UK"
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Heading(heading) }
                if (list == null) item { Ui.Words(if (asked.isEmpty()) "Loading the charts…" else "", 15, K.Soft) }
                else if (list.isEmpty()) item { Ui.Words("Nothing found. Try other words.", 15, K.Soft) }
                else items(list, key = { it.feed }) { f ->
                    val on = Podcasts.isFollowed(f.feed)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel)
                            .clickable { if (on) go(Screen.ShowPage(f.feed)) else add(f.feed) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Art(f.art, 60)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Ui.Words(f.title, 15, bold = true, lines = 2)
                            Ui.Words(listOf(f.author, f.genre).filter { it.isNotBlank() }.joinToString(" · "), 13, K.Soft)
                        }
                        Ui.Words(if (adding == f.feed) "Adding…" else if (on) "Following" else "Follow", 14, if (on) K.Soft else K.Accent, bold = !on)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A show

    @Composable
    fun ShowPage(feed: String, back: () -> Unit, go: (Screen) -> Unit) {
        val scope = rememberCoroutineScope()
        val touched = Podcasts.touches
        val show = Podcasts.shows.firstOrNull { it.feed == feed }
        val all = Podcasts.episodes[feed].orEmpty()
        val following = Podcasts.isFollowed(feed)
        var filter by rememberSaveable { mutableStateOf(0) } // 0 all, 1 not played, 2 on phone
        var oldestFirst by rememberSaveable { mutableStateOf(false) }
        var more by remember { mutableStateOf(false) }
        // A show opened that isn't kept yet (from the directory): read now.
        LaunchedEffect(feed) {
            if (show == null) withContext(Dispatchers.IO) { runCatching { Podcasts.follow(feed) } }
        }
        val shown = remember(all, filter, oldestFirst, touched, Sync.entries, Podcasts.saved) {
            all.filter {
                when (filter) {
                    1 -> !Podcasts.isPlayed(it.id)
                    2 -> it.id in Podcasts.saved
                    else -> true
                }
            }.let { if (oldestFirst) it.reversed() else it }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Ui.RoundButton(Glyph.BACK, "Back", onClick = back) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(show?.art.orEmpty(), 120)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Ui.Words(show?.title ?: "Loading…", 20, bold = true, lines = 3)
                        Ui.Words(show?.author.orEmpty(), 14, K.Soft, lines = 2)
                        Ui.Words(all.size.toString() + " episodes", 14, K.Soft)
                    }
                }
            }
            if (!show?.about.isNullOrBlank()) item {
                Ui.Words(show!!.about, 15, K.Soft, lines = if (more) 40 else 3, modifier = Modifier.clickable { more = !more })
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val next = shown.lastOrNull { !Podcasts.isPlayed(it.id) }.takeIf { oldestFirst } ?: all.firstOrNull { !Podcasts.isPlayed(it.id) }
                    if (next != null) Ui.Button("Play newest", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) {
                        val list = all
                        Playing.playEpisodes(list, list.indexOf(next).coerceAtLeast(0))
                    }
                    Ui.Button(if (following) "Unfollow" else "Follow", main = false, modifier = Modifier.weight(1f)) {
                        if (following) Podcasts.unfollow(feed) else scope.launch { withContext(Dispatchers.IO) { runCatching { Podcasts.follow(feed) } } }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("All", filter == 0) { filter = 0 } }
                    item { Chip("Not played", filter == 1) { filter = 1 } }
                    item { Chip("On phone", filter == 2) { filter = 2 } }
                    item { Chip(if (oldestFirst) "Oldest first" else "Newest first") { oldestFirst = !oldestFirst } }
                }
            }
            if (shown.isEmpty()) item { Ui.Words(if (all.isEmpty()) "Reading the show's episodes…" else "No episodes here.", 15, K.Soft) }
            items(shown, key = { it.id }) { e -> EpisodeRow(e, showName = false, list = shown, go = go) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // An episode

    @Composable
    fun EpisodePage(id: String, back: () -> Unit, go: (Screen) -> Unit) {
        val touched = Podcasts.touches
        val e = remember(id, Podcasts.episodes) { Podcasts.find(id) }
        if (e == null) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                Ui.Words("This episode is no longer in the show's feed.", 17, K.Soft)
            }
            return
        }
        val show = Podcasts.showOf(e)
        val at = remember(touched, Sync.entries) { Podcasts.position(e.id) }
        val played = remember(touched, Sync.entries) { Podcasts.isPlayed(e.id) }
        val list = Podcasts.episodes[e.feed].orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Ui.RoundButton(Glyph.BACK, "Back", onClick = back) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Art(e.art.ifEmpty { show?.art.orEmpty() }, 110)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f).clickable { go(Screen.ShowPage(e.feed)) }) {
                        Ui.Words(show?.title.orEmpty(), 14, K.Soft, lines = 2)
                        Ui.Words(e.title, 19, bold = true, lines = 4)
                        Ui.Words(listOf(Podcasts.dateWords(e.published), Podcasts.lengthWords(e)).filter { it.isNotEmpty() }.joinToString(" · "), 14, K.Soft)
                    }
                }
            }
            item {
                Ui.Button(
                    when {
                        played -> "Play again"
                        at > 0 -> "Carry on from " + Library.clock(at)
                        else -> "Play"
                    },
                    glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth(),
                ) { Playing.playEpisodes(list, list.indexOf(e).coerceAtLeast(0)) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ui.Button(if (played) "Mark not played" else "Mark played", main = false, modifier = Modifier.weight(1f)) { Podcasts.setPlayed(e.id, !played) }
                    val saving = Podcasts.saving[e.id]
                    Ui.Button(
                        when {
                            saving != null -> "Saving $saving%"
                            e.id in Podcasts.saved -> "Take off phone"
                            else -> "Save to phone"
                        },
                        main = false, glyph = Glyph.SAVE, modifier = Modifier.weight(1f),
                    ) {
                        if (saving == null) { if (e.id in Podcasts.saved) Podcasts.remove(e) else Podcasts.save(e) }
                    }
                }
            }
            if (Podcasts.problem.isNotEmpty()) item { Ui.Words(Podcasts.problem, 15, K.Accent, lines = 3) }
            if (e.about.isNotBlank()) item { Ui.Words(e.about, 15, K.Soft, lines = 200) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The player's buttons for a podcast

    @Composable
    fun Controls() {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Jump("−15") { Playing.skip(-15_000) }
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(K.Accent).clickable(onClickLabel = if (Playing.isPlaying) "Pause" else "Play") { Playing.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (Playing.buffering) CircularProgressIndicator(color = K.Page, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                    else Sign(if (Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 34.dp, K.Page)
                }
                Jump("+30") { Playing.skip(30_000) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = Playing.speed
                Box(Modifier.weight(1f)) { Chip("Speed " + (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×", s != 1f) { Playing.nextSpeed() } }
                val sleepWords = when {
                    Playing.sleepAtEnd -> "Sleep: end of episode"
                    Playing.sleepAt > 0 -> "Sleep in " + ((Playing.sleepAt - System.currentTimeMillis()) / 60_000 + 1) + " min"
                    else -> "Sleep timer"
                }
                Box(Modifier.weight(1.4f)) { Chip(sleepWords, Playing.sleepAt > 0 || Playing.sleepAtEnd) { Playing.nextSleep() } }
                Box(Modifier.weight(1f)) { Chip("Next") { Playing.next() } }
            }
        }
    }

    @Composable
    private fun Jump(label: String, onClick: () -> Unit) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(K.Panel).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Ui.Words(label, 17, bold = true)
        }
    }
}
