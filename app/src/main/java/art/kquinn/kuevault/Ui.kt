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
    data object Documents : Screen()
    data object Accounts : Screen()
    data object Settings : Screen()
    data class Coming(val section: Section) : Screen()
    data object PickSection : Screen()
}

/** What the screens ask the app to do that only the app can: the phone's own pickers and Google's page. */
class Asks(
    val addAccount: (Section) -> Unit,
    val signInAgain: (String) -> Unit,
    val pickFiles: () -> Unit,
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
        BackHandler(enabled = screen != Screen.Hub) { screen = if (screen == Screen.PickSection) Screen.Accounts else Screen.Hub }
        LaunchedEffect(Vault.accounts.size) { if (Vault.accounts.isNotEmpty()) Work.readSpace(context) }

        Column(Modifier.fillMaxSize().background(K.Page).safeDrawingPadding()) {
            Box(Modifier.weight(1f)) {
                when (val s = screen) {
                    Screen.Hub -> Hub(onOpen = { section -> screen = if (section.ready) Screen.Documents else if (Vault.forSection(section).isEmpty()) Screen.PickSection else Screen.Coming(section) }, onSettings = { screen = Screen.Settings })
                    Screen.Documents -> Documents(asks) { screen = Screen.Hub }
                    Screen.Accounts -> Accounts(asks, onAdd = { screen = Screen.PickSection })
                    Screen.Settings -> Settings { screen = Screen.Hub }
                    is Screen.Coming -> Coming(s.section) { screen = Screen.Hub }
                    Screen.PickSection -> PickSection(onPick = { section -> screen = Screen.Accounts; asks.addAccount(section) }, onBack = { screen = Screen.Accounts })
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
            val items = listOf("Hub" to Screen.Hub, "Photos" to Screen.Coming(Section.PHOTOS), "Mail" to Screen.Coming(Section.MAIL), "Accounts" to Screen.Accounts)
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
            val known = Vault.accounts.mapNotNull { Vault.quotas[it.email] }
            val used = known.sumOf { it.used }
            val total = known.sumOf { it.limit ?: 0L }
            Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Panel).padding(16.dp)) {
                val n = Vault.accounts.size
                Words(if (n == 0) "No accounts yet" else "Free space across $n account" + (if (n == 1) "" else "s"), 13, K.Soft)
                Words(if (total > 0) Vault.size(used) + " of " + Vault.size(total) + " used" else "Add a free account to start", 20, bold = true)
                Spacer(Modifier.height(10.dp))
                Meter(if (total > 0) used.toFloat() / total else 0f, K.Accent, 10)
                if (total > 0) { Spacer(Modifier.height(8.dp)); Words(Vault.size((total - used).coerceAtLeast(0)) + " left", 13, K.Soft) }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(Section.entries.toList()) { section -> Tile(section) { onOpen(section) } }
            }
        }
    }

    @Composable
    private fun Tile(section: Section, onClick: () -> Unit) {
        val accounts = Vault.forSection(section)
        val (used, total) = Vault.sectionSpace(section)
        val fraction = if (total > 0) used.toFloat() / total else 0f
        val nearlyFull = (accounts.isNotEmpty() && Vault.pick(section, 0) == null) || fraction >= 0.85f
        val colour = Color(section.colour)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (accounts.isEmpty()) K.Page else K.Panel)
                .then(if (accounts.isEmpty()) Modifier.border(2.dp, K.Raised, RoundedCornerShape(16.dp)) else if (nearlyFull) Modifier.border(2.dp, K.Warn, RoundedCornerShape(16.dp)) else Modifier)
                .clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(colour))
            Words(section.title, 17, bold = true, lines = 1)
            when {
                accounts.isEmpty() -> Words("+ Add an account", 13, K.Accent, bold = true)
                total > 0 -> {
                    Words(if (nearlyFull) (fraction * 100).toInt().toString() + "% full · add an account" else accounts.size.toString() + " account" + (if (accounts.size == 1) "" else "s") + " · " + Vault.size(used) + " of " + Vault.size(total), 12, if (nearlyFull) K.Warn else K.Soft)
                    Meter(fraction, if (nearlyFull) K.Warn else colour)
                }
                else -> Words(accounts.size.toString() + " account" + (if (accounts.size == 1) "" else "s"), 12, K.Soft)
            }
        }
    }

    @Composable
    private fun Coming(section: Section, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Title(section.title, onBack)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words(section.coming + ". Its accounts can be added now, in Accounts, so its space is ready.", 15, K.Soft, lines = 4)
                for (a in Vault.forSection(section)) Words("• " + a.email, 14)
            }
        }
    }

    @Composable
    private fun Documents(asks: Asks, onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var holding by remember { mutableStateOf<Drive.Item?>(null) }
        LaunchedEffect(Vault.forSection(Section.DOCUMENTS).size) { Work.readDocuments(context) }
        Column(Modifier.fillMaxSize()) {
            Title("Documents", onBack)
            val (used, total) = Vault.sectionSpace(Section.DOCUMENTS)
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Words(if (total > 0) Vault.size(used) + " of " + Vault.size(total) + " used" else "", 13, K.Soft, modifier = Modifier.weight(1f))
                Button("+ Add files", main = true) { asks.pickFiles() }
            }
            Spacer(Modifier.height(10.dp))
            when {
                Vault.forSection(Section.DOCUMENTS).isEmpty() -> Words("Documents has no account yet. Add one in Accounts.", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
                !Work.documentsRead -> Words("Reading your documents…", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
                Work.documents.isEmpty() -> Words("Nothing here yet. Tap Add files to put some in.", 15, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Work.documents, key = { it.email + it.id }) { item ->
                        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(item.modifiedMs))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel)
                                .clickable { if (holding == item) holding = null else scope.launch { Work.open(context, item) } }
                                .padding(14.dp),
                        ) {
                            Words(item.name, 15, bold = true)
                            Words(Vault.size(item.size) + " · " + date + " · in " + item.email, 12, K.Soft, lines = 1)
                            if (holding == item) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button("Open", main = true) { holding = null; scope.launch { Work.open(context, item) } }
                                    Button("Move to the bin") { holding = null; scope.launch { Work.bin(context, item) } }
                                }
                            } else {
                                Words("Tap to open · tap ⋯ for more", 11, K.Soft, lines = 1, modifier = Modifier.clickable { holding = item })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Accounts(asks: Asks, onAdd: () -> Unit) {
        var confirm by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize()) {
            Title("Accounts")
            val known = Vault.accounts.mapNotNull { Vault.quotas[it.email] }
            Words(Vault.accounts.size.toString() + " free account" + (if (Vault.accounts.size == 1) "" else "s") + (if (known.isNotEmpty()) " · " + Vault.size(known.sumOf { it.limit ?: 0L }) + " in all" else ""), 13, K.Soft, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (section in Section.entries) {
                    val list = Vault.forSection(section)
                    items(list, key = { it.email }) { a ->
                        val q = Vault.quotas[a.email]
                        val again = a.email in Work.signInAgain
                        val position = list.indexOf(a)
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Panel).clickable { confirm = if (confirm == a.email) null else a.email }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Words(a.email, 15, bold = true, lines = 1, modifier = Modifier.weight(1f))
                                if (q?.limit != null) Words(Vault.size(q.used) + " of " + Vault.size(q.limit), 13, K.Soft, lines = 1)
                            }
                            Words("For " + section.title + if (position > 0) ", when the one before is full" else "", 12, K.Soft)
                            if (q?.limit != null) Meter(q.fraction, if (q.fraction >= 0.85f) K.Warn else Color(section.colour))
                            if (again) Button("Sign in again", main = true) { asks.signInAgain(a.email) }
                            if (confirm == a.email) {
                                Words("Taking it out of KueVault leaves its files where they are, in the account.", 12, K.Soft, lines = 3)
                                Button("Take this account out") { confirm = null; Vault.remove(a.email) }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(20.dp)) { Button("+ Add an account", main = true, modifier = Modifier.fillMaxWidth(), onClick = onAdd) }
        }
    }

    @Composable
    private fun PickSection(onPick: (Section) -> Unit, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Title("Which section is it for?", onBack)
            Words("Each free account holds one kind of thing. Next, the phone asks which Google account.", 14, K.Soft, lines = 3, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (section in Section.entries) {
                    val n = Vault.forSection(section).size
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(K.Panel).clickable { onPick(section) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(section.colour)))
                        Spacer(Modifier.width(12.dp))
                        Words(section.title, 16, bold = true, modifier = Modifier.weight(1f))
                        Words(if (n == 0) "none yet" else "$n already", 13, K.Soft)
                    }
                }
            }
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
                Words("KueVault can only see the files it puts in your accounts, in each account's KueVault folder. Your files go straight between this phone and your own accounts. There is no KueVault server, and nothing is sent anywhere else.", 13, K.Soft, lines = 8)
                Button(if (forget) "Tap again to forget everything on this phone" else "Forget everything on this phone", modifier = Modifier.fillMaxWidth()) {
                    if (!forget) forget = true else { forget = false; Vault.forgetAll(); Work.documents.clear(); Work.documentsRead = false; Work.doing = "Forgotten. Your files are still in your accounts." }
                }
                Words("Takes every account out of KueVault on this phone. The files stay in your accounts.", 12, K.Soft, lines = 3)
            }
        }
    }
}
