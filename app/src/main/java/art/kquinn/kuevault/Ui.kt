package art.kquinn.kuevault

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.launch

/** KueVault's colours: the Kue apps' dark blue page, deeper blue boxes, yellow, and the K's light blue. */
object K {
    val Page = Color(0xFF0E1626)
    val Panel = Color(0xFF16213A)
    val Raised = Color(0xFF263A63)
    val Accent = Color(0xFFFFD23F)
    val Ink = Color.White
    val Soft = Color(0xFFB9C2D3)
    val Warn = Color(0xFFF28C38)
    /** The light blue of the Kue K: the second word of every Kue app's name. */
    val KueBlue = Color(0xFF5CCEFF)
    val Poppins = FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_bold, FontWeight.Bold),
        Font(R.font.poppins_bold, FontWeight.SemiBold),
    )
}

/** The screens, as a person moves between them. */
sealed class Screen {
    data object Hub : Screen()
    /** Everything in every place, with search. */
    data object Library : Screen()
    data object Places : Screen()
    data object Settings : Screen()
    /** One section's files. */
    data class Files(val section: Section) : Screen()
    /** Choosing which place [section] lives in. */
    data class ChooseHome(val section: Section) : Screen()
    /** Choosing what kind of place to connect; [forSection] lives there once it's connected. */
    data class AddPlace(val forSection: Section?) : Screen()
    /** Typing in a home computer's details. */
    data class HomeForm(val forSection: Section?) : Screen()
}

/** What the screens ask the app to do that only the app can: the phone's own pickers and Google's page. */
class Asks(
    /** Connects a Google account; the section, when given, then lives in it. */
    val addGoogle: (Section?) -> Unit,
    val signInAgain: (String) -> Unit,
    /** The phone's file picker; what is picked goes into the section's place. */
    val pickFiles: (Section) -> Unit,
)

@Composable
private fun Words(text: String, size: Int = 15, colour: Color = K.Ink, bold: Boolean = false, lines: Int = 2, modifier: Modifier = Modifier) {
    Text(text, color = colour, fontSize = size.sp, fontFamily = K.Poppins, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun Button(text: String, main: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (main) K.Accent else K.Panel).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Words(text, 15, if (main) Color(0xFF111111) else K.Ink, bold = main) }
}

@Composable
private fun Meter(fraction: Float, colour: Color, height: Int = 6) {
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp)).background(K.Raised)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).clip(RoundedCornerShape(height.dp)).background(colour))
    }
}

@Composable
private fun Title(text: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable(onClick = onBack), contentAlignment = Alignment.Center) { Words("‹", 24) }
            Spacer(Modifier.width(12.dp))
        }
        Words(text, 24, bold = true, lines = 1)
    }
}

object Ui {
    @Composable
    fun App(asks: Asks) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf<Screen>(Screen.Hub) }
        BackHandler(enabled = screen != Screen.Hub) {
            screen = when (val s = screen) {
                is Screen.HomeForm -> Screen.AddPlace(s.forSection)
                is Screen.AddPlace -> s.forSection?.let { Screen.ChooseHome(it) } ?: Screen.Places
                else -> Screen.Hub
            }
        }
        LaunchedEffect(Vault.places.size) { if (Vault.places.isNotEmpty()) { Work.readSpace(context); Work.readLibrary(context) } }

        Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (val s = screen) {
                    Screen.Hub -> Hub(
                        onOpen = { section ->
                            screen = when {
                                Vault.homeOf(section) == null && Library.of(section).isEmpty() -> Screen.ChooseHome(section)
                                else -> Screen.Files(section)
                            }
                        },
                        onSettings = { screen = Screen.Settings },
                    )
                    Screen.Library -> LibraryScreen()
                    Screen.Places -> Places(asks, onAdd = { screen = Screen.AddPlace(null) }, onChoose = { screen = Screen.ChooseHome(it) })
                    Screen.Settings -> Settings { screen = Screen.Hub }
                    is Screen.Files -> Files(s.section, asks, onBack = { screen = Screen.Hub }, onMove = { screen = Screen.ChooseHome(s.section) })
                    is Screen.ChooseHome -> ChooseHome(
                        s.section,
                        onPick = { id -> Vault.setHome(s.section, id); Work.doing = s.section.title + " now lives in " + (Vault.place(id)?.label ?: "") + "."; screen = Screen.Hub },
                        onAdd = { screen = Screen.AddPlace(s.section) },
                        onBack = { screen = Screen.Hub },
                    )
                    is Screen.AddPlace -> AddPlace(
                        s.forSection,
                        onGoogle = { screen = s.forSection?.let { Screen.Hub } ?: Screen.Places; asks.addGoogle(s.forSection) },
                        onHome = { screen = Screen.HomeForm(s.forSection) },
                        onBack = { screen = s.forSection?.let { Screen.ChooseHome(it) } ?: Screen.Places },
                    )
                    is Screen.HomeForm -> HomeForm(s.forSection, onDone = { screen = if (s.forSection != null) Screen.Hub else Screen.Places }, onBack = { screen = Screen.AddPlace(s.forSection) })
                }
            }
            if (Work.doing.isNotEmpty()) Box(Modifier.fillMaxWidth().background(K.Raised).padding(horizontal = 20.dp, vertical = 10.dp)) { Words(Work.doing, 14) }
            if (Work.problem.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF5A2A1A)).clickable { Work.problem = "" }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Words(Work.problem + "  ·  tap to close", 14, lines = 4)
                }
            }
            Updates.offer?.let { latest ->
                Row(Modifier.fillMaxWidth().background(K.Panel).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words("KueVault " + latest.name + " is ready", 14, modifier = Modifier.weight(1f))
                    Button("Update", main = true) { Updates.offer = null; scope.launch { Work.doing = Updates.updateByHand(context) { Work.doing = it } } }
                    Spacer(Modifier.width(8.dp))
                    Button("Not now") { Updates.putOff = latest.code; Updates.offer = null }
                }
            }
            Nav(screen) { screen = it }
        }
    }

    @Composable
    private fun Nav(now: Screen, go: (Screen) -> Unit) {
        Row(Modifier.fillMaxWidth().background(K.Panel).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
            val items = listOf("Hub" to Screen.Hub, "Search" to Screen.Library, "Places" to Screen.Places)
            for ((label, target) in items) {
                val on = now == target
                Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable { go(target) }.padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Words(label, 13, if (on) K.Accent else K.Soft, bold = on)
                }
            }
        }
    }

    @Composable
    private fun Hub(onOpen: (Section) -> Unit, onSettings: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.kue_mark), contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    buildAnnotatedString { append("Kue"); withStyle(SpanStyle(color = K.KueBlue)) { append("Vault") } },
                    color = K.Ink, fontSize = 24.sp, fontFamily = K.Poppins, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable(onClick = onSettings), contentAlignment = Alignment.Center) { Words("⚙", 20) }
            }
            val known = Vault.places.mapNotNull { Vault.quotas[it.id] }
            val used = known.sumOf { it.used }
            val total = known.sumOf { it.limit ?: 0L }
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Panel).padding(16.dp)) {
                val n = Vault.places.size
                Words(if (n == 0) "Nothing connected yet" else "Your $n place" + (if (n == 1) "" else "s"), 13, K.Soft)
                Words(if (total > 0) Vault.size(used) + " of " + Vault.size(total) + " used" else "Choose a section to give it a place", 20, bold = true)
                Spacer(Modifier.height(10.dp))
                Meter(if (total > 0) used.toFloat() / total else 0f, K.Accent, 10)
                if (total > 0) { Spacer(Modifier.height(8.dp)); Words(Vault.size((total - used).coerceAtLeast(0)) + " free", 13, K.Soft) }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Section.entries.toList()) { section -> Tile(section) { onOpen(section) } }
            }
        }
    }

    @Composable
    private fun Tile(section: Section, onClick: () -> Unit) {
        val home = Vault.homeOf(section)
        val q = home?.let { Vault.quotas[it.id] }
        val room = Vault.room(section, 0)
        val warn = room == Room.NEARLY_FULL || room == Room.FULL
        val colour = Color(section.colour)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (home == null) K.Page else K.Panel)
                .then(if (home == null) Modifier.border(2.dp, K.Raised, RoundedCornerShape(16.dp)) else if (warn) Modifier.border(2.dp, K.Warn, RoundedCornerShape(16.dp)) else Modifier)
                .clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(colour))
            Words(section.title, 17, bold = true, lines = 1)
            if (home == null) Words("+ Choose its place", 13, K.Accent, bold = true)
            else {
                Words(if (warn) (q!!.fraction * 100).toInt().toString() + "% full · " + home.label else home.label, 12, if (warn) K.Warn else K.Soft, lines = 1)
                if (q?.limit != null) Meter(q.fraction, if (warn) K.Warn else colour)
            }
        }
    }

    /** One section's files, from every place, newest first; new ones go to the section's own place. */
    @Composable
    private fun Files(section: Section, asks: Asks, onBack: () -> Unit, onMove: () -> Unit) {
        val home = Vault.homeOf(section)
        val files = Library.of(section)
        Column(Modifier.fillMaxSize()) {
            Title(section.title, onBack)
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onMove)) {
                    Words(home?.let { "Lives in " + it.label } ?: "No place yet", 13, K.Soft, lines = 1)
                    Words("Change", 12, K.Accent, bold = true, lines = 1)
                }
                if (home != null) Button("+ Add files", main = true) { asks.pickFiles(section) }
            }
            if (section.coming.isNotEmpty()) Words(section.coming + ".", 12, K.Soft, lines = 2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Spacer(Modifier.height(8.dp))
            when {
                files.isEmpty() && Work.reading -> Words("Reading your places…", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
                files.isEmpty() -> Words(if (home == null) "Choose a place first." else "Nothing here yet. Tap Add files to put some in.", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
                else -> ItemList(files, showSection = false)
            }
        }
    }

    /** Everything in every place: search as you type, narrow to one section, put in order. */
    @Composable
    private fun LibraryScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var query by remember { mutableStateOf("") }
        var section by remember { mutableStateOf<Section?>(null) }
        var order by remember { mutableStateOf(Library.Order.NEWEST) }
        val labels = Vault.places.associate { it.id to it.label }
        val found = Library.search(Library.items.toList(), query, section, labels, order)
        Column(Modifier.fillMaxSize()) {
            Title("Search everything")
            Column(Modifier.padding(horizontal = 20.dp)) {
                Field("", query, "Name, kind of file or place: \"tax pdf\"") { query = it }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", section == null) { section = null }
                    for (s in Section.entries) Chip(s.title, section == s) { section = if (section == s) null else s }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Words(found.size.toString() + " file" + (if (found.size == 1) "" else "s") + if (Work.reading) " · reading your places…" else "", 12, K.Soft, lines = 1, modifier = Modifier.weight(1f))
                    for (o in Library.Order.entries) {
                        Words(o.title, 12, if (o == order) K.Accent else K.Soft, bold = o == order, lines = 1, modifier = Modifier.clickable { order = o }.padding(horizontal = 6.dp, vertical = 8.dp))
                    }
                }
                if (!Work.reading) Words("Read again", 12, K.Accent, bold = true, lines = 1, modifier = Modifier.clickable { scope.launch { Work.readLibrary(context) } }.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(6.dp))
            if (Vault.places.isEmpty()) Words("Connect a place first, in Places.", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
            else if (found.isEmpty() && !Work.reading) Words(if (query.isBlank()) "Nothing in your places yet." else "Nothing matches.", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
            else ItemList(found, showSection = true)
        }
    }

    @Composable
    private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
        Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) K.Accent else K.Panel).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Words(text, 13, if (on) Color(0xFF111111) else K.Ink, bold = on, lines = 1)
        }
    }

    /** Files, each opening with a tap; ⋯ shows Open and Move to the bin. */
    @Composable
    private fun ItemList(files: List<Store.Item>, showSection: Boolean) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var holding by remember { mutableStateOf<Store.Item?>(null) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.placeId + "|" + it.id }) { item ->
                val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(item.modifiedMs))
                val where = Vault.place(item.placeId)?.label ?: ""
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel)
                        .clickable { if (holding == item) holding = null else scope.launch { Work.open(context, item) } }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showSection) { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(item.section.colour))); Spacer(Modifier.width(8.dp)) }
                        Words(item.name, 15, bold = true, modifier = Modifier.weight(1f))
                        Words("⋯", 20, K.Soft, lines = 1, modifier = Modifier.clickable { holding = if (holding == item) null else item }.padding(start = 12.dp, end = 4.dp))
                    }
                    Words(Vault.size(item.size) + " · " + date + " · " + (if (showSection) item.section.title + " · " else "") + "in " + where, 12, K.Soft, lines = 1)
                    if (holding == item) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button("Open", main = true) { holding = null; scope.launch { Work.open(context, item) } }
                            Button("Move to the bin") { holding = null; scope.launch { Work.bin(context, item) } }
                        }
                    }
                }
            }
        }
    }

    /** Every place connected: how full, what lives there, and anything it needs. */
    @Composable
    private fun Places(asks: Asks, onAdd: () -> Unit, onChoose: (Section) -> Unit) {
        var confirm by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            Title("Places")
            Words("The storage you own and connected. Each section lives in one of them.", 13, K.Soft, lines = 2, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Vault.places.toList(), key = { it.id }) { p ->
                    val q = Vault.quotas[p.id]
                    val sections = Vault.sectionsIn(p.id)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).clickable { confirm = if (confirm == p.id) null else p.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Words(p.kind.title, 12, K.Soft, lines = 1)
                        Row {
                            Words(p.label, 15, bold = true, lines = 1, modifier = Modifier.weight(1f))
                            if (q?.limit != null) Words(Vault.size(q.used) + " of " + Vault.size(q.limit), 13, K.Soft, lines = 1)
                        }
                        Words(if (sections.isEmpty()) "No section lives here" else sections.joinToString { it.title } + " live" + (if (sections.size == 1) "s" else "") + " here", 12, K.Soft)
                        if (q?.limit != null) Meter(q.fraction, if (q.fraction >= Vault.NEARLY) K.Warn else K.KueBlue)
                        if (p.id in Work.signInAgain) Button("Sign in again", main = true) { asks.signInAgain(p.email) }
                        Work.unreachable[p.id]?.let { Words(it, 12, K.Warn, lines = 3) }
                        if (confirm == p.id) {
                            Words("Taking it out of KueVault leaves its files where they are." + if (sections.isNotEmpty()) " The sections living here will need a new place." else "", 12, K.Soft, lines = 3)
                            Button("Take this place out") { confirm = null; Vault.disconnect(p.id) }
                        }
                    }
                }
                item {
                    val homeless = Section.entries.filter { Vault.homeOf(it) == null }
                    if (homeless.isNotEmpty() && Vault.places.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Words("Still to give a place", 13, K.Soft)
                        for (s in homeless) Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).clickable { onChoose(s) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(s.colour)))
                            Spacer(Modifier.width(12.dp))
                            Words(s.title, 15, bold = true, modifier = Modifier.weight(1f))
                            Words("Choose", 13, K.Accent, bold = true)
                        }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) { Button("+ Connect a place", main = true, modifier = Modifier.fillMaxWidth(), onClick = onAdd) }
        }
    }

    /** Which place a section lives in: one of the places already connected, or a new one. */
    @Composable
    private fun ChooseHome(section: Section, onPick: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Where should " + section.title + " live?", onBack)
            Words("New " + section.title.lowercase() + " go there. Anything already put somewhere else stays where it is and still shows.", 14, K.Soft, lines = 3, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = Vault.homes[section]
                for (p in Vault.places) {
                    val q = Vault.quotas[p.id]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).then(if (p.id == now) Modifier.border(2.dp, K.Accent, RoundedCornerShape(14.dp)) else Modifier).clickable { onPick(p.id) }.padding(14.dp)) {
                        Words(p.kind.title + if (p.id == now) " · now" else "", 12, K.Soft, lines = 1)
                        Words(p.label, 15, bold = true, lines = 1)
                        if (q?.limit != null) Words(Vault.size(q.free) + " free", 12, K.Soft, lines = 1)
                    }
                }
                Button("+ Connect a new place", main = true, modifier = Modifier.fillMaxWidth(), onClick = onAdd)
            }
        }
    }

    /** What kind of place to connect. */
    @Composable
    private fun AddPlace(forSection: Section?, onGoogle: () -> Unit, onHome: () -> Unit, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Connect a place", onBack)
            Words("Storage you own. More than one Google account is fine: each is its own place.", 14, K.Soft, lines = 3, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (kind in PlaceKind.entries) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(if (kind.ready) K.Panel else K.Page)
                            .then(if (kind.ready) Modifier.clickable { if (kind == PlaceKind.GOOGLE) onGoogle() else onHome() } else Modifier.border(2.dp, K.Raised, RoundedCornerShape(14.dp)))
                            .padding(14.dp),
                    ) {
                        Words(kind.title, 16, if (kind.ready) K.Ink else K.Soft, bold = true)
                        Words(
                            when (kind) {
                                PlaceKind.GOOGLE -> "The phone asks which Google account"
                                PlaceKind.HOME -> "A shared folder on your computer or network drive, over the home Wi-Fi"
                                else -> kind.coming
                            },
                            12, K.Soft, lines = 2,
                        )
                    }
                }
            }
        }
    }

    /** A home computer's details: its name, the shared folder, and the computer's own sign-in. */
    @Composable
    private fun HomeForm(forSection: Section?, onDone: () -> Unit, onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var host by remember { mutableStateOf("") }
        var share by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var saying by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Home computer or network drive", onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words("Share a folder first. On Windows: right-click the folder › Properties › Sharing › Share. On a network drive: its own settings page, under shared folders. Then type in:", 13, K.Soft, lines = 4)
                Field("Computer's name or address", host, "DESKTOP-KQ or 192.168.1.20") { host = it }
                Field("Shared folder's name", share, "Vault") { share = it }
                Field("The computer's sign-in name", user, "kevin") { user = it }
                Field("The computer's password", password, "", secret = true) { password = it }
                Words("The password stays on this phone, locked by the phone itself.", 12, K.Soft, lines = 2)
                if (saying.isNotEmpty()) Words(saying, 14, K.Warn, lines = 4)
                Button(if (busy) "Trying…" else "Connect", main = true, modifier = Modifier.fillMaxWidth()) {
                    if (busy) return@Button
                    busy = true
                    saying = ""
                    scope.launch {
                        val said = Work.connectHome(context, host, share, user, password)
                        busy = false
                        if (said == "Connected") {
                            val place = Vault.places.lastOrNull { it.kind == PlaceKind.HOME && it.host.equals(host.trim().removePrefix("\\\\").removePrefix("smb://").trimEnd('/', '\\'), true) }
                            if (forSection != null && place != null) Vault.setHome(forSection, place.id)
                            Work.doing = (place?.label ?: "The computer") + " connected." + (forSection?.let { " " + it.title + " lives there now." } ?: "")
                            onDone()
                        } else saying = said
                    }
                }
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, example: String, secret: Boolean = false, onChange: (String) -> Unit) {
        if (label.isNotEmpty()) Words(label, 13, K.Soft, lines = 1)
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && example.isNotEmpty()) Words(example, 15, K.Soft.copy(alpha = 0.5f), lines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = K.Ink, fontSize = 15.sp, fontFamily = K.Poppins),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(K.Accent),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun Settings(onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var forget by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Settings", onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Words("KueVault " + BuildConfig.VERSION_NAME, 15, bold = true)
                Button("Check for updates", modifier = Modifier.fillMaxWidth()) { scope.launch { Work.doing = Updates.updateByHand(context) { Work.doing = it } } }
                Button("Copy report for Claude", modifier = Modifier.fillMaxWidth()) {
                    val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("KueVault report", Report.text(context)))
                    Work.doing = "Report copied. Paste it into the chat."
                }
                Spacer(Modifier.height(6.dp))
                Words("Privacy", 15, bold = true)
                Words("In each place, KueVault can only see the files it puts in its own KueVault folder. Your files go straight between this phone and your own places. There is no KueVault server, and nothing is sent anywhere else. Home computer passwords stay on this phone, locked.", 13, K.Soft, lines = 8)
                Button(if (forget) "Tap again to forget everything on this phone" else "Forget everything on this phone", modifier = Modifier.fillMaxWidth()) {
                    if (!forget) forget = true else { forget = false; Vault.forgetAll(); Library.forgetAll(); Work.doing = "Forgotten. Your files are still in your places." }
                }
                Words("Takes every place out of KueVault on this phone. The files stay where they are.", 12, K.Soft, lines = 3)
            }
        }
    }
}
