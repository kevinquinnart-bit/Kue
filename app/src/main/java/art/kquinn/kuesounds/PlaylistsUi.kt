package art.kquinn.kuesounds

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The playlist screens: the Playlists tab, a playlist, and the box for adding songs to one. */
object ListUi {
    /** What a playlist brought in from another player left out, for its page to say. Kept until KueSounds closes. */
    private val leftOut = HashMap<String, List<String>>()

    @Composable
    private fun Field(hint: String, value: String, onChange: (String) -> Unit) {
        Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Ui.Words(hint, 16, K.Soft)
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = K.Ink, fontSize = 16.sp, fontFamily = K.Poppins),
                cursorBrush = SolidColor(K.Accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The Playlists tab

    @Composable
    fun Home(go: (Screen) -> Unit, layout: Views.Layout = Views.Layout.LIST) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val lists = remember(Sync.entries) { Playlists.all() }
        var naming by remember { mutableStateOf<String?>(null) }
        var note by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val bringIn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            note = ""
            scope.launch {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull().orEmpty()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                        Playlists.bringIn(name, text, Scanner.data.tracks)
                    }.getOrNull()
                }
                busy = false
                if (result == null) note = "No songs were found in that file. It needs to be a playlist file: M3U, M3U8, PLS, XSPF, WPL, or a CSV with song and artist columns."
                else {
                    leftOut[result.playlist.id] = result.missing
                    go(Screen.PlaylistPage(result.playlist.id))
                }
            }
        }
        // Shown the chosen way (Kevin, 25 November 2026): a list, a list with small pictures, or large covers. A
        // playlist's picture is the cover of its first song that has one.
        val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan) = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
        val byId = remember(Scanner.data) { Scanner.data.tracks.associateBy { it.id } }
        val pictureOf = { pl: Playlists.Playlist -> pl.ids.asSequence().mapNotNull { byId[it] }.firstNotNullOfOrNull { Scanner.artForTrack(it) ?: Scanner.singleArtFor(it) } }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = if (layout == Views.Layout.COVERS) androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp) else androidx.compose.foundation.lazy.grid.GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = full) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ui.Button("New playlist", modifier = Modifier.weight(1f)) { naming = "" }
                    Ui.Button(if (busy) "Reading…" else "Bring one in", main = false, modifier = Modifier.weight(1f)) { bringIn.launch(arrayOf("*/*")) }
                }
            }
            item(span = full) {
                Ui.Words(
                    "Bring one in from another player's playlist file (M3U, PLS, XSPF, WPL, or CSV, as Spotify's exports are). " +
                        "Each song is matched to the same song in your music.",
                    14, K.Soft, lines = 4,
                )
            }
            if (note.isNotEmpty()) item(span = full) { Ui.Words(note, 15, K.Accent, lines = 4) }
            if (lists.isEmpty()) item(span = full) { Ui.Words("No playlists yet. Hold a song to add it to one, or tap + while one is playing.", 16, K.Soft, lines = 3) }
            items(lists.size, key = { lists[it].id }) { n ->
                val p = lists[n]
                val songs = p.ids.size.toString() + if (p.ids.size == 1) " song" else " songs"
                if (layout == Views.Layout.COVERS) {
                    Column(Modifier.clickable { go(Screen.PlaylistPage(p.id)) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                            val pic = pictureOf(p)
                            if (pic != null) coil3.compose.AsyncImage(model = pic, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else KueSettings.Icon(KueSettings.Icons.PLAYLIST, 40.dp, K.Soft, 2f)
                        }
                        Ui.Words(p.name, 15, bold = true)
                        Ui.Words(songs, 13, K.Soft)
                    }
                } else Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { go(Screen.PlaylistPage(p.id)) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pic = pictureOf(p)
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(K.Raised), contentAlignment = Alignment.Center) {
                        if (pic != null) coil3.compose.AsyncImage(model = pic, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Sign(Glyph.NOTE, 22.dp, K.Soft)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Ui.Words(p.name, 16, bold = true)
                        Ui.Words(songs, 13, K.Soft)
                    }
                }
            }
            item(span = full) { Spacer(Modifier.height(20.dp)) }
        }
        naming?.let { typed ->
            NameBox("New playlist", typed, onChange = { naming = it }, onCancel = { naming = null }) {
                val p = Playlists.create(typed)
                naming = null
                go(Screen.PlaylistPage(p.id))
            }
        }
    }

    @Composable
    private fun NameBox(title: String, value: String, onChange: (String) -> Unit, onCancel: () -> Unit, onDone: () -> Unit) {
        Dialog(onDismissRequest = onCancel) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Ui.Words(title, 19, bold = true)
                Field("Its name", value, onChange)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ui.Button("Cancel", main = false, modifier = Modifier.weight(1f), onClick = onCancel)
                    Ui.Button("Done", modifier = Modifier.weight(1f), onClick = onDone)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A playlist

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun Page(id: String, back: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val p = remember(Sync.entries, id) { Playlists.get(id) }
        if (p == null) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                Ui.Words("This playlist has been deleted.", 17, K.Soft)
            }
            return
        }
        val byId = remember(Scanner.data.tracks) { Scanner.data.tracks.associateBy { it.id } }
        val songs = remember(p, byId) { p.ids.mapNotNull { byId[it] } }
        var renaming by remember { mutableStateOf<String?>(null) }
        var sure by remember { mutableStateOf(false) }
        var holding by remember { mutableStateOf(-1) }
        var saved by remember { mutableStateOf("") }
        val missing = leftOut[id].orEmpty()
        val sendOut = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Playlists.m3u8(p, byId).toByteArray()) } }.isSuccess }
                saved = if (ok) "Saved as a playlist file." else "The file couldn't be written."
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                    Ui.Words(p.name, 24, bold = true, lines = 2, modifier = Modifier.clickable { renaming = p.name })
                    Ui.Words(songs.size.toString() + (if (songs.size == 1) " song" else " songs") + " · tap the name to rename · hold a song to move or remove it", 13, K.Soft, lines = 2)
                    if (songs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Ui.Button("Play", glyph = Glyph.PLAY, modifier = Modifier.weight(1f)) { Playing.play(songs, 0) }
                        Ui.Button("Shuffle", main = false, glyph = Glyph.SHUFFLE, modifier = Modifier.weight(1f)) { Playing.play(songs, 0, shuffled = true) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Ui.Button("Save as a file", main = false, modifier = Modifier.weight(1f)) { sendOut.launch(p.name + ".m3u8") }
                        Ui.Button(if (sure) "Yes, delete it" else "Delete", main = false, modifier = Modifier.weight(1f)) {
                            if (sure) { Playlists.delete(id); back() } else sure = true
                        }
                    }
                    if (saved.isNotEmpty()) Ui.Words(saved, 14, K.Accent)
                    if (missing.isNotEmpty()) {
                        Ui.Words(missing.size.toString() + " songs in the file aren't in your music, so were left out:", 15, K.Accent, lines = 2)
                        Ui.Words(missing.take(40).joinToString("\n") + if (missing.size > 40) "\n…and " + (missing.size - 40) + " more" else "", 14, K.Soft, lines = 45)
                    }
                }
            }
            itemsIndexed(songs, key = { i, t -> "$i:" + t.id }) { i, t ->
                val on = t.id == Playing.currentId
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(if (on) K.Panel else Color.Transparent)
                        .combinedClickable(onClick = { Playing.play(songs, i) }, onLongClick = { holding = i }).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ui.Words((i + 1).toString(), 15, if (on) K.Accent else K.Soft, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Ui.Words(t.title, 16, if (on) K.Accent else K.Ink, bold = on)
                        Ui.Words(t.artist, 13, K.Soft)
                    }
                }
            }
        }
        if (holding >= 0) {
            val at = holding
            Dialog(onDismissRequest = { holding = -1 }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ui.Words(songs.getOrNull(at)?.title.orEmpty(), 18, bold = true, lines = 2)
                    if (at > 0) Ui.Button("Move up", main = false, modifier = Modifier.fillMaxWidth()) { Playlists.move(id, at, at - 1); holding = at - 1 }
                    if (at < songs.size - 1) Ui.Button("Move down", main = false, modifier = Modifier.fillMaxWidth()) { Playlists.move(id, at, at + 1); holding = at + 1 }
                    if (at > 0) Ui.Button("Move to the top", main = false, modifier = Modifier.fillMaxWidth()) { Playlists.move(id, at, 0); holding = -1 }
                    Ui.Button("Take out of this playlist", main = false, modifier = Modifier.fillMaxWidth()) { Playlists.removeAt(id, at); holding = -1 }
                    Ui.Button("Done", modifier = Modifier.fillMaxWidth()) { holding = -1 }
                }
            }
        }
        renaming?.let { typed ->
            NameBox("Rename", typed, onChange = { renaming = it }, onCancel = { renaming = null }) {
                Playlists.rename(id, typed)
                renaming = null
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Adding songs to a playlist

    /** The box for adding [ids] (one song, or an album's) to a playlist, or to a new one. */
    @Composable
    fun AddBox(label: String, ids: List<String>, onAdded: (String) -> Unit = {}, onClose: () -> Unit) {
        val lists = remember(Sync.entries) { Playlists.all() }
        var naming by remember { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = onClose) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Page).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Ui.Words("Add $label to a playlist", 18, bold = true, lines = 2)
                val typed = naming
                if (typed != null) {
                    Field("The new playlist's name", typed) { naming = it }
                    Ui.Button("Make it and add", modifier = Modifier.fillMaxWidth()) { Playlists.create(typed, ids); onAdded(typed); onClose() }
                } else {
                    Ui.Button("New playlist", modifier = Modifier.fillMaxWidth()) { naming = "" }
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(lists, key = { it.id }) { p ->
                            Ui.Button(p.name + " (" + p.ids.size + ")", main = false, modifier = Modifier.fillMaxWidth()) { Playlists.add(p.id, ids); onAdded(p.name); onClose() }
                        }
                    }
                }
                Ui.Button("Cancel", main = false, modifier = Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }
}
