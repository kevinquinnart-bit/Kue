package art.kquinn.kuesounds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.tag.FieldKey

/**
 * Check them one at a time (Kevin, 27 November 2026: "shouldn't AI be able to pull all info into a song file and present
 * for acceptance", with pop-ups rather than more on the screen). For each song or album KueSounds couldn't finish, it
 * works out the whole answer itself (Shazam at up to four places, then Apple Music, Deezer and Wikipedia for the album,
 * year, kind and cover) and shows it beside what is in the file now. One tap: Accept and save, Change it myself, Leave
 * it as it is (never listed again), or Skip.
 */
object ReviewUi {
    /** One thing to check: a song, or an album (its songs and name). */
    data class Item(val songs: List<Track>, val album: String?, val why: String)

    /** Everything not fixed, songs first, as one list. */
    fun items(left: Scanner.Left): List<Item> =
        left.songs.map { (t, why) -> Item(listOf(t), null, why) } + left.albums.map { Item(it.tracks, it.title, "No cover found anywhere.") }

    /**
     * The whole answer for one item, worked out from everything KueSounds can ask, or null when nothing knows it.
     * [now] is what is in the file. Off the main thread.
     */
    fun propose(item: Item, now: Map<FieldKey, String>, trying: (String) -> Unit): Covers.Lookup? {
        // Sort with AI's answer when it has one; else asked now when a key is set (Kevin, 28 November 2026).
        AiSort.answerFor(item)?.takeIf { it.artist.isNotBlank() || it.album.isNotBlank() }?.let { return it }
        if (AiSort.whose(AiSort.key).isNotEmpty()) {
            val s = runCatching { AiSort.ask(item, now, trying) }.getOrNull()
            if (s != null && (s.look.artist.isNotBlank() || s.look.album.isNotBlank())) {
                trying("Finding its cover")
                val address = runCatching { AiSort.coverFor(s, item.album != null).second }.getOrDefault("")
                return s.look.copy(small = address.ifBlank { s.look.small }, big = address.ifBlank { s.look.big })
            }
        }
        val t = item.songs.first()
        val artist = now[FieldKey.ARTIST].orEmpty().ifBlank { t.artist }.takeIf { it.isNotBlank() && it != Library.UNKNOWN_ARTIST }.orEmpty()
        if (item.album == null) {
            val heard = runCatching { SoundMatch.identifyAnywhere(t) { trying(it) }.firstOrNull() }.getOrNull()
            var best = heard?.let { f ->
                Covers.Lookup(f.title, f.artist, f.albums.firstOrNull().orEmpty(), f.artist, f.year.take(4), 0, 0, f.genre, f.cover.orEmpty(), f.cover.orEmpty(), f.from)
            }
            val title = best?.title ?: Library.withoutBrackets(now[FieldKey.TITLE].orEmpty().ifBlank { t.title })
            val who = best?.artist ?: artist
            trying("Looking up its album and cover")
            val found = runCatching { Covers.lookUp((who + " " + title).trim(), true, title) }.getOrDefault(emptyList())
            val match = found.firstOrNull { !it.coverOnly && Covers.same(Library.withoutBrackets(it.title), Library.withoutBrackets(title)) && (who.isBlank() || Covers.sameArtist(it.artist, who)) }
            best = when {
                best == null -> match
                match == null -> best
                else -> best.copy(
                    album = best.album.ifBlank { match.album }, albumArtist = best.albumArtist.ifBlank { match.albumArtist },
                    year = best.year.ifBlank { match.year }, genre = best.genre.ifBlank { match.genre },
                    small = best.small.ifBlank { match.small }, big = best.big.ifBlank { match.big },
                    from = if (best.big.isBlank()) best.from + " and " + match.from else best.from,
                )
            }
            val b = best
            val wiki = found.firstOrNull { it.coverOnly }
            if (b != null && b.big.isBlank() && wiki != null) return b.copy(small = wiki.small, big = wiki.big, from = b.from + ", cover from Wikipedia")
            return b
        }
        // An album with no cover: the album itself by name, then its first song's own cover by its sound, then Wikipedia.
        trying("Looking up the album")
        val found = runCatching { Covers.lookUp((artist + " " + item.album).trim(), false) }.getOrDefault(emptyList())
        found.firstOrNull { !it.coverOnly && Covers.same(it.album, item.album) && (artist.isBlank() || Covers.sameArtist(it.artist, artist)) }?.let { return it.copy(title = "") }
        val heard = runCatching { SoundMatch.identifyAnywhere(t) { trying(it + " · its first song") }.firstOrNull() }.getOrNull()
        if (heard?.cover?.isNotBlank() == true) {
            return Covers.Lookup("", heard.artist, item.album, "", heard.year.take(4), 0, 0, heard.genre, heard.cover, heard.cover, heard.from + " (the cover of its first song)", coverOnly = true)
        }
        return found.firstOrNull { it.coverOnly }
    }

    @Composable
    fun Review(items0: List<Item>, onClose: () -> Unit) {
        // The list as it was when opened (Kevin, 27 November 2026): the list in Settings shrinks as each one is saved,
        // which moved this on to the next song before the last was finished and showed the wrong song's file.
        val items = remember { items0 }
        var at by remember { mutableIntStateOf(0) }
        var saved by remember { mutableIntStateOf(0) }
        var left by remember { mutableIntStateOf(0) }
        var skipped by remember { mutableIntStateOf(0) }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KueSettings.PanelHeader(if (at < items.size) "Check " + (at + 1) + " of " + items.size else "All checked", Ui.look, onClose = onClose)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (items.isEmpty()) 1f else at.toFloat() / items.size }, color = K.Accent, trackColor = K.Raised,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                if (at >= items.size) {
                    Ui.Words(
                        listOf(if (saved > 0) "$saved saved" else "", if (left > 0) "$left right as they were" else "", if (skipped > 0) "$skipped for next time" else "")
                            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Nothing changed." },
                        16,
                    )
                    Ui.Button("Done", modifier = Modifier.fillMaxWidth(), onClick = onClose)
                } else key(at, items[at].songs.first().id) {
                    One(items[at]) { result ->
                        when (result) { "saved" -> saved++; "left" -> left++; else -> skipped++ }
                        at++
                    }
                }
            }
        }
    }

    @Composable
    private fun One(item: Item, onDone: (String) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val t = item.songs.first()
        var now by remember { mutableStateOf<Map<FieldKey, String>?>(null) }
        var finding by remember { mutableStateOf("Reading the file…") }
        var answer by remember { mutableStateOf<Covers.Lookup?>(null) }
        var nothing by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf("") }
        var byHand by remember { mutableStateOf(false) }
        // After a save: what is in the file now, read back from the Drive, to check before moving on (Kevin, 27 November
        // 2026: "needs to be a final check that all is ok before moving on").
        var savedTimes by remember { mutableIntStateOf(0) }

        DisposableEffect(t.id) { onDispose { Fixer.dropCopy(context.applicationContext, t) } }
        LaunchedEffect(t.id) {
            val read = withContext(Dispatchers.IO) { runCatching { Fixer.readFile(context.applicationContext, t) }.getOrNull() }
            val known = read?.known ?: mapOf(FieldKey.TITLE to t.title, FieldKey.ARTIST to t.artist, FieldKey.ALBUM to t.album, FieldKey.YEAR to if (t.year > 0) t.year.toString() else "")
            now = known
            answer = withContext(Dispatchers.IO) { propose(item, known) { finding = it } }
            nothing = answer == null
            finding = ""
        }

        // An album of one song opens as that song, with every detail (Kevin, 27 November 2026: "only a short list").
        if (byHand) FixByHandUi.Page(item.songs, if (item.songs.size == 1) null else item.album, item.why) {
            byHand = false
            // Saved in there: that page ended on the final check of the file, so this one is done.
            if (item.songs.all { s -> Scanner.data.tracks.firstOrNull { it.id == s.id }?.settled == true }) onDone("saved")
        }

        if (savedTimes > 0) {
            // Saved: the file itself, read back, with its picture, before moving on.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                key(savedTimes) { FileNow(t, item.songs.size, Modifier.weight(1f)) }
                Ui.Button("All good, next", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth()) { onDone("saved") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Ui.Words("Change it again", 15, K.Accent, bold = true, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { byHand = true }.padding(8.dp))
                }
            }
            return
        }

        // One page (Kevin, 27 November 2026: "combine this with the following page"): KueSounds' answer put straight into
        // the editor, every detail and the cover shown as changes to keep or alter, Save at the foot, then the check.
        if (finding.isNotEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val title = item.album ?: t.title
                Ui.Words(title + " · " + t.artist, 16, bold = true)
                Ui.Words(item.why, 13, K.Accent)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Ui.Words(finding, 14, K.Accent, modifier = Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel)) { Hear(t) }
            }
            return
        }
        val a = answer
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(Modifier.fillMaxSize()) {
            with(FixByHandUi) {
                Editor(
                    item.songs, if (item.songs.size == 1) null else item.album, item.why, scroll,
                    suggest = a,
                    note = when {
                        a == null -> "Nothing knows it by its sound or its name: search the web below, or Save to keep it as it is."
                        a.coverOnly -> "KueSounds suggests the cover from " + a.from + "."
                        else -> "KueSounds suggests these from " + a.from + ": the changes are in yellow. Keep, change, then Save."
                    },
                    onSaved = { savedTimes++ },
                    onClose = {},
                )
            }
        }
    }

    /**
     * What is in a song's file now, read back from the Drive: its own picture (with a small player over it) and its
     * details, for the check after a save (Kevin, 27 November 2026: "show what the final file is like with the album art").
     */
    @Composable
    fun FileNow(t: Track, songs: Int, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        var got by remember { mutableStateOf<Tagger.All?>(null) }
        var problem by remember { mutableStateOf("") }
        LaunchedEffect(t.id) {
            withContext(Dispatchers.IO) { runCatching { Fixer.readFile(context.applicationContext, t) } }
                .onSuccess { got = it }.onFailure { problem = "The file couldn't be read back: " + (it.message ?: it.javaClass.simpleName) }
        }
        DisposableEffect(t.id) { onDispose { Fixer.dropCopy(context.applicationContext, t) } }
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Ui.Words("Saved. This is in the file now:", 16, bold = true)
            val g = got
            when {
                problem.isNotEmpty() -> Ui.Words(problem, 14, K.Accent)
                g == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Ui.Words("Reading the file back…", 15, K.Accent)
                }
                else -> {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
                        Box(Modifier.size(side).clip(RoundedCornerShape(16.dp)).background(K.Panel).border(2.dp, K.Accent, RoundedCornerShape(16.dp))) {
                            if (g.picture != null) AsyncImage(model = g.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Ui.Words("No picture in the file", 14, K.Soft, centre = true, modifier = Modifier.align(Alignment.Center))
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))) { Hear(t) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for ((k, label) in Tagger.named) {
                            val v = g.known[k].orEmpty()
                            if (v.isBlank() || k == FieldKey.LYRICS || k == FieldKey.COMMENT) continue
                            Row {
                                Ui.Words(label, 13, K.Soft, modifier = Modifier.width(110.dp))
                                Ui.Words(v, 13, modifier = Modifier.weight(1f))
                            }
                        }
                        if (songs > 1) Ui.Words("Shown: its first song. All $songs were saved.", 12, K.Soft)
                    }
                }
            }
        }
    }

    /** The final check as its own pop-up, after a save from Fix it by hand: the file as it is now, then All good closes. */
    @Composable
    fun FinalCheck(t: Track, songs: Int, onChangeAgain: () -> Unit, onDone: () -> Unit) {
        Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KueSettings.PanelHeader("Saved", Ui.look, onClose = onDone)
                FileNow(t, songs, Modifier.weight(1f))
                Ui.Button("All good", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth(), onClick = onDone)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Ui.Words("Change it again", 15, K.Accent, bold = true, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onChangeAgain).padding(8.dp))
                }
            }
        }
    }

    /**
     * Play, pause and a slider for one song, as thin as the player's own (no big buttons). Plays through the normal
     * player; moving on to the next one stops it.
     */
    @Composable
    fun Hear(t: Track) {
        val mine = Playing.currentId == t.id
        var position by remember { mutableStateOf(0L) }
        var dragging by remember { mutableStateOf(false) }
        var dragAt by remember { mutableStateOf(0f) }
        LaunchedEffect(mine) {
            while (mine) { if (!dragging) position = Playing.positionMs(); kotlinx.coroutines.delay(500) }
        }
        DisposableEffect(t.id) { onDispose { if (Playing.currentId == t.id && Playing.isPlaying) Playing.pause() } }
        val duration = if (mine) Playing.durationMs else t.durationMs
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clickable(onClickLabel = if (mine && Playing.isPlaying) "Pause" else "Play") {
                    if (mine) Playing.toggle() else Playing.play(listOf(t), 0)
                },
                contentAlignment = Alignment.Center,
            ) { Sign(if (mine && Playing.isPlaying) Glyph.PAUSE else Glyph.PLAY, 24.dp, K.Ink) }
            androidx.compose.material3.Slider(
                value = if (dragging) dragAt else if (mine && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = true; dragAt = it },
                onValueChangeFinished = {
                    if (mine) { Playing.seekTo((dragAt * duration).toLong()); position = (dragAt * duration).toLong() }
                    dragging = false
                },
                enabled = mine,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = K.Ink, activeTrackColor = K.Accent, inactiveTrackColor = K.Raised, disabledThumbColor = K.Soft, disabledInactiveTrackColor = K.Raised),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Ui.Words(Library.clock(if (mine) (if (dragging) (dragAt * duration).toLong() else position) else duration), 12, K.Ink)
        }
    }

    /** The details the answer would write, with their names, in order. A cover-only answer writes the year at most. */
    private fun changesOf(item: Item, a: Covers.Lookup, now: Map<FieldKey, String>): List<Triple<FieldKey, String, String>> {
        val out = ArrayList<Triple<FieldKey, String, String>>()
        fun add(k: FieldKey, label: String, v: String) { if (v.isNotBlank()) out += Triple(k, label, v) }
        if (item.album == null && !a.coverOnly) {
            add(FieldKey.TITLE, "Song name", a.title)
            add(FieldKey.ARTIST, "Artist", a.artist)
            add(FieldKey.ALBUM, "Album", a.album)
            add(FieldKey.ALBUM_ARTIST, "Album artist", a.albumArtist.ifBlank { a.artist })
            add(FieldKey.YEAR, "Year", a.year)
            add(FieldKey.GENRE, "Genre", a.genre)
        } else if (item.album != null && !a.coverOnly) {
            add(FieldKey.YEAR, "Year", a.year)
            if (now[FieldKey.GENRE].isNullOrBlank()) add(FieldKey.GENRE, "Genre", a.genre)
        }
        return out
    }

    /** The one confirm pop-up, the same everywhere in KueSounds. */
    @Composable
    fun Confirm(title: String, text: String, yes: String, onYes: () -> Unit, onNo: () -> Unit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onNo,
            containerColor = K.Panel, titleContentColor = K.Ink, textContentColor = K.Ink,
            title = { Ui.Words(title, 18, bold = true) },
            text = { Ui.Words(text, 15) },
            confirmButton = { Ui.Button(yes, onClick = onYes) },
            dismissButton = { Ui.Button("Not now", main = false, onClick = onNo) },
        )
    }

    /** A list in a pop-up: what a step did (Kevin, 27 November 2026: "lists of match sound, closest artist"). Tap a song for its info. */
    @Composable
    fun SongList(title: String, songs: List<Track>, empty: String, ticks: Boolean = false, onClose: () -> Unit) {
        var open by remember { mutableStateOf<Track?>(null) }
        open?.let { t -> Ui.SongInfo(t) { open = null } }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KueSettings.PanelHeader(title, Ui.look, onClose = onClose)
                if (songs.isEmpty()) Ui.Words(empty, 15, K.Soft)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(songs.size) { i ->
                        val t = songs[i]
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).clickable { open = t }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ui.Words((i + 1).toString() + ".", 14, K.Soft, modifier = Modifier.width(36.dp))
                            Column(Modifier.weight(1f)) {
                                Ui.Words(t.title, 15, bold = true)
                                Ui.Words(t.artist + (if (t.album.isNotBlank()) " · " + t.album else ""), 13, K.Soft)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (ticks) {
                                // The reviewed tick, as in Song info: tap the box to tick, the row to open the song.
                                val on = t.id in Scanner.data.reviewed
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = if (on) "Untick reviewed" else "Tick as reviewed") { Scanner.markReviewed(t.id, !on) }, contentAlignment = Alignment.Center) {
                                    KueSettings.Icon(if (on) KueSettings.Icons.BOX_TICK else KueSettings.Icons.BOX, 26.dp, if (on) K.Accent else K.Soft, 2f)
                                }
                            } else KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Soft, 2.2f)
                        }
                    }
                }
            }
        }
    }

    /**
     * The AI's answers in one list, to look over and save together (Kevin, 28 November 2026: 135 sorted, only 10
     * written, the rest to save one at a time). Each shows the cover found, what it will be, and what the file says
     * now; a tick to keep it. Untick the wrong ones, Save the rest; a tap on one opens it to change by hand.
     */
    @Composable
    fun AiAnswers(items: List<Item>, onClose: () -> Unit) {
        val context = LocalContext.current
        val list = remember(items) { AiSort.answersFor(items).filter { it.second != null }.map { it.first to it.second!! } }
        val ticked = remember(list) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply { list.forEach { (i, l) -> put(i.songs.first().id, AiSort.tickedAtFirst(i, l)) } } }
        var open by remember { mutableStateOf<Item?>(null) }
        open?.let { i -> FixByHandUi.Page(i.songs, if (i.songs.size == 1) null else i.album, i.why) { open = null } }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(start = 18.dp, end = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KueSettings.PanelHeader("The AI's answers", Ui.look, onClose = onClose)
                Ui.Words("Untick any that are wrong, then save the rest. Tap one to change it by hand.", 14, K.Soft)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.size, key = { list[it].first.songs.first().id }) { n ->
                        val (item, l) = list[n]
                        val t = item.songs.first()
                        val id = t.id
                        val on = ticked[id] == true
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised)
                                .border(1.dp, if (on) K.Accent else K.Edge, RoundedCornerShape(12.dp)).clickable { open = item }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                                if (l.small.isNotBlank() || l.big.isNotBlank()) AsyncImage(model = l.small.ifBlank { l.big }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else KueSettings.Icon(KueSettings.Icons.MUSIC, 22.dp, K.Soft, 2f)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Ui.Words(if (item.album == null) l.title else l.album, 15, bold = true)
                                Ui.Words(listOf(l.artist, if (item.album == null) l.album else "", l.year).filter { it.isNotBlank() }.joinToString(" · "), 13, K.Accent)
                                Ui.Words("Now: " + (item.album ?: t.title) + " · " + t.artist, 12, K.Soft)
                            }
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = if (on) "Untick" else "Tick") { ticked[id] = !on }, contentAlignment = Alignment.Center) {
                                KueSettings.Icon(if (on) KueSettings.Icons.BOX_TICK else KueSettings.Icons.BOX, 26.dp, if (on) K.Accent else K.Soft, 2f)
                            }
                        }
                    }
                }
                val count = ticked.count { it.value }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ui.Button(if (count == 0) "None ticked" else "Save the $count ticked", icon = KueSettings.Icons.TICK, modifier = Modifier.weight(1.6f)) {
                        if (count == 0) return@Button
                        AiSort.saveTicked(context, list.filter { ticked[it.first.songs.first().id] == true })
                        onClose()
                    }
                    Ui.Button(if (count == list.size) "Untick all" else "Tick all", main = false, modifier = Modifier.weight(1f)) {
                        val all = count != list.size
                        list.forEach { ticked[it.first.songs.first().id] = all }
                    }
                }
            }
        }
    }
}
