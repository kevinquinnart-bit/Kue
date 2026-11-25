package art.kquinn.kuetv

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
    data class KueApp(val name: String, val pkg: String, val download: String, val colour: Color, val logo: Int)

    val apps = listOf(
        KueApp("KueTV", "art.kquinn.kuetv", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuetv/KueTV.apk", Color(0xFF1D9E75), R.drawable.kue_app_tv),
        KueApp("KueSounds", "art.kquinn.kuesounds", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuesounds/KueSounds.apk", Color(0xFFEF9F27), R.drawable.kue_app_sounds),
        KueApp("KueVault", "art.kquinn.kuevault", "https://github.com/kevinquinnart-bit/Kue/releases/download/kuevault/KueVault.apk", Color(0xFF9B4DDB), R.drawable.kue_app_vault),
    )

    /** The eight groups' drawn icons, as lines on a 24 by 24 grid. Drawn for Kue, never stock icons. */
    object Icons {
        const val ACCOUNT = "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4.5 20c1.2-3.6 4-5.5 7.5-5.5s6.3 1.9 7.5 5.5"
        const val APP = "M5 4h14v16H5zM9.5 9v6M9.5 12l4-3M11 12l3 3"
        /** Look and feel: a picture in a wavy frame, from Kevin's drawing (24 November 2026). */
        const val LOOK = "M3 3C5 5 9 2.3 12 2.3S19 5 21 3C19 5 21.7 9 21.7 12S19 19 21 21C19 19 15 21.7 12 21.7S5 19 3 21C5 19 2.3 15 2.3 12S5 5 3 3z" +
            "M7.5 7.5h9v9h-9zM14.6 10a1.3 1.3 0 1 1-2.6 0 1.3 1.3 0 1 1 2.6 0M7.5 14.6c1.4-1.8 3-2 4.5-.6s3 1.3 4.5-.3"
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
        const val CROSS = "M6.5 6.5l11 11M17.5 6.5l-11 11"
        const val BOX = "M5 5h14v14H5z"
        const val BOX_TICK = "M5 5h14v14H5zM8 12.5l3 3 5-6"

        // Drawn for every screen of the Kue apps, in place of text signs and stock pictures (Kevin, 24 November 2026:
        // "Kue icons required in Sounds and Vault throughout"). MORE, DOT and PLAY are drawn filled.
        const val BACK = "M15 5l-7 7 7 7"
        const val MORE = "M6 10.6a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8zM12 10.6a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8zM18 10.6a1.4 1.4 0 1 1 0 2.8 1.4 1.4 0 0 1 0-2.8z"
        const val SEARCH = "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13zM15.2 15.2L20 20"
        const val HUB = "M4 11l8-7 8 7M6 9.5V20h12V9.5M10 20v-5h4v5"
        const val PLACES = "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"
        const val THINGS = "M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8"
        const val PHOTO = "M4 6h16v13H4zM8.5 11a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM4 16.5l5-4.5 4 3.5 3-2.5 4 3.5"
        const val MUSIC = "M9 18V5.5l10-2.5v13M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM19 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z"
        const val FILM = "M4 5h16v14H4zM8 5v14M16 5v14M4 9.5h4M4 14.5h4M16 9.5h4M16 14.5h4"
        const val DOCUMENT = "M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6"
        const val PLUS = "M12 5v14M5 12h14"
        const val DOT = "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z"
        const val PLAY_NEXT = "M4 6h11M4 11h11M4 16h6M14 13.5l6 3.5-6 3.5z"
        const val QUEUE = "M4 6h14M4 11h14M4 16h7M17 13.5v7M13.5 17h7"
        const val PLAYLIST = "M10 17V5.5l8-2M10 17a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM17.5 11v8M13.5 15h8"
        const val EDIT = "M4 20l1-4.5L15.5 5a2.1 2.1 0 0 1 3 3L8 18.5zM13.5 7l3 3"
        const val BIN = "M5 7h14M10 7V4.5h4V7M7 7l1 13h8l1-13M10.5 11v5.5M13.5 11v5.5"
        const val PHONE = "M7 3h10v18H7zM12 7v6.5M9.3 10.8L12 13.5l2.7-2.7M10.5 17.5h3"
        const val FIX = "M4 20L15 9M13 5.5l1-2.5 1 2.5 2.5 1-2.5 1-1 2.5-1-2.5-2.5-1zM18.5 12l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"
        const val OPEN = "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6"
        const val DISC = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"
        const val PLAY = "M8 5l11 7-11 7z"
        /** Sliders: the equaliser and sound settings. */
        const val EQUALISER = "M6 4v16M12 4v16M18 4v16M4 14h4M10 8h4M16 16h4"
        /** A crescent moon: the sleep timer. */
        const val SLEEP = "M19 14.5A7.5 7.5 0 0 1 9.5 5a7.5 7.5 0 1 0 9.5 9.5z"
        /** An "i" in a circle: details about something. */
        const val INFO = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v6M12 7.5v.5"
        /** How a list is shown: plain rows, rows with pictures, big covers. */
        const val VIEW_LIST = "M4 6h16M4 12h16M4 18h16"
        const val VIEW_PICTURES = "M4 4h5v5H4zM12 6.5h8M4 15h5v5H4zM12 17.5h8"
        const val VIEW_COVERS = "M4 4h7v7H4zM13 4h7v7h-7zM4 13h7v7H4zM13 13h7v7h-7z"
        /** A screen with a play mark: a video to watch. */
        const val VIDEO = "M3 6h18v12H3zM10 9.5l5 2.5-5 2.5z"
        /** A globe: the web. */
        const val WEB = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.6 3.7 5.6 3.7 9s-1.2 6.4-3.7 9c-2.5-2.6-3.7-5.6-3.7-9S9.5 5.6 12 3z"
        const val SHUFFLE = "M4 7h3c4 0 6 10 10 10h3M17.5 14l2.5 3-2.5 3M4 17h3c1.5 0 2.6-1.3 3.6-3M13.4 10c1-1.7 2.1-3 3.6-3h3M17.5 4l2.5 3-2.5 3"

        /** Profile pictures, drawn (they were emoji). Kept by the emoji each profile already has saved. */
        val pictures = linkedMapOf(
            "🙂" to ("Smile" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM8.5 14.5c1 1.4 2.2 2 3.5 2s2.5-.6 3.5-2M9 9.5v1M15 9.5v1"),
            "🦊" to ("Fox" to "M4 4l4 5h8l4-5-1 9-7 7-7-7zM9.5 12.5v.5M14.5 12.5v.5M11 16h2"),
            "🐼" to ("Panda" to "M12 20a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM5 8a2 2 0 1 1 3-2.5M19 8a2 2 0 1 0-3-2.5M8.5 11.5l2 1.5M15.5 11.5l-2 1.5M11 16h2"),
            "🦁" to ("Lion" to "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 2.5l2 2.2 3-.8.4 3 2.8 1.3-1.3 2.8 1.3 2.8-2.8 1.3-.4 3-3-.8-2 2.2-2-2.2-3 .8-.4-3-2.8-1.3 1.3-2.8-1.3-2.8 2.8-1.3.4-3 3 .8zM10 11v.5M14 11v.5M11 14h2"),
            "🐸" to ("Frog" to "M4 15c0-4 3.5-6 8-6s8 2 8 6-3.5 5-8 5-8-1-8-5zM8 6.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM16 6.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5zM8.5 15.5c2 1.3 5 1.3 7 0"),
            "🐙" to ("Octopus" to "M6 11a6 6 0 0 1 12 0v2H6zM7 13c0 3-2 4-3 5M10 13v5c0 1-1 2-2 2M14 13v5c0 1 1 2 2 2M17 13c0 3 2 4 3 5M10 9.5v.5M14 9.5v.5"),
            "🚀" to ("Rocket" to "M12 2.5c3 2 4.5 5.5 4.5 9.5l-2 4h-5l-2-4c0-4 1.5-7.5 4.5-9.5zM12 8a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM7.5 12.5L5 15v3l4.5-2M16.5 12.5L19 15v3l-4.5-2M10.5 18.5L12 21.5l1.5-3"),
            "⚽" to ("Football" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 8l3.3 2.4-1.3 3.8h-4l-1.3-3.8zM12 3v5M15.3 10.4l5-1.6M14 14.2l3 4.2M10 14.2l-3 4.2M8.7 10.4l-5-1.6"),
            "🎮" to ("Game" to "M7 8h10a4 4 0 0 1 4 4v3a3 3 0 0 1-5.4 1.8L14.5 15h-5l-1.1 1.8A3 3 0 0 1 3 15v-3a4 4 0 0 1 4-4zM7.5 11v3M6 12.5h3M15.5 12h.5M17.5 13.5h.5"),
            "🎸" to ("Guitar" to "M14 10l6.5-6.5M18.5 2.5l3 3M12.8 8.7a3.5 3.5 0 0 0-5.3.3c-.9 1.1-.4 2.6-1.6 3.4-1.4.9-3.4.9-3.4 3.4 0 2.7 2.4 5.2 5.2 5.2 2.5 0 2.5-2 3.4-3.4.8-1.2 2.3-.7 3.4-1.6a3.5 3.5 0 0 0 .3-5.3zM9 13.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z"),
            "🌟" to ("Star" to STAR),
            "🍕" to ("Pizza" to "M4 6c5-2.7 11-2.7 16 0L12 21zM5.5 8.5c4-1.8 9-1.8 13 0M10 10.5v.5M14 12v.5M11.5 15v.5"),
        )

        /**
         * A tick, cross, star or box written at the start of a line ("✓ working"), as a drawn icon and the words
         * left: (icon, filled, words). No icon when the line starts with none of them.
         */
        fun lead(text: String): Triple<String?, Boolean, String> {
            val marks = listOf("★" to (STAR to true), "✓" to (TICK to false), "✗" to (CROSS to false), "☑" to (BOX_TICK to false), "☐" to (BOX to false))
            for ((m, icon) in marks) if (text.startsWith("$m ")) return Triple(icon.first, icon.second, text.removePrefix(m).trimStart())
            return Triple(null, false, text)
        }
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

    /**
     * A section inside a group: its name as a row that opens in place, and closes again (Kevin, 25 November 2026: too
     * much scrolling). Closed until tapped, unless Search all settings opened it.
     */
    @Composable
    fun Section(title: String, open: Boolean, look: Look, onToggle: () -> Unit, content: @Composable () -> Unit) {
        val turn by animateFloatAsState(if (open) 90f else 0f, tween(220), label = "section")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.raised)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = title, onClick = onToggle).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Say(title, 15, look.ink, look, bold = true, modifier = Modifier.weight(1f))
                Box(Modifier.rotate(turn)) { Icon(Icons.CHEVRON, 16.dp, look.soft(), 2.2f) }
            }
            AnimatedVisibility(open, enter = expandVertically(tween(220)) + fadeIn(tween(220)), exit = shrinkVertically(tween(180)) + fadeOut(tween(140))) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            }
        }
    }

    /** One setting for Search all settings: the group it is in ([group] is the group's own id), its section if any, its name. */
    class Place(val group: String, val groupTitle: String, val section: String?, val label: String) {
        /** Where it lives, as Settings shows it: "Look and feel › Text size". */
        val trail: String get() = listOfNotNull(groupTitle, section).joinToString(" › ")
    }

    /** The settings among [all] whose name, section or group hold every word typed, those named for it first. */
    fun find(all: List<Place>, typed: String): List<Place> {
        val words = typed.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return all.filter { p -> val text = (p.label + " " + p.trail).lowercase(); words.all { it in text } }
            .sortedBy { p -> if (words.all { it in p.label.lowercase() }) 0 else 1 }.take(20)
    }

    /**
     * Search all settings (Kevin, 25 November 2026): a box at the top of Settings; each setting found shows where it
     * lives, and tapping it opens its group and section.
     */
    @Composable
    fun Search(query: String, all: List<Place>, look: Look, onQuery: (String) -> Unit, onPick: (Place) -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(look.panel).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.SEARCH, 20.dp, look.soft())
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Say("Search all settings", 15, look.soft(), look)
                androidx.compose.foundation.text.BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = look.ink, fontSize = 15.sp, fontFamily = look.font),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(look.accent), modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Box(Modifier.clickable(role = Role.Button, onClickLabel = "Clear") { onQuery("") }.padding(6.dp)) { Icon(Icons.CROSS, 16.dp, look.soft(), 2.2f) }
        }
        if (query.isNotBlank()) {
            val hits = find(all, query)
            if (hits.isEmpty()) Note("No setting has that word in it.", look)
            for (p in hits) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.raised)
                        .clickable(role = Role.Button, onClickLabel = p.label) { onPick(p) }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Say(p.label, 15, look.ink, look, bold = true)
                    Say("Settings › " + p.trail, 13, look.soft(), look)
                }
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
                // The app's own logo, as on its icon (Kevin, 24 November 2026: the proper logo, not a plain K).
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(app.logo), contentDescription = null, modifier = Modifier.size(38.dp),
                )
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
