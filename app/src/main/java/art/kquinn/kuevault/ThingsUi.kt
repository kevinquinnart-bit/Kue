package art.kquinn.kuevault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Something shared into KueVault from another app, waiting for the person to say where it goes. */
object Incoming {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var mime by mutableStateOf("")
    var text by mutableStateOf("")
    /** Goes up by one with each share, so the screen moves to it even when the same thing is shared twice. */
    var count by mutableStateOf(0)

    fun take(intent: Intent?) {
        intent ?: return
        val got: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> return
        }
        val words = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().ifBlank { intent.getStringExtra(Intent.EXTRA_HTML_TEXT).orEmpty().let { Scan.html(it) } }
        if (got.isEmpty() && words.isBlank()) return
        uris = got
        mime = intent.type.orEmpty()
        text = (intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { it + "\n" } ?: "") + words
        count++
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()

    fun waiting() = uris.isNotEmpty() || text.isNotBlank()

    fun clear() { uris = emptyList(); mime = ""; text = "" }

    /** The first web link in what was shared. */
    fun link(): String? = Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd('.', ',', ')')
}

/** What the scanner, the camera or the file picker brought in, and the pictures to read its words from. */
class Got(val brought: Work.Brought, val pictures: List<Uri>)

/** The scanner, the camera and the file picker, ready to use from a screen. */
class Getter(val scan: () -> Unit, val photo: () -> Unit, val file: () -> Unit)

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** The scanner (Google's on the phone: finds the page's edges, flattens it, makes a PDF), the camera and the picker. */
@Composable
fun rememberGetter(onGot: (Got) -> Unit): Getter {
    val context = LocalContext.current
    var photoFile by remember { mutableStateOf<File?>(null) }
    val scanner = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val result = GmsDocumentScanningResult.fromActivityResultIntent(r.data) ?: return@rememberLauncherForActivityResult
        val pages = result.pages?.map { it.imageUri }.orEmpty()
        val pdf = result.pdf?.uri
        Work.scope.launch {
            val brought = if (pdf != null) Work.bringIn(context, pdf, "application/pdf") else pages.firstOrNull()?.let { Work.bringIn(context, it, "image/jpeg") }
            if (brought == null) Work.problem = "The scan couldn't be kept. Try again." else onGot(Got(brought, pages))
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile ?: return@rememberLauncherForActivityResult
        if (!ok || !f.exists() || f.length() == 0L) return@rememberLauncherForActivityResult
        onGot(Got(Work.Brought(f, "image/jpeg", "Photo.jpg"), listOf(Uri.fromFile(f))))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        Work.scope.launch {
            val brought = Work.bringIn(context, uri)
            if (brought == null) Work.problem = "That file couldn't be opened." else onGot(Got(brought, if (brought.mime.startsWith("image/")) listOf(Uri.fromFile(brought.file)) else emptyList()))
        }
    }
    return remember {
        Getter(
            scan = {
                val activity = context.activity()
                val options = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(20)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
                val client: GmsDocumentScanner = GmsDocumentScanning.getClient(options)
                if (activity == null) Work.problem = "The scanner couldn't start." else client.getStartScanIntent(activity)
                    .addOnSuccessListener { scanner.launch(IntentSenderRequest.Builder(it).build()) }
                    .addOnFailureListener { Work.problem = "This phone's scanner isn't available (it needs Google Play services). Take a photo instead." }
            },
            photo = {
                val folder = File(context.cacheDir, "photo").apply { mkdirs() }
                val f = File(folder, "photo" + System.nanoTime() + ".jpg")
                photoFile = f
                runCatching { camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f)) }
                    .onFailure { Work.problem = "This phone has no camera app KueVault can use." }
            },
            file = { picker.launch(arrayOf("application/pdf", "image/*", "text/plain", "text/html", "message/rfc822")) },
        )
    }
}

/** Reads what was brought in and makes a first guess at the thing. A barcode in a photo is looked up. */
suspend fun guessFrom(context: Context, got: Got): Thing {
    val words = if (got.pictures.isNotEmpty()) got.pictures.map { Scan.words(context, it) }.joinToString("\n") else Scan.wordsOfFile(context, got.brought.file, got.brought.mime)
    val g = Reader.read(words)
    var name = g.name
    var make = g.make
    val code = got.pictures.firstOrNull()?.let { Scan.barcode(context, it) }.orEmpty()
    if (code.isNotEmpty()) withContext(Dispatchers.IO) { Scan.lookUp(code) }?.let { (n, brand) ->
        if (name.isBlank() || name == make || name == g.model) name = n
        if (make.isBlank()) make = brand
    }
    return Thing(name = name, make = make, model = g.model, serial = g.serial, shop = g.shop, boughtDay = g.bought?.toEpochDay(), price = g.price, warrantyMonths = g.warrantyMonths, barcode = code)
}

object ThingsUi {
    private val day = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
    private val short = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.UK)
    private val Good = Color(0xFF6BD08A)

    @Composable
    fun Show(screen: Screen, go: (Screen) -> Unit) {
        when (screen) {
            Screen.Things -> ThingList(go)
            is Screen.ThingView -> View(screen.id, go)
            Screen.AddThing -> Add(go)
            is Screen.EditThing -> Edit(screen, go)
            is Screen.Amazon -> AmazonPick(screen.orders, go)
            is Screen.Ask -> Ask(screen.thingId, go)
            Screen.Shared -> Shared(go)
            else -> {}
        }
    }

    @Composable
    private fun CoverLine(t: Thing) {
        val end = t.warrantyEnds
        val (text, colour) = when (Things.cover(t)) {
            Cover.COVERED -> "Under warranty until " + end!!.format(day) to Good
            Cover.ENDING_SOON -> "Warranty ends " + end!!.format(day) to K.Warn
            Cover.ENDED -> "Warranty ended " + end!!.format(day) to K.Soft
            Cover.NONE_KNOWN -> (if (t.boughtDay == null) "When it was bought isn't known" else "Warranty length not known") to K.Soft
        }
        Words(text, 12, colour, bold = colour != K.Soft, lines = 1)
    }

    /** Everything the person owns, with search; ending warranties first. */
    @Composable
    private fun ThingList(go: (Screen) -> Unit) {
        var query by remember { mutableStateOf("") }
        val found = Things.search(Things.all.toList(), query)
        val soon = Things.all.filter { Things.cover(it) == Cover.ENDING_SOON }
        val home = Vault.homeOf(Section.THINGS)
        Column(Modifier.fillMaxSize()) {
            Title("My things") { go(Screen.Hub) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words("Each thing you own with its receipt, warranty and manual, all in one place.", 13, K.Soft, lines = 2)
                if (home == null) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(2.dp, K.Accent, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Words("Choose where the papers live", 15, bold = true)
                        Words("Receipts, warranties, manuals and photos go into one of your places, in a folder called My things.", 13, K.Soft, lines = 3)
                        Button("Choose a place", main = true) { go(Screen.ChooseHome(Section.THINGS)) }
                    }
                } else Row(Modifier.fillMaxWidth().clickable { go(Screen.ChooseHome(Section.THINGS)) }) {
                    Words("Papers live in " + home.label, 12, K.Soft, lines = 1, modifier = Modifier.weight(1f))
                    Words("Change", 12, K.Accent, bold = true, lines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("Add a thing", main = true, modifier = Modifier.weight(1f), icon = KueSettings.Icons.PLUS) { go(Screen.AddThing) }
                    if (Things.all.any { it.papersOf(Paper.MANUAL).isNotEmpty() }) Button("Ask your manuals", modifier = Modifier.weight(1f)) { go(Screen.Ask(null)) }
                }
                if (Things.all.size > 3) Ui.Field("", query, "Search: \"kettle\", \"Bosch\", \"Currys\"") { query = it }
                if (soon.isNotEmpty() && query.isBlank()) Words(soon.size.toString() + (if (soon.size == 1) " warranty ends" else " warranties end") + " in the next 30 days.", 13, K.Warn, bold = true, lines = 2)
            }
            Spacer(Modifier.height(8.dp))
            if (Things.all.isEmpty()) Words("Nothing here yet. Tap Add a thing: scan a receipt, photograph the label, or bring in your Amazon orders.", 15, K.Soft, lines = 4, modifier = Modifier.padding(horizontal = 20.dp))
            else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val ordered = if (query.isBlank()) found.sortedBy { if (Things.cover(it) == Cover.ENDING_SOON) 0 else 1 } else found
                items(ordered, key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).clickable { go(Screen.ThingView(t.id)) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Words(t.title, 15, bold = true)
                        val sub = listOf(listOf(t.make, t.model).filter { it.isNotBlank() }.joinToString(" "), t.shop, t.bought?.format(short).orEmpty()).filter { it.isNotBlank() && !t.title.startsWith(it) }
                        if (sub.isNotEmpty()) Words(sub.joinToString(" · "), 12, K.Soft, lines = 1)
                        CoverLine(t)
                        val kinds = Paper.entries.filter { t.papersOf(it).isNotEmpty() }
                        Words(if (kinds.isEmpty()) "No papers yet" else kinds.joinToString(" · ") { it.one }, 12, if (kinds.isEmpty()) K.Accent else K.KueBlue, lines = 1)
                    }
                }
            }
        }
    }

    /** The ways of adding a thing. */
    @Composable
    private fun Add(go: (Screen) -> Unit) {
        val context = LocalContext.current
        var reading by remember { mutableStateOf(false) }
        val home = Vault.homeOf(Section.THINGS)
        val getter = rememberGetter { got ->
            reading = true
            Work.scope.launch {
                Work.doing = "Reading it…"
                val draft = guessFrom(context, got)
                Work.doing = ""
                reading = false
                val kind = if (got.brought.mime.startsWith("image/") && got.pictures.size == 1 && got.brought.name == "Photo.jpg") Paper.PHOTO else Paper.RECEIPT
                go(Screen.EditThing(draft, true, got.brought, kind))
            }
        }
        val amazon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            Work.scope.launch {
                Work.doing = "Reading your Amazon orders…"
                val orders = withContext(Dispatchers.IO) { runCatching { amazonOrders(context, uri) }.getOrDefault(emptyList()) }
                Work.doing = ""
                if (orders.isEmpty()) Work.problem = "No orders found in that file. Pick the zip Amazon sent (or the Retail.OrderHistory file inside it)."
                else go(Screen.Amazon(orders))
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Add a thing") { go(Screen.Things) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (home == null) {
                    Words("Choose where the papers live first, so the receipt or photo can be kept.", 13, K.Warn, lines = 3)
                    Button("Choose a place", main = true, modifier = Modifier.fillMaxWidth()) { go(Screen.ChooseHome(Section.THINGS)) }
                }
                if (reading) Words("Reading it…", 15, K.Soft)
                Way("Scan the receipt or invoice", "The camera finds the page and flattens it. KueVault reads the make, model, shop, date, price and warranty off it.", home != null) { getter.scan() }
                Way("Photograph the label or box", "The label with the model and serial number, or the barcode on the box.", home != null) { getter.photo() }
                Way("From a file or email", "A PDF or picture on the phone. For an email: in Gmail tap ⋮ › Print › Save as PDF, then pick that PDF here. Or share it straight to KueVault.", home != null) { getter.file() }
                Way("From your Amazon orders", "In Amazon: Account › Data and privacy › Request your data, then choose Your Orders. Amazon emails a link to a zip file; download it and pick it here.", true) { amazon.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*")) }
                Way("Type it in", "Name, make and model, and when and where it was bought.", true) { go(Screen.EditThing(Thing(), true)) }
            }
        }
    }

    @Composable
    private fun Way(title: String, text: String, on: Boolean, onClick: () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) K.Panel else K.Page).then(if (on) Modifier.clickable(onClick = onClick) else Modifier.border(2.dp, K.Raised, RoundedCornerShape(14.dp))).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Words(title, 15, if (on) K.Ink else K.Soft, bold = true)
            Words(text, 12, K.Soft, lines = 4)
        }
    }

    /** Amazon's orders: from the zip Amazon sends (every Retail.OrderHistory file in it), or one CSV file. */
    private fun amazonOrders(context: Context, uri: Uri): List<Reader.Order> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        val zip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!zip) return Reader.amazon(String(bytes, Charsets.UTF_8))
        val out = ArrayList<Reader.Order>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name.substringAfterLast('/')
                if (!e.isDirectory && n.endsWith(".csv", true) && n.contains("OrderHistory", true)) out += Reader.amazon(String(z.readBytes(), Charsets.UTF_8))
            }
        }
        return out.distinctBy { it.orderId + "|" + it.asin + "|" + it.name }.sortedByDescending { it.bought ?: LocalDate.MIN }
    }

    /** Choosing which Amazon orders to add. Small things (under £20) are hidden unless asked for. */
    @Composable
    private fun AmazonPick(orders: List<Reader.Order>, go: (Screen) -> Unit) {
        var query by remember { mutableStateOf("") }
        var small by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf(setOf<Int>()) }
        val have = Things.all.map { it.notes }.toSet()
        fun cost(o: Reader.Order) = o.price.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
        val shown = orders.withIndex().filter { (_, o) ->
            (small || cost(o) >= 20.0 || o.price.isBlank()) && (query.isBlank() || Library.plain(o.name).contains(Library.plain(query)))
        }
        Column(Modifier.fillMaxSize()) {
            Title("Amazon orders") { go(Screen.AddThing) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words("Tick the things you want to keep a file for.", 13, K.Soft, lines = 2)
                Ui.Field("", query, "Search your orders") { query = it }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ui.Chip("Under £20 too", small) { small = !small }
                    Ui.Chip("Tick all shown", false) { picked = picked + shown.map { it.index } }
                    Ui.Chip("Untick all", false) { picked = emptySet() }
                }
                Button(if (picked.isEmpty()) "Tick some to add them" else "Add " + picked.size + " thing" + (if (picked.size == 1) "" else "s"), main = picked.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    if (picked.isEmpty()) return@Button
                    var added = 0
                    for (i in picked.sortedDescending()) {
                        val t = Reader.fromOrder(orders[i])
                        if (t.notes.isNotEmpty() && t.notes in have) continue
                        Things.keep(t)
                        added++
                    }
                    Work.told = "$added thing" + (if (added == 1) "" else "s") + " added from Amazon. Add each one's manual and warranty when you like."
                    go(Screen.Things)
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.index }) { (i, o) ->
                    val on = i in picked
                    val already = Reader.fromOrder(o).notes.let { it.isNotEmpty() && it in have }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) K.Raised else K.Panel).clickable { if (!already) picked = if (on) picked - i else picked + i }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (on) K.Accent else K.Page).border(2.dp, if (on) K.Accent else K.Soft, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { if (on) KueSettings.Icon(KueSettings.Icons.TICK, 16.dp, Color(0xFF111111), 2.6f) }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Words(o.name, 13, if (already) K.Soft else K.Ink, lines = 2)
                            Words(listOfNotNull(o.bought?.format(short), o.price.takeIf { it.isNotBlank() }, if (already) "already in My things" else null).joinToString(" · "), 11, K.Soft, lines = 1)
                        }
                    }
                }
            }
        }
    }

    /** The form for a thing. Whatever was read off the paper is already in; change anything that's wrong. */
    @Composable
    private fun Edit(screen: Screen.EditThing, go: (Screen) -> Unit) {
        val context = LocalContext.current
        val d = screen.draft
        var name by remember { mutableStateOf(d.name) }
        var make by remember { mutableStateOf(d.make) }
        var model by remember { mutableStateOf(d.model) }
        var serial by remember { mutableStateOf(d.serial) }
        var shop by remember { mutableStateOf(d.shop) }
        var bought by remember { mutableStateOf(d.bought?.format(short).orEmpty()) }
        var price by remember { mutableStateOf(d.price) }
        var months by remember { mutableStateOf(d.warrantyMonths) }
        var monthsText by remember { mutableStateOf(d.warrantyMonths?.takeIf { it % 12 != 0 }?.toString().orEmpty()) }
        var notes by remember { mutableStateOf(d.notes) }
        var saying by remember { mutableStateOf("") }
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title(if (screen.isNew) "New thing" else "Change details") { go(if (screen.isNew) Screen.Things else Screen.ThingView(d.id)) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (screen.brought != null) Words("Read off the " + screen.kind.one.lowercase() + ". Check each one; it goes with the thing when you keep it.", 13, K.Soft, lines = 3)
                Ui.Field("What it is", name, "Washing machine") { name = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Ui.Field("Make", make, "Bosch") { make = it } }
                    Column(Modifier.weight(1f)) { Ui.Field("Model", model, "WAN28281GB") { model = it } }
                }
                Ui.Field("Serial number", serial, "On the label") { serial = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Ui.Field("Shop", shop, "Currys") { shop = it } }
                    Column(Modifier.weight(1f)) { Ui.Field("Price", price, "£449.00") { price = it } }
                }
                Ui.Field("Bought on", bought, "24/11/2026") { bought = it }
                Words("Warranty", 13, K.Soft, lines = 1)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Ui.Chip("Not known", months == null) { months = null; monthsText = "" }
                    for (y in listOf(1, 2, 3, 5, 10)) Ui.Chip("$y year" + if (y == 1) "" else "s", months == y * 12) { months = y * 12; monthsText = "" }
                }
                Ui.Field("Or months", monthsText, "18") { t ->
                    monthsText = t.filter { it.isDigit() }.take(3)
                    if (monthsText.isNotEmpty()) months = monthsText.toIntOrNull()?.takeIf { it in 1..240 }
                }
                Ui.Field("Notes", notes, "Extended cover, who to call…") { notes = it }
                if (saying.isNotEmpty()) Words(saying, 14, K.Warn, lines = 3)
                Button("Keep", main = true, modifier = Modifier.fillMaxWidth()) {
                    val day = if (bought.isBlank()) null else Reader.datesIn(bought).firstOrNull()
                    if (bought.isNotBlank() && day == null) { saying = "Type the day it was bought like 24/11/2026."; return@Button }
                    if (day != null && day.isAfter(Things.today())) { saying = "That day hasn't come yet."; return@Button }
                    val t = d.copy(
                        name = name.trim(), make = make.trim(), model = model.trim().uppercase(), serial = serial.trim().uppercase(), shop = shop.trim(),
                        boughtDay = day?.toEpochDay(), price = price.trim(), warrantyMonths = months, notes = notes.trim(),
                    )
                    if (t.title == "Unnamed thing") { saying = "Give it a name, or a make and model."; return@Button }
                    Things.keep(t)
                    screen.brought?.let { b -> Work.scope.launch { Work.attach(context, t, screen.kind, b).takeIf { it.isNotEmpty() }?.let { Work.told = it } } }
                    if (t.warrantyEnds != null && !Reminders.allowed(context) && Build.VERSION.SDK_INT >= 33) notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    Reminders.start(context)
                    go(Screen.ThingView(t.id))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    /** One thing: its details, its warranty, and its papers by kind. */
    @Composable
    private fun View(id: String, go: (Screen) -> Unit) {
        val context = LocalContext.current
        val t = Things.get(id) ?: run { LaunchedEffect(id) { go(Screen.Things) }; return }
        var addingKind by remember { mutableStateOf<Paper?>(null) }
        var choosing by remember { mutableStateOf<Paper?>(null) }
        var holding by remember { mutableStateOf<Paperwork?>(null) }
        var removing by remember { mutableStateOf(false) }
        val getter = rememberGetter { got ->
            val kind = addingKind ?: Paper.OTHER
            addingKind = null
            val now = Things.get(id) ?: return@rememberGetter
            Work.scope.launch {
                Work.attach(context, now, kind, got.brought).takeIf { it.isNotEmpty() }?.let { Work.told = it }
                // A receipt or label added later fills in what the thing doesn't have yet.
                if (kind == Paper.RECEIPT || kind == Paper.PHOTO || kind == Paper.WARRANTY) {
                    val g = guessFrom(context, got)
                    val cur = Things.get(id) ?: return@launch
                    Things.keep(cur.copy(
                        make = cur.make.ifBlank { g.make }, model = cur.model.ifBlank { g.model }, serial = cur.serial.ifBlank { g.serial },
                        shop = cur.shop.ifBlank { g.shop }, boughtDay = cur.boughtDay ?: g.boughtDay, price = cur.price.ifBlank { g.price },
                        warrantyMonths = cur.warrantyMonths ?: g.warrantyMonths, barcode = cur.barcode.ifBlank { g.barcode },
                    ))
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title(t.title) { go(Screen.Things) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Detail("Make and model", listOf(t.make, t.model).filter { it.isNotBlank() }.joinToString(" "))
                    Detail("Serial number", t.serial)
                    Detail("Bought", listOf(t.bought?.format(day).orEmpty(), t.shop.let { if (it.isNotBlank()) "at $it" else "" }, t.price).filter { it.isNotBlank() }.joinToString(" "))
                    Detail("Notes", t.notes)
                    CoverLine(t)
                    Words("Change details", 13, K.Accent, bold = true, lines = 1, modifier = Modifier.clickable { go(Screen.EditThing(t, false)) }.padding(vertical = 6.dp))
                }
                for (kind in Paper.entries) {
                    val list = t.papersOf(kind)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Words(kind.title, 15, bold = true, lines = 1, modifier = Modifier.weight(1f))
                        Row(Modifier.clickable { choosing = if (choosing == kind) null else kind }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            KueSettings.Icon(KueSettings.Icons.PLUS, 14.dp, K.Accent, 2.4f)
                            Spacer(Modifier.width(4.dp))
                            Words("Add", 13, K.Accent, bold = true, lines = 1)
                        }
                    }
                    if (choosing == kind) {
                        if (Vault.homeOf(Section.THINGS) == null) Button("Choose where the papers live first", main = true, modifier = Modifier.fillMaxWidth()) { go(Screen.ChooseHome(Section.THINGS)) }
                        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Scan", modifier = Modifier.weight(1f)) { choosing = null; addingKind = kind; getter.scan() }
                            Button("Photo", modifier = Modifier.weight(1f)) { choosing = null; addingKind = kind; getter.photo() }
                            Button("File", modifier = Modifier.weight(1f)) { choosing = null; addingKind = kind; getter.file() }
                        }
                    }
                    if (kind == Paper.MANUAL && list.isEmpty()) {
                        Words("Most makers put the manual online as a PDF. Find it, download it, then add it here with File, or share the link to KueVault.", 12, K.Soft, lines = 4)
                        Button("Find the manual online", modifier = Modifier.fillMaxWidth()) {
                            val q = listOf(t.make, t.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { t.title } + " manual pdf"
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { Work.problem = "No web browser on this phone." }
                        }
                    }
                    for (p in list) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { Work.scope.launch { Work.openPaper(context, p) } }.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Words(p.name, 14, lines = 1, modifier = Modifier.weight(1f))
                                Box(Modifier.clickable { holding = if (holding == p) null else p }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) { KueSettings.Icon(KueSettings.Icons.MORE, 22.dp, K.Soft, filled = true) }
                            }
                            Words(Vault.size(p.size) + " · in " + (Vault.place((Things.find(p, Library.items.toList()) ?: p.item()).placeId)?.label ?: "a place no longer connected"), 11, K.Soft, lines = 1)
                            if (holding == p) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button("Open", main = true, icon = KueSettings.Icons.OPEN) { holding = null; Work.scope.launch { Work.openPaper(context, p) } }
                                Button("Move to the bin", icon = KueSettings.Icons.BIN) { holding = null; Work.scope.launch { Work.binPaper(context, id, p) } }
                            }
                        }
                    }
                    if (kind == Paper.MANUAL && list.isNotEmpty()) Button("Ask the manual", main = true, modifier = Modifier.fillMaxWidth()) { go(Screen.Ask(id)) }
                }
                Spacer(Modifier.height(10.dp))
                if (!removing) Button("Take out of My things", modifier = Modifier.fillMaxWidth()) { removing = true }
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Words(if (t.papers.isEmpty()) "Take " + t.title + " out?" else "Take " + t.title + " out. What about its " + t.papers.size + " paper" + (if (t.papers.size == 1) "" else "s") + "?", 14, lines = 3)
                    if (t.papers.isNotEmpty()) Button("Move its papers to the bin too", modifier = Modifier.fillMaxWidth()) { Work.scope.launch { Work.removeThing(context, t, true) }; go(Screen.Things) }
                    Button(if (t.papers.isEmpty()) "Take it out" else "Keep its papers in My things' folder", modifier = Modifier.fillMaxWidth()) { Work.scope.launch { Work.removeThing(context, t, false) }; go(Screen.Things) }
                    Button("Don't take it out", main = true, modifier = Modifier.fillMaxWidth()) { removing = false }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    @Composable
    private fun Detail(label: String, value: String) {
        if (value.isBlank()) return
        Words(label, 11, K.Soft, lines = 1)
        Words(value, 14, lines = 3)
    }

    /** Asking the manuals: typed or spoken; the manual's own words come back, with the page, and can be read out. */
    @Composable
    private fun Ask(thingId: String?, go: (Screen) -> Unit) {
        val context = LocalContext.current
        val things = thingId?.let { listOfNotNull(Things.get(it)) } ?: Things.all.toList()
        var question by remember { mutableStateOf("") }
        var hits by remember { mutableStateOf<List<Manuals.Hit>?>(null) }
        var passages by remember { mutableStateOf<List<Manuals.Passage>>(emptyList()) }
        var loaded by remember { mutableStateOf(0) }
        val unread = things.flatMap { t -> t.papersOf(Paper.MANUAL).filter { !Manuals.indexed(context, t.id, it) }.map { t to it } }
        LaunchedEffect(loaded, thingId) { passages = withContext(Dispatchers.IO) { Manuals.passages(context, things) } }
        fun ask(q: String) { question = q; hits = Manuals.ask(q, passages) }
        val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (r.resultCode == Activity.RESULT_OK && !said.isNullOrBlank()) ask(said)
        }
        Column(Modifier.fillMaxSize()) {
            Title(if (thingId == null) "Ask your manuals" else "Ask the manual") { Speaker.stop(); go(thingId?.let { Screen.ThingView(it) } ?: Screen.Things) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words(if (thingId == null) "Ask about anything you own. The answer is the manual's own words, with its page." else "Ask about " + things.firstOrNull()?.title + ". The answer is the manual's own words, with its page.", 13, K.Soft, lines = 3)
                if (unread.isNotEmpty() && Work.doing.isEmpty()) Button("Read " + unread.size + " manual" + (if (unread.size == 1) "" else "s") + " first (on this phone)", main = true, modifier = Modifier.fillMaxWidth()) {
                    Work.scope.launch {
                        var bad = 0
                        for ((t, p) in unread) if (!Work.readManual(context, t.id, p)) bad++
                        loaded++
                        if (bad > 0) Work.problem = "$bad manual" + (if (bad == 1) "" else "s") + " couldn't be read." else Work.told = "Ready to ask."
                    }
                }
                Ui.Field("", question, "How do I clean the filter?") { question = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button("Ask", main = true, modifier = Modifier.weight(1f)) { if (question.isNotBlank()) ask(question) }
                    Button("Say it", modifier = Modifier.weight(1f), icon = KueSettings.Icons.MIC) {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB").putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your manuals")
                        runCatching { listen.launch(i) }.onFailure { Work.problem = "This phone has no voice typing. Type the question instead." }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val h = hits
            when {
                passages.isEmpty() && unread.isEmpty() -> Words("No manual words to ask yet. Add a manual to a thing first.", 14, K.Soft, lines = 3, modifier = Modifier.padding(horizontal = 20.dp))
                h == null -> {}
                h.isEmpty() -> Words("Nothing in the manuals matches that. Try other words: \"filter\", \"reset\", \"error E15\".", 14, K.Soft, lines = 3, modifier = Modifier.padding(horizontal = 20.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(h) { hit ->
                        val t = Things.get(hit.passage.thingId)
                        val paper = t?.papers?.firstOrNull { it.id == hit.passage.paperId }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Words((if (thingId == null) (t?.title ?: "") + " · " else "") + "page " + hit.passage.page, 12, K.KueBlue, bold = true, lines = 1)
                            Words(hit.passage.text, 14, lines = 30)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button("Read it out", main = true) { Speaker.say(context, hit.passage.text) }
                                Button("Stop") { Speaker.stop() }
                                if (paper != null) Button("Open manual") { Work.scope.launch { Work.openPaper(context, paper) } }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Something shared into KueVault: a new thing from it, one of a thing's papers, or Documents. */
    @Composable
    private fun Shared(go: (Screen) -> Unit) {
        val context = LocalContext.current
        var toThing by remember { mutableStateOf(false) }
        var kind by remember { mutableStateOf(Paper.RECEIPT) }
        val link = Incoming.link()
        val count = Incoming.uris.size
        val what = when {
            count > 1 -> "$count files"
            count == 1 -> if (Incoming.mime.startsWith("image/")) "A picture" else if (Incoming.mime == "application/pdf") "A PDF" else "A file"
            link != null && Incoming.text.trim() == link -> "A link"
            else -> "Some words"
        }
        fun done() { Incoming.clear() }
        /** What was shared, brought into the phone: the files, or a downloaded link, or the words as a text file. */
        suspend fun bring(): List<Work.Brought> = when {
            Incoming.uris.isNotEmpty() -> Incoming.uris.mapNotNull { Work.bringIn(context, it, Incoming.mime.takeIf { m -> !m.endsWith("/*") }) }
            link != null && Incoming.text.trim() == link -> listOfNotNull(Work.fetchLink(context, link))
            else -> listOf(Work.textFile(context, Incoming.text))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Shared with KueVault") { done(); go(Screen.Hub) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("$what. Where should it go?", 15, bold = true, lines = 2)
                if (Vault.homeOf(Section.THINGS) == null) {
                    Words("Choose where My things' papers live first.", 13, K.Warn, lines = 2)
                    Button("Choose a place", main = true, modifier = Modifier.fillMaxWidth()) { go(Screen.ChooseHome(Section.THINGS)) }
                } else if (!toThing) {
                    Button("A new thing (read it as the receipt)", main = true, modifier = Modifier.fillMaxWidth()) {
                        Work.scope.launch {
                            val got = bring().firstOrNull() ?: return@launch
                            Work.doing = "Reading it…"
                            val draft = guessFrom(context, Got(got, if (got.mime.startsWith("image/")) listOf(Uri.fromFile(got.file)) else emptyList()))
                            Work.doing = ""
                            done()
                            go(Screen.EditThing(draft, true, got, Paper.RECEIPT))
                        }
                    }
                    if (Things.all.isNotEmpty()) Button("One of my things", modifier = Modifier.fillMaxWidth()) { toThing = true }
                } else {
                    Words("As its…", 13, K.Soft, lines = 1)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in Paper.entries) Ui.Chip(k.one, kind == k) { kind = k }
                    }
                    for (t in Things.search(Things.all.toList(), "")) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable {
                            Work.scope.launch {
                                val all = bring()
                                done()
                                for (b in all) Work.attach(context, t, kind, b).takeIf { it.isNotEmpty() }?.let { Work.told = it }
                            }
                            go(Screen.ThingView(t.id))
                        }.padding(12.dp)) {
                            Words(t.title, 14, bold = true, lines = 1)
                            Words(listOf(t.make, t.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { " " }, 11, K.Soft, lines = 1)
                        }
                    }
                }
                if (Incoming.uris.isNotEmpty() && Vault.homeOf(Section.DOCUMENTS) != null) Button("Put it in Documents", modifier = Modifier.fillMaxWidth()) {
                    val uris = Incoming.uris
                    done()
                    Work.scope.launch { Work.upload(context, Section.DOCUMENTS, uris) }
                    go(Screen.Files(Section.DOCUMENTS))
                }
                Button("Not now", modifier = Modifier.fillMaxWidth()) { done(); go(Screen.Hub) }
            }
        }
    }
}
