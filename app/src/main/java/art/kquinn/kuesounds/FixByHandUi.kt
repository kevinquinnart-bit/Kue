package art.kquinn.kuesounds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
 * Fix it by hand (Kevin, 27 November 2026): for every song or album the fixing couldn't finish, the same page, in the
 * order he'd do it himself. 1. Listen: Shazam is asked at up to four places in the song. 2. Look it up: Apple and
 * Deezer searched in the app (name, album, year, cover in one tap), plus the web, web pictures and Spotify. 3. Every
 * detail in the file, each one changeable, and anything else in it that can be taken out. Then Save into the file.
 * For an album the details shared by its songs (album, album artist, year, kind of music, cover) go into every song.
 */
object FixByHandUi {
    private val albumKeys = listOf(FieldKey.ALBUM, FieldKey.ALBUM_ARTIST, FieldKey.YEAR, FieldKey.GENRE, FieldKey.RECORD_LABEL)
    private val alwaysShown = listOf(FieldKey.TITLE, FieldKey.ARTIST, FieldKey.ALBUM, FieldKey.ALBUM_ARTIST, FieldKey.YEAR, FieldKey.TRACK, FieldKey.DISC_NO, FieldKey.GENRE)

    @Composable
    fun Page(songs: List<Track>, albumTitle: String?, why: String, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val first = songs.first()
        val isAlbum = albumTitle != null
        var opened by remember { mutableStateOf<Track?>(null) }
        opened?.let { t -> Page(listOf(t), null, Scanner.data.fixProblems[t.id].orEmpty()) { opened = null } }

        // What is in the file now (read from the Drive), and as changed here.
        var read by remember { mutableStateOf<Tagger.All?>(null) }
        var readProblem by remember { mutableStateOf("") }
        val was = remember { mutableStateMapOf<FieldKey, String>() }
        val now = remember { mutableStateMapOf<FieldKey, String>() }
        val removed = remember { mutableStateMapOf<String, Boolean>() }
        var cover by remember { mutableStateOf<ByteArray?>(null) }
        var everyDetail by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<FieldKey?>(null) }
        var busy by remember { mutableStateOf("") }
        var saying by remember { mutableStateOf("") }

        LaunchedEffect(first.id) {
            val got = withContext(Dispatchers.IO) { runCatching { Fixer.readFile(context.applicationContext, first) } }
            got.onSuccess { all ->
                read = all
                val keys = if (isAlbum) albumKeys else Tagger.named.map { it.first }
                for (k in keys) { was[k] = all.known[k].orEmpty(); now[k] = all.known[k].orEmpty() }
            }.onFailure { readProblem = "The file couldn't be read from your Drive: " + (it.message ?: it.javaClass.simpleName) }
        }

        // A find (by sound or by search) put into the table and as the cover; nothing is written until Save.
        val use: (Covers.Lookup) -> Unit = { f ->
            if (!isAlbum) {
                if (f.title.isNotBlank()) now[FieldKey.TITLE] = f.title
                if (f.artist.isNotBlank()) now[FieldKey.ARTIST] = f.artist
                if (f.trackNo > 0) now[FieldKey.TRACK] = f.trackNo.toString()
                if (f.discNo > 0) now[FieldKey.DISC_NO] = f.discNo.toString()
            }
            if (f.album.isNotBlank()) now[FieldKey.ALBUM] = f.album
            if (f.albumArtist.isNotBlank()) now[FieldKey.ALBUM_ARTIST] = f.albumArtist
            if (f.year.isNotBlank()) now[FieldKey.YEAR] = f.year
            if (f.genre.isNotBlank()) now[FieldKey.GENRE] = f.genre
            if (f.big.isNotBlank()) {
                busy = "Fetching its cover…"
                scope.launch {
                    val b = withContext(Dispatchers.IO) { Covers.download(f.big) }
                    busy = ""
                    if (b != null) cover = b
                    saying = "Put in below from " + f.from + ". Check it, then Save into the file."
                }
            } else saying = "Put in below. Check it, then Save into the file."
            Unit
        }

        val changes = now.filter { (k, v) -> v.trim() != was[k].orEmpty() }
        val changed = changes.isNotEmpty() || removed.any { it.value } || cover != null
        val save = {
            busy = if (isAlbum) "Saving into " + songs.size + " files…" else "Saving into the file…"
            saying = ""
            val go = {
                scope.launch {
                    saying = withContext(Dispatchers.IO) {
                        Fixer.saveByHand(context.applicationContext, songs, changes.mapValues { it.value.trim() }, removed.filter { it.value }.keys, cover)
                    }
                    // Only a full save counts: "Saved into 0 of 1 files" kept the changes on screen, Take out included (27 November 2026).
                    if (saying.startsWith("Saved into the file") || saying.startsWith("Saved into all")) {
                        changes.forEach { (k, v) -> was[k] = v.trim() }
                        removed.clear()
                    }
                    busy = ""
                }
                Unit
            }
            if (Google.canEdit) go() else Fixer.askPermission { go() }
        }

        Dialog(onDismissRequest = { if (busy.isEmpty()) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Ui.PullBar { if (busy.isEmpty()) onClose() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ui.RoundButton(Glyph.BACK, "Back") { if (busy.isEmpty()) onClose() }
                    Spacer(Modifier.width(12.dp))
                    Ui.Words("Fix it by hand", 20, bold = true, modifier = Modifier.weight(1f))
                }
                Ui.Words((albumTitle ?: first.title) + " · " + (if (isAlbum) first.albumArtist.ifBlank { first.artist } else first.artist), 15, bold = true)
                if (why.isNotBlank()) Ui.Words(why, 14, K.Accent)

                // The cover: as it is, or the one chosen here.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val shape = RoundedCornerShape(16.dp)
                    val m = Modifier.size(220.dp).clip(shape).background(K.Panel)
                    when {
                        cover != null -> AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = m.border(3.dp, K.Accent, shape))
                        else -> Ui.Cover(Scanner.artForTrack(first) ?: Scanner.singleArtFor(first), m, corner = 16.dp, glyphSize = 64.dp)
                    }
                }
                if (cover != null) Ui.Words("New cover, written in when you save.", 13, K.Accent, centre = true, modifier = Modifier.fillMaxWidth())

                // 1. Listen.
                Ui.Words("1. Listen to it", 17, bold = true)
                Ui.Button(
                    if (busy.startsWith("Listening") || busy == "Asking AcoustID") busy else "Name it by its sound (Shazam)",
                    main = false, icon = KueSettings.Icons.MIC, modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy.isNotEmpty()) return@Button
                    busy = "Listening…"
                    saying = ""
                    scope.launch {
                        val got = withContext(Dispatchers.IO) { runCatching { SoundMatch.identifyAnywhere(first) { busy = it } } }
                        busy = ""
                        val f = got.getOrNull()?.firstOrNull()
                        when {
                            f != null -> use(
                                Covers.Lookup(
                                    f.title, f.artist, f.albums.firstOrNull().orEmpty(), f.artist, f.year.take(4), 0, 0, f.genre,
                                    f.cover.orEmpty(), f.cover.orEmpty(), f.from,
                                ),
                            )
                            got.isFailure -> saying = got.exceptionOrNull()?.message ?: "Listening didn't work."
                            else -> saying = "Shazam didn't know it at any of the four places it listened. Look it up below."
                        }
                    }
                }

                // 2. Look it up.
                Ui.Words("2. Look it up", 17, bold = true)
                Search(
                    start = listOf(first.artist.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), if (isAlbum) albumTitle.orEmpty() else Library.withoutBrackets(first.title))
                        .filter { it.isNotBlank() }.joinToString(" "),
                    songs = !isAlbum, onUse = use, onPicture = { b -> cover = b; saying = "New cover chosen. Save into the file to keep it." },
                )

                // 3. Every detail in the file.
                Ui.Words(if (isAlbum) "3. The album's details, for all " + songs.size + " songs" else "3. Everything in the file", 17, bold = true)
                val all = read
                when {
                    readProblem.isNotEmpty() -> Ui.Words(readProblem, 14, K.Accent)
                    all == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                        Ui.Words("Reading the file…", 14, K.Accent)
                    }
                    else -> {
                        val keys = if (isAlbum) albumKeys
                        else Tagger.named.map { it.first }.filter { everyDetail || it in alwaysShown || was[it].orEmpty().isNotBlank() || now[it].orEmpty().isNotBlank() }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).border(1.dp, K.Edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                            for (k in keys) {
                                val label = Tagger.named.first { it.first == k }.second
                                val v = now[k].orEmpty()
                                if (editing == k) {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Ui.Words(label, 13, K.Soft)
                                        Field(v) { now[k] = it }
                                    }
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Change $label") { editing = k }.padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Ui.Words(label, 14, K.Soft, modifier = Modifier.width(124.dp))
                                        Ui.Words(
                                            v.ifBlank { "Not set" }.let { if (k == FieldKey.LYRICS && it.length > 120) it.take(120) + " (and more)" else it }, 15,
                                            if (v.trim() != was[k].orEmpty()) K.Accent else if (v.isBlank()) K.Soft else K.Ink, modifier = Modifier.weight(1f),
                                        )
                                        KueSettings.Icon(KueSettings.Icons.EDIT, 16.dp, K.Soft, 2f)
                                    }
                                }
                            }
                        }
                        if (!isAlbum) Ui.Button(if (everyDetail) "Show only the details that are set" else "Show every detail a file can hold", main = false, modifier = Modifier.fillMaxWidth()) { everyDetail = !everyDetail }
                        // Anything else the file holds, by its own code (encoder, ratings, old tags): each can be taken out.
                        if (!isAlbum && all.other.isNotEmpty()) {
                            Ui.Words("Also in the file", 15, bold = true)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).border(1.dp, K.Edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                                for ((id, text) in all.other.distinctBy { it.first }) {
                                    val out = removed[id] == true
                                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Ui.Words(plainName(id), 13, K.Soft)
                                            Ui.Words(if (out) "Taken out when you save" else text.take(200), 14, if (out) K.Accent else K.Ink)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Ui.Button(if (out) "Keep" else "Take out", main = false, modifier = Modifier.height(44.dp)) { removed[id] = !out }
                                    }
                                }
                            }
                        }
                    }
                }

                if (busy.isNotEmpty() && !busy.startsWith("Listening") && busy != "Asking AcoustID") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                    Ui.Words(busy, 14, K.Accent, modifier = Modifier.weight(1f))
                }
                if (saying.isNotEmpty()) Ui.Words(saying, 14, K.Accent)
                if (changed && busy.isEmpty()) {
                    Ui.Button(if (isAlbum) "Save into all " + songs.size + " files" else "Save into the file", icon = KueSettings.Icons.TICK, modifier = Modifier.fillMaxWidth()) { save() }
                    Ui.Button("Undo changes", main = false, modifier = Modifier.fillMaxWidth()) {
                        was.forEach { (k, v) -> now[k] = v }
                        removed.clear()
                        cover = null
                        editing = null
                        saying = ""
                    }
                }

                if (isAlbum) {
                    Ui.Words("Its songs: tap one to fix it on its own", 17, bold = true)
                    for (t in songs.sortedWith(compareBy({ it.discNo }, { it.trackNo }, { it.title }))) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).clickable { opened = t }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Ui.Words(t.title, 15, bold = true)
                                Ui.Words(t.artist + " · " + t.fileName, 13, K.Soft)
                            }
                            KueSettings.Icon(KueSettings.Icons.CHEVRON, 16.dp, K.Soft, 2.2f)
                        }
                    }
                }
                Ui.Button("Play it", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(songs, 0) }
            }
        }
    }

    /** What a file's own code for a detail means, in plain words (Kevin, 27 November 2026: "don't know what this is"). */
    fun plainName(id: String): String = when (id.uppercase().substringBefore(':')) {
        "TSSE", "ENCODER", "©TOO", "ENCODING" -> "The program that made the file"
        "TENC", "ENCODED-BY", "ENCODEDBY" -> "Who made the file"
        "TXXX" -> "Extra note" + id.substringAfter(':', "").let { if (it.isNotBlank()) " (" + it + ")" else "" }
        "PRIV" -> "Private data from another program"
        "TCOP", "COPYRIGHT", "CPRT" -> "Copyright"
        "WXXX", "WOAR", "WOAF", "WCOM", "URL" -> "Web address"
        "TMED" -> "Where it came from (CD, record)"
        "TFLT" -> "Kind of sound file"
        "POPM", "RATING" -> "Star rating from another player"
        "PCNT" -> "Play count from another player"
        "TPUB", "ORGANIZATION", "LABEL" -> "Publisher"
        "TSRC", "ISRC" -> "ISRC code"
        "MCDI" -> "CD it was copied from"
        "UFID" -> "Code from a music catalogue"
        "TDRC", "TYER", "DATE" -> "Date"
        "TDAT", "TIME", "TRDA" -> "Date recorded"
        "TOAL" -> "First album"
        "TOPE" -> "First artist"
        "TSOP", "TSOA", "TSOT", "TSO2", "ARTISTSORT", "ALBUMSORT", "TITLESORT" -> "Name for sorting"
        "ITUNNORM", "COMM" -> "Comment"
        "----" -> "Note from iTunes"
        else -> "Other detail"
    } + " · " + id

    /** The search: Apple and Deezer in the app, each find with its cover; then the web, web pictures, Spotify and the phone. */
    @Composable
    private fun Search(start: String, songs: Boolean, onUse: (Covers.Lookup) -> Unit, onPicture: (ByteArray) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var words by remember { mutableStateOf(start) }
        var found by remember { mutableStateOf<List<Covers.Lookup>?>(null) }
        var looking by remember { mutableStateOf(false) }
        var forSongs by remember { mutableStateOf(songs) }
        val search = {
            looking = true
            found = null
            scope.launch {
                found = withContext(Dispatchers.IO) { Covers.lookUp(words, forSongs) }
                looking = false
            }
            Unit
        }
        val open = { url: String ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Unit
        }
        val fromPhone = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }?.let(onPicture)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(words) { words = it } }
            Ui.Button("Search", modifier = Modifier.height(52.dp), onClick = search)
        }
        KueSettings.Choices("Look for", listOf("Songs", "Albums"), if (forSongs) 0 else 1, Ui.look) { forSongs = it == 0; if (words.isNotBlank()) search() }
        when {
            looking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                Ui.Words("Looking in Apple Music and Deezer…", 14, K.Accent)
            }
            found?.isEmpty() == true -> Ui.Words("Nothing found for that. Change the words and search again, or try the web below.", 14, K.Soft)
            else -> found?.take(20)?.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).border(1.dp, K.Edge, RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Use this") { onUse(f) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(model = f.small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(K.Panel))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Ui.Words(f.title.ifBlank { f.album }, 15, bold = true)
                        Ui.Words(f.artist, 13)
                        Ui.Words(listOf(if (f.title.isNotBlank()) f.album else "", f.year, f.from).filter { it.isNotBlank() }.joinToString(" · "), 12, K.Soft)
                    }
                    Spacer(Modifier.width(8.dp))
                    Ui.Words("Use", 14, K.Accent, bold = true)
                }
            }
        }
        val q = android.net.Uri.encode(words)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ui.Button("The web", main = false, icon = KueSettings.Icons.WEB, modifier = Modifier.weight(1f)) { open("https://www.google.com/search?q=$q") }
            Ui.Button("Web pictures", main = false, icon = KueSettings.Icons.DISC, modifier = Modifier.weight(1f)) { open("https://www.google.com/search?tbm=isch&q=$q") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ui.Button("Spotify", main = false, icon = KueSettings.Icons.SEARCH, modifier = Modifier.weight(1f)) { SpotifyLink.openSearch(context, words) }
            Ui.Button("Picture on phone", main = false, icon = KueSettings.Icons.PHONE, modifier = Modifier.weight(1f)) {
                fromPhone.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        Ui.Words("A picture saved from the web goes on your phone; then tap Picture on phone to use it.", 13, K.Soft)
    }

    @Composable
    private fun Field(value: String, onChange: (String) -> Unit) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Page).border(1.dp, K.Edge, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = K.Ink, fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = K.Poppins),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(K.Accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
