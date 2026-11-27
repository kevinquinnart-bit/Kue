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
        val t = item.songs.first()
        val artist = now[FieldKey.ARTIST].orEmpty().ifBlank { t.artist }.takeIf { it.isNotBlank() && it != Library.UNKNOWN_ARTIST }.orEmpty()
        if (item.album == null) {
            val heard = runCatching { SoundMatch.identifyAnywhere(t) { trying(it + " · Shazam") }.firstOrNull() }.getOrNull()
            var best = heard?.let { f ->
                Covers.Lookup(f.title, f.artist, f.albums.firstOrNull().orEmpty(), f.artist, f.year.take(4), 0, 0, f.genre, f.cover.orEmpty(), f.cover.orEmpty(), "Shazam")
            }
            val title = best?.title ?: Library.withoutBrackets(now[FieldKey.TITLE].orEmpty().ifBlank { t.title })
            val who = best?.artist ?: artist
            trying("Looking up its album and cover")
            val found = runCatching { Covers.lookUp((who + " " + title).trim(), true) }.getOrDefault(emptyList())
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
        val heard = runCatching { SoundMatch.identifyAnywhere(t) { trying(it + " · Shazam, its first song") }.firstOrNull() }.getOrNull()
        if (heard?.cover?.isNotBlank() == true) {
            return Covers.Lookup("", heard.artist, item.album, "", heard.year.take(4), 0, 0, heard.genre, heard.cover, heard.cover, "Shazam (the cover of its first song)", coverOnly = true)
        }
        return found.firstOrNull { it.coverOnly }
    }

    @Composable
    fun Review(items: List<Item>, onClose: () -> Unit) {
        var at by remember { mutableIntStateOf(0) }
        var saved by remember { mutableIntStateOf(0) }
        var left by remember { mutableIntStateOf(0) }
        var skipped by remember { mutableIntStateOf(0) }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Ui.PullBar(onClose)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ui.Words(if (at < items.size) "Check " + (at + 1) + " of " + items.size else "All checked", 20, bold = true, modifier = Modifier.weight(1f))
                    KueSettings.CloseButton(Ui.look, onClick = onClose)
                }
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
                } else key(at) {
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
        var sure by remember { mutableStateOf(false) }
        var byHand by remember { mutableStateOf(false) }
        // After a save: what is in the file now, read back from the Drive, to check before moving on (Kevin, 27 November
        // 2026: "needs to be a final check that all is ok before moving on").
        var savedTimes by remember { mutableIntStateOf(0) }
        var after by remember { mutableStateOf<Tagger.All?>(null) }
        var afterProblem by remember { mutableStateOf("") }
        LaunchedEffect(savedTimes) {
            if (savedTimes == 0) return@LaunchedEffect
            after = null
            afterProblem = ""
            withContext(Dispatchers.IO) { runCatching { Fixer.readFile(context.applicationContext, t) } }
                .onSuccess { after = it }.onFailure { afterProblem = "The file couldn't be read back: " + (it.message ?: it.javaClass.simpleName) }
        }

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
            // Saved in there: show what is in the file now, to check; otherwise it stays here to decide.
            if (item.songs.all { s -> Scanner.data.tracks.firstOrNull { it.id == s.id }?.settled == true }) savedTimes++
        }
        if (sure) Confirm(
            "It's right as it is?",
            "Nothing is changed in the file, and KueSounds won't ask about it again. You can still change it any time from Song info.",
            "Yes, it's right", onYes = { sure = false; Fixer.leaveAsItIs(item.songs); onDone("left") }, onNo = { sure = false },
        )

        if (savedTimes > 0) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Ui.Words("Saved. This is in the file now:", 17, bold = true)
                val got = after
                when {
                    afterProblem.isNotEmpty() -> Ui.Words(afterProblem, 14, K.Accent)
                    got == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                        Ui.Words("Reading the file back…", 15, K.Accent)
                    }
                    else -> {
                        // The picture as it is inside the file itself, not the one found online.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(K.Panel).border(2.dp, K.Accent, RoundedCornerShape(16.dp))) {
                                if (got.picture != null) AsyncImage(model = got.picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                else Ui.Words("No picture in the file", 14, K.Soft, centre = true, modifier = Modifier.align(Alignment.Center))
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))) { Hear(t) }
                            }
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).border(1.dp, K.Edge, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((k, label) in Tagger.named) {
                                val v = got.known[k].orEmpty()
                                if (v.isBlank() || k == FieldKey.LYRICS) continue
                                Row {
                                    Ui.Words(label, 14, K.Soft, modifier = Modifier.width(124.dp))
                                    Ui.Words(v, 15, modifier = Modifier.weight(1f))
                                }
                            }
                            if (item.songs.size > 1) Ui.Words("Shown: its first song. All " + item.songs.size + " were saved.", 13, K.Soft)
                        }
                    }
                }
                Ui.Button("All good, next", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth()) { onDone("saved") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Ui.Words("Change it again", 15, K.Accent, bold = true, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { byHand = true }.padding(10.dp))
                }
            }
            return
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = item.album ?: now?.get(FieldKey.TITLE).orEmpty().ifBlank { t.title }
            val who = now?.get(FieldKey.ARTIST).orEmpty().ifBlank { t.artist }
            Ui.Words(title + " · " + who, 17, bold = true)
            Ui.Words(item.why, 14, K.Accent)

            val a = answer
            if (a == null) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel)) { Hear(t) }
            when {
                finding.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Ui.Words(finding, 15, K.Accent, modifier = Modifier.weight(1f))
                }
                a != null -> {
                    Ui.Words("KueSounds suggests, from " + a.from + ":", 15, K.Soft)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(K.Panel).border(2.dp, K.Accent, RoundedCornerShape(16.dp))) {
                            AsyncImage(model = a.big.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            // Hear it before deciding (Kevin, 27 November 2026): play, pause and a slider over the cover.
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))) { Hear(t) }
                        }
                    }
                    val rows = changesOf(item, a, now.orEmpty())
                    if (rows.isEmpty()) Ui.Words("Just the cover changes.", 14, K.Soft)
                    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).border(1.dp, K.Edge, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label, v) in rows) {
                            val old = now?.get(k).orEmpty()
                            Ui.Words(label, 13, K.Soft)
                            if (old.isNotBlank() && old != v) Ui.Words(old, 14, K.Soft)
                            Ui.Words(v, 16, if (old != v) K.Accent else K.Ink, bold = old != v)
                        }
                    }
                }
                nothing -> Ui.Words("Nothing knows it, by its sound or by its name. Change it yourself, or keep it as it is.", 15)
            }
            if (problem.isNotEmpty()) Ui.Words(problem, 14, K.Accent)

            if (finding.isEmpty()) {
                if (saving) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Ui.Words("Saving into " + (if (item.songs.size == 1) "the file" else "its " + item.songs.size + " files") + "…", 15, K.Accent)
                } else {
                    if (a != null) Ui.Button("Accept and save", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth()) {
                        saving = true
                        problem = ""
                        val go = {
                            scope.launch {
                                val set = changesOf(item, a, now.orEmpty()).filter { (k, _, v) -> now?.get(k).orEmpty() != v }.associate { (k, _, v) -> k to v }
                                val cover = withContext(Dispatchers.IO) { a.big.takeIf { it.isNotBlank() }?.let { Covers.download(it) }?.let { FixByHandUi.asJpeg(it) } }
                                val said = withContext(Dispatchers.IO) { Fixer.saveByHand(context.applicationContext, item.songs, set, emptySet(), cover) }
                                saving = false
                                if (said.startsWith("Saved into the file") || said.startsWith("Saved into all")) savedTimes++
                                else problem = said
                            }
                            Unit
                        }
                        if (Google.canEdit) go() else Fixer.askPermission { go() }
                    }
                    Ui.Button("Change it myself", main = a == null, icon = KueSettings.Icons.EDIT, modifier = Modifier.fillMaxWidth()) { byHand = true }
                    // Not the same thing (Kevin, 27 November 2026, "the same thing?"): one stops it being asked about, the other
                    // brings it back next time. Plain words, no block buttons.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { sure = true }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Ui.Words("It's right as it is", 15, K.Accent, bold = true, centre = true)
                            Ui.Words("Don't ask again", 12, K.Soft, centre = true)
                        }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onDone("skipped") }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Ui.Words("Not now", 15, K.Accent, bold = true, centre = true)
                            Ui.Words("Ask me next time", 12, K.Soft, centre = true)
                        }
                    }
                }
            }
        }
    }

    /**
     * Play, pause and a slider for one song, as thin as the player's own (no big buttons). Plays through the normal
     * player; moving on to the next one stops it.
     */
    @Composable
    private fun Hear(t: Track) {
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
            add(FieldKey.GENRE, "Kind of music", a.genre)
        } else if (item.album != null && !a.coverOnly) {
            add(FieldKey.YEAR, "Year", a.year)
            if (now[FieldKey.GENRE].isNullOrBlank()) add(FieldKey.GENRE, "Kind of music", a.genre)
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
    fun SongList(title: String, songs: List<Track>, empty: String, onClose: () -> Unit) {
        var open by remember { mutableStateOf<Track?>(null) }
        open?.let { t -> Ui.SongInfo(t) { open = null } }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Ui.PullBar(onClose)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ui.Words(title, 19, bold = true, modifier = Modifier.weight(1f))
                    KueSettings.CloseButton(Ui.look, onClick = onClose)
                }
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
                            KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Soft, 2.2f)
                        }
                    }
                }
            }
        }
    }
}
