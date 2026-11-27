package art.kquinn.kuesounds

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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

    /** True while the page is open: a picture or link shared to KueSounds from the browser comes here. */
    private var openCount by mutableStateOf(0)
    val isOpen: Boolean get() = openCount > 0

    /** Any page that takes a picture shared from the browser (Fix it by hand, Choose the cover) says it is open. */
    fun opened() { openCount++ }
    fun closed() { openCount-- }
    var sharedPicture by mutableStateOf<android.net.Uri?>(null)
    var sharedLink by mutableStateOf<String?>(null)

    /** Any picture as a JPEG no bigger than 1200 across, which every kind of music file takes. Off the main thread. */
    fun asJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1200) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Composable
    fun Page(songs: List<Track>, albumTitle: String?, why: String, left: Int = 0, onNext: (() -> Unit)? = null, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val first = songs.first()
        val isAlbum = albumTitle != null
        var opened by remember { mutableStateOf<Track?>(null) }
        opened?.let { t -> Page(listOf(t), null, Scanner.data.fixProblems[t.id].orEmpty()) { opened = null } }

        // What is in the file now (read from the Drive once), and as changed here.
        var read by remember { mutableStateOf<Tagger.All?>(null) }
        var readProblem by remember { mutableStateOf("") }
        val was = remember { mutableStateMapOf<FieldKey, String>() }
        val now = remember { mutableStateMapOf<FieldKey, String>() }
        val removed = remember { mutableStateMapOf<String, Boolean>() }
        var cover by remember { mutableStateOf<ByteArray?>(null) }
        var everyDetail by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<FieldKey?>(null) }
        var saving by remember { mutableStateOf(false) }
        var saying by remember { mutableStateOf("") }
        var fixed by remember { mutableStateOf(false) }
        var heard by remember { mutableStateOf<Covers.Lookup?>(null) }
        var listening by remember { mutableStateOf("") }
        var heardSaying by remember { mutableStateOf("") }
        var pictureMenu by remember { mutableStateOf(false) }
        var pictureSaying by remember { mutableStateOf("") }
        val scroll = rememberScrollState()

        var pageOpen by remember { mutableStateOf(true) }
        androidx.compose.runtime.DisposableEffect(first.id) {
            openCount++
            onDispose {
                pageOpen = false
                openCount--
                Fixer.dropCopy(context.applicationContext, first)
            }
        }
        LaunchedEffect(first.id) {
            val got = withContext(Dispatchers.IO) { runCatching { Fixer.readFile(context.applicationContext, first) } }
            got.onSuccess { all ->
                read = all
                val keys = if (isAlbum) albumKeys else Tagger.named.map { it.first }
                for (k in keys) { was[k] = all.known[k].orEmpty(); now[k] = all.known[k].orEmpty() }
            }.onFailure { readProblem = "The file couldn't be read from your Drive: " + (it.message ?: it.javaClass.simpleName) }
        }

        // A picture chosen any way: made a JPEG, shown at the top, written in when saved.
        val takePicture = { b: ByteArray?, from: String ->
            scope.launch {
                val jpeg = withContext(Dispatchers.Default) { b?.let { asJpeg(it) } }
                if (jpeg != null) { cover = jpeg; fixed = false; pictureSaying = "New cover from $from. It goes into the file when you save."; scope.launch { scroll.animateScrollTo(0) } }
                else pictureSaying = "That picture couldn't be used. Try another."
            }
            Unit
        }
        // Shared from the browser while this page is open (Share on a picture or a page in Chrome, then KueSounds).
        LaunchedEffect(sharedPicture) {
            val uri = sharedPicture ?: return@LaunchedEffect
            sharedPicture = null
            takePicture(withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }, "what you shared")
        }
        LaunchedEffect(sharedLink) {
            val link = sharedLink ?: return@LaunchedEffect
            sharedLink = null
            pictureSaying = "Fetching the picture from the link…"
            takePicture(withContext(Dispatchers.IO) { Covers.pictureFromLink(link) }, "the link you shared")
        }

        // A find (by sound or by search) put into the table and as the cover; nothing is written until Save.
        val use: (Covers.Lookup) -> Unit = { f ->
            if (!f.coverOnly) {
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
                fixed = false
            }
            if (f.big.isNotBlank()) {
                pictureSaying = "Fetching its cover…"
                scope.launch { takePicture(withContext(Dispatchers.IO) { Covers.download(f.big) }, f.from) }
            }
            Unit
        }

        val changes = now.filter { (k, v) -> v.trim() != was[k].orEmpty() }
        val changed = changes.isNotEmpty() || removed.any { it.value } || cover != null
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val save = {
            keyboard?.hide()
            editing = null
            saving = true
            saying = ""
            val go = {
                // Saved in the app's own scope, so closing the page never stops a save halfway (Kevin, 27 November 2026:
                // "Saving into the file" never cleared and the page couldn't be closed while it showed).
                Fixer.saves.launch {
                    val said = withContext(Dispatchers.IO) {
                        Fixer.saveByHand(context.applicationContext, songs, changes.mapValues { it.value.trim() }, removed.filter { it.value }.keys, cover)
                    }
                    // Only a full save counts: "Saved into 0 of 1 files" keeps the changes on screen.
                    if (said.startsWith("Saved into the file") || said.startsWith("Saved into all")) {
                        changes.forEach { (k, v) -> was[k] = java.text.Normalizer.normalize(v.trim(), java.text.Normalizer.Form.NFC); now[k] = was[k].orEmpty() }
                        removed.clear()
                        cover = null
                        editing = null
                        fixed = true
                        runCatching { scroll.animateScrollTo(0) }
                    }
                    saying = said
                    saving = false
                    if (!pageOpen) android.widget.Toast.makeText(context.applicationContext, said, android.widget.Toast.LENGTH_LONG).show()
                }
                Unit
            }
            if (Google.canEdit) go() else Fixer.askPermission { go() }
        }

        // Closes at any time, saving or not: a save carries on and says when it's done (Kevin, 27 November 2026).
        val leave = {
            if (saving) android.widget.Toast.makeText(context.applicationContext, "Still saving. It finishes by itself.", android.widget.Toast.LENGTH_SHORT).show()
            onClose()
        }
        Dialog(onDismissRequest = leave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
          // The pull bar, back and close stay at the top while the page scrolls under them.
          Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(start = 18.dp, end = 18.dp, top = 8.dp)) {
                Ui.PullBar(leave)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Ui.RoundButton(Glyph.BACK, "Back", onClick = leave)
                    Spacer(Modifier.width(12.dp))
                    Ui.Words("Fix it by hand", 20, bold = true, modifier = Modifier.weight(1f))
                    KueSettings.CloseButton(Ui.look, onClick = leave)
                }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The names as they are now in the file, not as they were when the list was made.
                val title = if (isAlbum) was[FieldKey.ALBUM].orEmpty().ifBlank { albumTitle.orEmpty() } else was[FieldKey.TITLE].orEmpty().ifBlank { first.title }
                val who = if (isAlbum) was[FieldKey.ALBUM_ARTIST].orEmpty().ifBlank { first.albumArtist.ifBlank { first.artist } } else was[FieldKey.ARTIST].orEmpty().ifBlank { first.artist }
                Ui.Words("$title · $who", 15, bold = true)

                // Done: says so plainly, and goes on to the next one.
                if (fixed) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Raised).border(2.dp, K.Accent, RoundedCornerShape(14.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KueSettings.Icon(KueSettings.Icons.TICK, 22.dp, K.Accent, 2.6f)
                            Spacer(Modifier.width(10.dp))
                            Ui.Words("Fixed. " + saying, 16, bold = true, modifier = Modifier.weight(1f))
                        }
                        if (onNext != null) Ui.Button("Next not fixed ($left left)", icon = KueSettings.Icons.CHEVRON, modifier = Modifier.fillMaxWidth()) { onNext() }
                        Ui.Button("Back to the list", main = onNext == null, modifier = Modifier.fillMaxWidth()) { onClose() }
                    }
                } else if (why.isNotBlank()) Ui.Words(why, 14, K.Accent)

                // The cover: as it is, or the one chosen here. Tap or hold it for the ways to change it (Kevin, 27 November 2026).
                if (pictureMenu) PictureMenu(
                    words = listOf(was[FieldKey.ARTIST].orEmpty().ifBlank { first.artist }, if (isAlbum) albumTitle.orEmpty() else was[FieldKey.ALBUM].orEmpty().ifBlank { first.album })
                        .filter { it.isNotBlank() && it != Library.UNKNOWN_ARTIST }.joinToString(" "),
                    onPicture = { b, from -> pictureMenu = false; takePicture(b, from) },
                ) { pictureMenu = false }
                Box(
                    Modifier.fillMaxWidth().combinedClickable(onClickLabel = "Change the cover", onLongClick = { pictureMenu = true }) { pictureMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    val shape = RoundedCornerShape(16.dp)
                    val m = Modifier.size(220.dp).clip(shape).background(K.Panel)
                    when {
                        cover != null -> AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = m.border(3.dp, K.Accent, shape))
                        else -> Ui.Cover(Scanner.artForTrack(Scanner.data.tracks.firstOrNull { it.id == first.id } ?: first) ?: Scanner.singleArtFor(first), m, corner = 16.dp, glyphSize = 64.dp)
                    }
                }
                Ui.Words(if (pictureSaying.isNotEmpty()) pictureSaying else "Tap the cover to change it.", 13, if (pictureSaying.isNotEmpty()) K.Accent else K.Soft, centre = true, modifier = Modifier.fillMaxWidth())
                if (changed && !saving) SaveRow(isAlbum, songs.size, onSave = { save() }) {
                    was.forEach { (k, v) -> now[k] = v }
                    removed.clear(); cover = null; editing = null; pictureSaying = ""
                }
                if (saving) Busy(if (isAlbum) "Saving into " + songs.size + " files…" else "Saving into the file…")
                if (saying.isNotEmpty() && !fixed) Ui.Words(saying, 14, K.Accent)

                // 1. Listen.
                Ui.Words("1. Listen to it", 17, bold = true)
                Line(if (listening.isNotEmpty()) listening else "Name it by its sound (Shazam)", KueSettings.Icons.MIC) {
                    if (listening.isNotEmpty()) return@Line
                    listening = "Listening…"
                    heard = null
                    heardSaying = ""
                    scope.launch {
                        val got = withContext(Dispatchers.IO) { runCatching { SoundMatch.identifyAnywhere(first) { listening = it } } }
                        listening = ""
                        val f = got.getOrNull()?.firstOrNull()
                        when {
                            f != null -> heard = Covers.Lookup(f.title, f.artist, f.albums.firstOrNull().orEmpty(), f.artist, f.year.take(4), 0, 0, f.genre, f.cover.orEmpty(), f.cover.orEmpty(), f.from)
                            got.isFailure -> heardSaying = "Listening didn't work: " + (got.exceptionOrNull()?.message ?: "no answer")
                            else -> heardSaying = "Shazam didn't know it at any of the four places it listened. Look it up below."
                        }
                    }
                }
                if (listening.isNotEmpty()) Busy(listening)
                heard?.let { f -> Found(f, "Shazam says") { use(f); heard = null; pictureSaying = "Put in from Shazam. Check below, then Save." } }
                if (heardSaying.isNotEmpty()) Ui.Words(heardSaying, 14, K.Accent)

                // 2. Look it up.
                Ui.Words("2. Look it up", 17, bold = true)
                Search(
                    start = listOf(was[FieldKey.ARTIST].orEmpty().ifBlank { first.artist }.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), if (isAlbum) albumTitle.orEmpty() else Library.withoutBrackets(first.title))
                        .filter { it.isNotBlank() }.joinToString(" "),
                    songs = !isAlbum, onUse = { f -> use(f); pictureSaying = if (f.coverOnly) "Fetching the picture…" else "Put in from " + f.from + ". Check below, then Save." },
                    onPicture = takePicture,
                )

                // 3. Every detail in the file.
                Ui.Words(if (isAlbum) "3. The album's details, for all " + songs.size + " songs" else "3. Everything in the file", 17, bold = true)
                val all = read
                when {
                    readProblem.isNotEmpty() -> Ui.Words(readProblem, 14, K.Accent)
                    all == null -> Busy("Reading the file…")
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
                                        Field(v) { now[k] = it; fixed = false }
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
                        if (!isAlbum) Line(if (everyDetail) "Show only the details that are set" else "Show every detail a file can hold", KueSettings.Icons.CHEVRON) { everyDetail = !everyDetail }
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
                                        Ui.Button(if (out) "Keep" else "Take out", main = false, modifier = Modifier.height(44.dp)) { removed[id] = !out; fixed = false }
                                    }
                                }
                            }
                        }
                    }
                }
                if (changed && !saving) SaveRow(isAlbum, songs.size, onSave = { save() }) {
                    was.forEach { (k, v) -> now[k] = v }
                    removed.clear(); cover = null; editing = null; pictureSaying = ""
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
    }

    /**
     * The ways to change the cover, in a pop-up: pictures found for it (shops and Wikipedia, tap one), a picture file on
     * the phone, a link, or a picture search on the web.
     */
    @Composable
    private fun PictureMenu(words: String, onPicture: (ByteArray?, String) -> Unit, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var found by remember { mutableStateOf<List<Covers.Lookup>?>(null) }
        var fetching by remember { mutableStateOf(false) }
        LaunchedEffect(words) {
            found = withContext(Dispatchers.IO) {
                runCatching { (Covers.lookUp(words, false) + Covers.lookUp(words, true)).filter { it.big.isNotBlank() }.distinctBy { it.big }.take(24) }.getOrDefault(emptyList())
            }
        }
        val fromFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch { onPicture(withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }, "the file") }
        }
        Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Ui.PullBar(onClose)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ui.Words("Change the cover", 19, bold = true, modifier = Modifier.weight(1f))
                    KueSettings.CloseButton(Ui.look, onClick = onClose)
                }
                Line("Use a picture file on your phone", KueSettings.Icons.PHONE) { fromFile.launch(arrayOf("image/*")) }
                Line("Use a picture from a link you copied", KueSettings.Icons.OPEN) {
                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    if (!clip.contains("http")) android.widget.Toast.makeText(context, "Copy the link to the picture or its page first.", android.widget.Toast.LENGTH_LONG).show()
                    else { fetching = true; scope.launch { val b = withContext(Dispatchers.IO) { Covers.pictureFromLink(clip) }; fetching = false; onPicture(b, "the link") } }
                }
                Line("Find pictures on the web", KueSettings.Icons.WEB) {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?tbm=isch&q=" + android.net.Uri.encode(words + " cover"))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                if (fetching) Busy("Fetching the picture…")
                Ui.Words("Or tap one found for it:", 15, bold = true)
                val list = found
                when {
                    list == null -> Busy("Looking in Apple Music, Deezer and Wikipedia…")
                    list.isEmpty() -> Ui.Words("None found. Use a file, a link or the web above.", 14, K.Soft)
                    else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Adaptive(104.dp), Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(list.size) { i ->
                            val c = list[i]
                            Column(
                                Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                                    if (fetching) return@clickable
                                    fetching = true
                                    scope.launch { val b = withContext(Dispatchers.IO) { Covers.download(c.big) }; fetching = false; onPicture(b, c.from) }
                                },
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                AsyncImage(model = c.small, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(K.Panel))
                                Ui.Words(c.album.ifBlank { c.title }, 12, bold = true)
                                Ui.Words(c.artist.take(60) + " · " + c.from, 11, K.Soft)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Save and Undo, at the top and at the foot of the page. */
    @Composable
    private fun SaveRow(isAlbum: Boolean, count: Int, onSave: () -> Unit, onUndo: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ui.Button(if (isAlbum) "Save into all $count" else "Save into the file", icon = KueSettings.Icons.TICK, modifier = Modifier.weight(1.6f), onClick = onSave)
            Ui.Button("Undo", main = false, modifier = Modifier.weight(1f), onClick = onUndo)
        }
    }

    @Composable
    private fun Busy(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            Ui.Words(text, 14, K.Accent, modifier = Modifier.weight(1f))
        }
    }

    /**
     * One straight row: icon on the left, words beside it, the same height and edge as the rows round it (Kevin, 27
     * November 2026: "align straight"). Used for every choice on this page, one under the other.
     */
    @Composable
    private fun Line(label: String, icon: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(K.Raised).border(1.dp, K.Edge, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KueSettings.Icon(icon, 20.dp, K.Ink, 2f)
            Spacer(Modifier.width(14.dp))
            Ui.Words(label, 16, modifier = Modifier.weight(1f))
        }
    }

    /** A find with its cover and what it says, and Use. */
    @Composable
    private fun Found(f: Covers.Lookup, heading: String?, onUse: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Raised).border(1.dp, if (heading != null) K.Accent else K.Edge, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Use this", onClick = onUse).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(model = f.small.ifBlank { null }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(K.Panel))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (heading != null) Ui.Words(heading, 12, K.Accent, bold = true)
                Ui.Words(f.title.ifBlank { f.album }, 15, bold = true)
                Ui.Words(f.artist, 13)
                Ui.Words(
                    listOf(if (f.title.isNotBlank()) f.album else "", f.year, f.from, if (f.coverOnly) "picture only" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    12, K.Soft,
                )
            }
            Spacer(Modifier.width(8.dp))
            Ui.Words("Use", 15, K.Accent, bold = true)
        }
    }

    /** The search: Apple, Deezer and Wikipedia in the app, each find with its cover; then the web, Spotify, the phone and a link. */
    @Composable
    private fun Search(start: String, songs: Boolean, onUse: (Covers.Lookup) -> Unit, onPicture: (ByteArray?, String) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var words by remember { mutableStateOf(start) }
        var found by remember { mutableStateOf<List<Covers.Lookup>?>(null) }
        var looking by remember { mutableStateOf(false) }
        var forSongs by remember { mutableStateOf(songs) }
        var link by remember { mutableStateOf<String?>(null) }
        var more by remember { mutableStateOf(false) }
        val search = {
            looking = true
            found = null
            more = false
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
        // Any picture file on the phone (Downloads, Files, Photos), not just the photo gallery (Kevin, 27 November 2026: "use file").
        val fromFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch { onPicture(withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }, "the file") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(words) { words = it } }
            Ui.Button("Search", modifier = Modifier.height(52.dp), onClick = search)
        }
        KueSettings.Choices("Look for", listOf("Songs", "Albums"), if (forSongs) 0 else 1, Ui.look) { forSongs = it == 0; if (words.isNotBlank()) search() }
        // The three best, the rest only when asked for (Kevin, 27 November 2026: "don't need these, too much").
        when {
            looking -> Busy("Looking in Apple Music, Deezer and Wikipedia…")
            found?.isEmpty() == true -> Ui.Words("Nothing found for that. Change the words and search again, or use the web below.", 14, K.Soft)
            else -> found?.let { all ->
                val best = all.filter { !it.coverOnly }.ifEmpty { all }
                best.take(if (more) 20 else 3).forEach { f -> Found(f, null) { onUse(f) } }
                if (!more && best.size > 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Ui.Words("Show " + (minOf(best.size, 20) - 3) + " more", 14, K.Accent, bold = true, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { more = true }.padding(8.dp))
                }
            }
        }
        val q = android.net.Uri.encode(words)
        Line("Search the web", KueSettings.Icons.WEB) { open("https://www.google.com/search?q=$q") }
        Line("Find pictures on the web", KueSettings.Icons.DISC) { open("https://www.google.com/search?tbm=isch&q=$q") }
        Line("Look in Spotify", KueSettings.Icons.SEARCH) { SpotifyLink.openSearch(context, words) }
        Line("Use a picture file on your phone", KueSettings.Icons.PHONE) { fromFile.launch(arrayOf("image/*")) }
        Line("Use a picture from a link", KueSettings.Icons.OPEN) {
            val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            link = if (clip.contains("http")) clip.trim() else ""
        }
        link?.let { l ->
            Ui.Words("Paste the link to a picture, or to a page with it (Wikipedia, a shop):", 13, K.Soft)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field(l) { link = it } }
                Ui.Button("Use", modifier = Modifier.height(52.dp)) {
                    val got = l
                    link = null
                    scope.launch { onPicture(withContext(Dispatchers.IO) { Covers.pictureFromLink(got) }, "the link") }
                }
            }
        }
        Ui.Words("Or in your browser: Share a picture or the page, then pick KueSounds. It comes straight here.", 13, K.Soft)
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
}
