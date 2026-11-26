package art.kquinn.kuesounds

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Name that song (Kevin, 26 November 2026), opened from the microphone button beside Settings on the main screen.
 * Two ways in: Around me (the microphone) and On this phone (what another app is playing). The song named shows with
 * its cover, album, year, label and kind of music, and can be played from your music, on YouTube or in Spotify.
 */
object NameUi {
    @Composable
    fun Page(back: () -> Unit) {
        val context = LocalContext.current
        Listen.init(context)
        // What to do once the microphone permission is given.
        var then by remember { mutableStateOf<Listen.From?>(null) }
        var refused by remember { mutableStateOf(false) }
        val shareSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == Activity.RESULT_OK && d != null) CaptureService.start(context, r.resultCode, d)
        }
        val start = { f: Listen.From ->
            if (f == Listen.From.AROUND) Listen.aroundMe(context)
            else runCatching { shareSound.launch(CaptureService.question(context)) }
            Unit
        }
        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
            refused = !yes
            if (yes) then?.let(start)
            then = null
        }
        val begin = { f: Listen.From ->
            if (Listen.canHear(context)) start(f) else { then = f; askMic.launch(android.Manifest.permission.RECORD_AUDIO) }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ui.RoundButton(Glyph.BACK, "Back", onClick = back)
                    Spacer(Modifier.width(12.dp))
                    Ui.Words("Name that song", 22, bold = true)
                }
            }
            if (Listen.listening) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = K.Accent, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                        Ui.Words(
                            (if (Listen.asking) "Asking Shazam…" else "Listening… " + Listen.heard + " s") +
                                (if (Listen.from == Listen.From.PHONE) " (this phone)" else " (around me)"),
                            16, K.Accent,
                        )
                    }
                    Ui.Button("Stop", main = false, icon = KueSettings.Icons.CROSS, modifier = Modifier.fillMaxWidth()) { Listen.stop() }
                }
            } else {
                item { Way(KueSettings.Icons.MIC, "Around me", "Music playing in the room, the car or a shop.") { begin(Listen.From.AROUND) } }
                item { Way(KueSettings.Icons.PHONE, "On this phone", "What another app on this phone is playing. Android asks first.") { begin(Listen.From.PHONE) } }
            }
            if (refused) item { Ui.Words("KueSounds needs the microphone for this. Allow it in the phone's Settings › Apps › KueSounds › Permissions.", 14, K.Accent, lines = 4) }
            if (Listen.message.isNotEmpty() && !Listen.listening) item { Ui.Words(Listen.message, 15, K.Accent, lines = 4) }
            Listen.found?.let { song -> item { Result(song) } }
            if (Listen.named.isNotEmpty()) {
                item { Ui.Words("Named before", 18, bold = true, modifier = Modifier.padding(top = 6.dp)) }
                items(Listen.named, key = { it.at }) { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Panel).clickable { Listen.found = n.song }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(K.Raised), contentAlignment = Alignment.Center) {
                            if (n.song.cover != null) AsyncImage(model = n.song.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Sign(Glyph.NOTE, 24.dp, K.Soft)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Ui.Words(n.song.title, 15, bold = true)
                            Ui.Words(n.song.artist, 13, K.Soft)
                            Ui.Words(when_(n.at) + " · " + n.from, 12, K.Soft)
                        }
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Take off the list") { Listen.forget(n) }, contentAlignment = Alignment.Center) {
                            KueSettings.Icon(KueSettings.Icons.CROSS, 18.dp, K.Soft, 2f)
                        }
                    }
                }
            }
        }
    }

    /** One of the two ways to listen: a big row with its drawn icon. */
    @Composable
    private fun Way(icon: String, name: String, about: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Panel).clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(K.Accent), contentAlignment = Alignment.Center) {
                KueSettings.Icon(icon, 30.dp, K.Page, 2.2f)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Ui.Words(name, 18, bold = true)
                Ui.Words(about, 13, K.Soft, lines = 2)
            }
        }
    }

    /** The song named: big cover, its details, and where to play it. */
    @Composable
    private fun Result(song: SoundMatch.Found) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val open = { url: String ->
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Unit
        }
        val words = song.artist + " " + song.title
        val yours = remember(song) {
            Scanner.view.songs.firstOrNull { Library.matchName(it.title) == Library.matchName(song.title) && Library.matchName(it.artist) == Library.matchName(song.artist) }
                ?: Scanner.view.songs.firstOrNull { Library.matchName(it.title) == Library.matchName(song.title) }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(K.Panel), contentAlignment = Alignment.Center) {
                if (song.cover != null) AsyncImage(model = song.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Sign(Glyph.NOTE, 72.dp, K.Soft)
            }
            Ui.Words(song.title, 22, bold = true, lines = 2)
            Ui.Words(song.artist, 17, K.Accent, lines = 2)
            val facts = buildList {
                song.albums.firstOrNull()?.let { add("Album" to it) }
                if (song.year.isNotBlank()) add("Released" to song.year)
                if (song.label.isNotBlank()) add("Label" to song.label)
                if (song.genre.isNotBlank()) add("Kind of music" to song.genre)
            }
            for ((label, value) in facts) Row {
                Ui.Words(label, 14, K.Soft, modifier = Modifier.width(120.dp))
                Ui.Words(value, 14, lines = 2, modifier = Modifier.weight(1f))
            }
            if (yours != null) Ui.Button("Play it from your music", glyph = Glyph.PLAY, modifier = Modifier.fillMaxWidth()) { Playing.play(listOf(yours), 0) }
            else Ui.Words("Not in your music.", 14, K.Soft)
            var finding by remember { mutableStateOf(false) }
            Ui.Button(if (finding) "Finding the video…" else "Watch it on YouTube", main = yours == null, icon = KueSettings.Icons.VIDEO, modifier = Modifier.fillMaxWidth()) {
                if (finding) return@Button
                finding = true
                scope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching { YouTube.find(song.artist, song.title) }.getOrDefault(emptyList()) }
                    finding = false
                    if (found.isEmpty()) open("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(words, "UTF-8"))
                    else Ui.watch = Screen.YouTubePlayer("https://www.youtube.com/watch?v=" + found.first().id, found.take(5).map { it.id }, song.title + " · " + song.artist)
                }
            }
            Ui.Button("Find it in Spotify", main = false, icon = KueSettings.Icons.MUSIC, modifier = Modifier.fillMaxWidth()) { SpotifyLink.openSearch(context, words) }
            Ui.Button("Look it up on the web", main = false, icon = KueSettings.Icons.WEB, modifier = Modifier.fillMaxWidth()) {
                open("https://www.google.com/search?q=" + java.net.URLEncoder.encode(words, "UTF-8"))
            }
        }
    }

    private fun when_(at: Long): String = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(at))
}
