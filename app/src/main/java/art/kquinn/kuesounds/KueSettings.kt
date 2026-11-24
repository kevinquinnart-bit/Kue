package art.kquinn.kuesounds

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kue settings (v1, 24 Nov 2026): one settings layout for every Kue app. The same eight groups in the same
 * order with the same drawn icons: Account, the app itself, Look and feel, Notifications, Backup and sync,
 * Privacy and storage, Your Kue apps, Help and about. Each group opens in place, one at a time.
 *
 * How each kind of setting behaves, the same in every app:
 *  - 2 to 4 choices: side by side, a filled dot on the chosen one ([Choices]).
 *  - 5 or more choices: the row fans out underneath, one dot each ([FanOut]).
 *  - A size: dots that grow ([Scale]).
 *  - On or off: a switch in the app's colour ([Toggle]).
 *  - Something to do: a filled button (each app's own).
 *
 * This file is the same in KueTV, KueSounds and KueVault apart from its first line. Change it in all three.
 */
object KueSettings {
    /** An app's colours and font, handed to every part of the settings. */
    data class Look(
        val page: Color,
        val panel: Color,
        val raised: Color,
        val accent: Color,
        val onAccent: Color,
        val ink: Color,
        val soft: () -> Color,
        val font: FontFamily,
    )

    /** The Kue apps, for "Your Kue apps". */
    data class KueApp(val name: String, val pkg: String, val download: String, val colour: Color)

    val apps = listOf(
        KueApp("KueTV", "art.kquinn.kuetv", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuetv/KueTV.apk", Color(0xFF1D9E75)),
        KueApp("KueSounds", "art.kquinn.kuesounds", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuesounds/KueSounds.apk", Color(0xFFEF9F27)),
        KueApp("KueVault", "art.kquinn.kuevault", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuevault/KueVault.apk", Color(0xFF9B4DDB)),
    )

    /** The eight groups' drawn icons, as lines on a 24 by 24 grid. Drawn for Kue, never stock icons. */
    object Icons {
        const val ACCOUNT = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 20c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5"
        const val APP = "M5 4h14v16H5zM9.5 9v6M9.5 12l4-3M11 12l3 3"
        const val LOOK = "M12 3a9 9 0 1 0 0 18zM12 3a9 9 0 0 1 0 18"
        const val NOTICES = "M6 17V11a6 6 0 0 1 12 0v6l1.5 2h-15zM10 21h4"
        const val BACKUP = "M7 18a4.5 4.5 0 0 1-.5-9A6 6 0 0 1 18 8.5a4.5 4.5 0 0 1-.5 9.5M12 12v8M9 15l3-3 3 3"
        const val PRIVACY = "M12 3l7 3v5c0 4.5-3 8.5-7 10-4-1.5-7-5.5-7-10V6zM9.5 12l2 2 3.5-4"
        const val KUE = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
        const val HELP = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v6M12 7.5v.5"
        const val CHEVRON = "M9 5l7 7-7 7"
        const val GEAR = "M12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM12 2.5v3.5M12 18v3.5M2.5 12H6M18 12h3.5M5.3 5.3l2.5 2.5M16.2 16.2l2.5 2.5M5.3 18.7l2.5-2.5M16.2 7.8l2.5-2.5"
        const val TICK = "M5 12.5l4.5 4.5L19 7.5"
        const val STAR = "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 17l-5.2 2.7 1-5.9-4.3-4.1 5.9-.8z"
        const val MIC = "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21M9 21h6"
    }

    // ---- Look and feel, kept on this phone and in Android's backup ----------------------------------------------

    /** Text size, 0 to 4: 2 is normal. */
    var textStep by mutableIntStateOf(2)
        private set
    /** Stronger contrast: the soft grey text drawn nearly white. */
    var contrast by mutableStateOf(false)
        private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("kue-look", Context.MODE_PRIVATE)
        prefs = p
        textStep = p.getInt("textStep", 2).coerceIn(0, 4)
        contrast = p.getBoolean("contrast", false)
    }

    fun chooseTextStep(step: Int) {
        textStep = step.coerceIn(0, 4)
        prefs?.edit()?.putInt("textStep", textStep)?.apply()
    }

    fun chooseContrast(on: Boolean) {
        contrast = on
        prefs?.edit()?.putBoolean("contrast", on)?.apply()
    }

    /** How much bigger or smaller every word in the app is drawn. */
    val textScale: Float get() = floatArrayOf(0.85f, 0.92f, 1f, 1.12f, 1.25f)[textStep]

    // ---- The parts -------------------------------------------------------------------------------------------------

    @Composable
    fun Icon(path: String, size: Dp, colour: Color, width: Float = 1.8f, filled: Boolean = false) {
        val drawn = androidx.compose.runtime.remember(path) { PathParser().parsePathString(path).toPath() }
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension / 24f
            withTransform({ scale(s, s, pivot = Offset.Zero) }) {
                if (filled) drawPath(drawn, colour)
                drawPath(drawn, colour, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    @Composable
    private fun Say(text: String, size: Int, colour: Color, look: Look, bold: Boolean = false, lines: Int = 1, modifier: Modifier = Modifier) {
        Text(
            text, modifier = modifier, color = colour, fontSize = size.sp, fontFamily = look.font,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis,
            lineHeight = (size * 1.3f).sp,
        )
    }

    /** One of the eight groups: its icon, name and a line saying how it is set now. Tapping opens it in place. */
    @Composable
    fun Group(
        icon: String,
        title: String,
        summary: String,
        open: Boolean,
        look: Look,
        onToggle: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        val turn by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "chevron")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(look.panel)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(role = Role.Button, onClickLabel = title, onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(look.raised), contentAlignment = Alignment.Center) {
                    Icon(icon, 24.dp, look.accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Say(title, 17, look.ink, look, bold = true)
                    Say(summary, 13, look.soft(), look)
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.rotate(turn)) { Icon(Icons.CHEVRON, 18.dp, look.soft(), 2.2f) }
            }
            AnimatedVisibility(open, enter = expandVertically(tween(240)) + fadeIn(tween(240)), exit = shrinkVertically(tween(200)) + fadeOut(tween(160))) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp, top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }

    /** A small heading inside a group. */
    @Composable
    fun Heading(text: String, look: Look) {
        Say(text, 13, look.soft(), look, modifier = Modifier.padding(top = 6.dp))
    }

    /** One dot: filled when chosen. */
    @Composable
    private fun Dot(on: Boolean, look: Look, size: Dp = 18.dp) {
        Box(
            Modifier.size(size).clip(CircleShape).border(2.dp, if (on) look.accent else look.soft().copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (on) Box(Modifier.size(size * 0.48f).clip(CircleShape).background(look.accent)) }
    }

    /** 2 to 4 choices side by side, the chosen one's dot filled. */
    @Composable
    fun Choices(label: String, options: List<String>, chosen: Int, look: Look, onPick: (Int) -> Unit) {
        Heading(label, look)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, o ->
                Row(
                    Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).background(look.raised)
                        .clickable(role = Role.RadioButton, onClickLabel = o) { onPick(i) }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(i == chosen, look)
                    Spacer(Modifier.width(8.dp))
                    Say(o, 14, look.ink, look)
                }
            }
        }
    }

    /** 5 or more choices: one row saying the chosen one; tapped, the options fan out underneath, one dot each. */
    @Composable
    fun FanOut(label: String, options: List<String>, chosen: Int, open: Boolean, look: Look, onOpen: (Boolean) -> Unit, onPick: (Int) -> Unit) {
        val turn by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "fan")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp)).background(look.raised)
                .clickable(role = Role.Button, onClickLabel = label) { onOpen(!open) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Say(label, 15, look.ink, look, modifier = Modifier.weight(1f))
            Say(options.getOrElse(chosen) { "" }, 14, look.soft(), look)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.rotate(turn)) { Icon(Icons.CHEVRON, 16.dp, look.soft(), 2.2f) }
        }
        AnimatedVisibility(open, enter = expandVertically(tween(260)) + fadeIn(tween(260)), exit = shrinkVertically(tween(200)) + fadeOut(tween(160))) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
                options.forEachIndexed { i, o ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton, onClickLabel = o) { onPick(i); onOpen(false) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(i == chosen, look)
                        Spacer(Modifier.width(12.dp))
                        Say(o, 15, look.ink, look)
                    }
                }
            }
        }
    }

    /** A size: dots that grow, the chosen one filled. */
    @Composable
    fun Scale(label: String, steps: Int, chosen: Int, look: Look, onPick: (Int) -> Unit) {
        Heading(label, look)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.raised).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Say("Aa", 12, look.ink, look)
            for (i in 0 until steps) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClickLabel = "$label ${i + 1} of $steps") { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    val d = (10 + i * 4).dp
                    if (i == chosen) Box(Modifier.size(d).clip(CircleShape).background(look.accent))
                    else Box(Modifier.size(d).clip(CircleShape).border(2.dp, look.soft().copy(alpha = 0.55f), CircleShape))
                }
            }
            Say("Aa", 20, look.ink, look, bold = true)
        }
    }

    /** On or off: a switch in the app's colour, with a line saying what it does. */
    @Composable
    fun Toggle(label: String, detail: String, on: Boolean, look: Look, onChange: (Boolean) -> Unit) {
        val x by animateFloatAsState(if (on) 1f else 0f, tween(180), label = "switch")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(look.raised)
                .clickable(role = Role.Switch, onClickLabel = label) { onChange(!on) }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Say(label, 15, look.ink, look)
                if (detail.isNotEmpty()) Say(detail, 12, look.soft(), look, lines = 4)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(50.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) look.accent else look.soft().copy(alpha = 0.35f))) {
                Box(Modifier.offset(x = 3.dp + 20.dp * x, y = 3.dp).size(24.dp).clip(CircleShape).background(Color.White))
            }
        }
    }

    /** A plain line of information inside a group: a name and its value. */
    @Composable
    fun Fact(label: String, value: String, look: Look) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Say(label, 15, look.ink, look, modifier = Modifier.weight(1f))
            Say(value, 14, look.soft(), look)
        }
    }

    /** Words inside a group, as many lines as they need. */
    @Composable
    fun Note(text: String, look: Look, lines: Int = 8) {
        Say(text, 14, look.soft(), look, lines = lines)
    }

    /** Your Kue apps: the other Kue apps, Open when it is on this phone, Get when it is not. */
    @Composable
    fun KueApps(self: String, look: Look) {
        val context = LocalContext.current
        apps.filter { it.pkg != self }.forEach { app ->
            val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp)).background(look.raised)
                    .clickable(role = Role.Button, onClickLabel = (if (launch != null) "Open " else "Get ") + app.name) {
                        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(app.download))
                        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The app's own corner colour and its K, small.
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(app.colour), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        Say("K", 13, Color(0xFF0E1626), look, bold = true)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Say(app.name, 16, look.ink, look, bold = true, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(if (launch != null) look.accent else look.panel).padding(horizontal = 14.dp, vertical = 7.dp),
                ) { Say(if (launch != null) "Open" else "Get", 14, if (launch != null) look.onAccent else look.ink, look, bold = true) }
            }
        }
    }

    /** Opens this app's page in the phone's own settings, where its permissions are. */
    fun openPermissions(context: Context) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
