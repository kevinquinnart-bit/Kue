package art.kquinn.kuesounds

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Open song in (Kevin, 26 November 2026, from Shazam's own "Open song in"): one tap takes a song to YouTube, YouTube
 * Music, Spotify or Apple Music. Each opens in its app when it is on the phone, otherwise its website. YouTube goes
 * straight to the artist's own video; the others open a search for the song. KueSounds pauses first.
 */
object OpenSong {
    enum class Place(val label: String, val pkg: String) {
        YOUTUBE("YouTube", "com.google.android.youtube"),
        YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music"),
        SPOTIFY("Spotify", SpotifyLink.SPOTIFY_APP),
        APPLE_MUSIC("Apple Music", "com.apple.android.music"),
    }

    // Drawn for Kue, not the services' own logos.
    private const val ROUND_PLAY = "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM10 8.5l5.5 3.5-5.5 3.5z"

    private fun icon(p: Place) = when (p) {
        Place.YOUTUBE -> KueSettings.Icons.VIDEO
        Place.YOUTUBE_MUSIC -> ROUND_PLAY
        Place.SPOTIFY -> KueSettings.Icons.MUSIC
        Place.APPLE_MUSIC -> KueSettings.Icons.DISC
    }

    /** Opens [url] in [pkg]'s app, or the website when the app isn't on the phone. */
    private fun go(context: Context, url: String, pkg: String?, web: String = url) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (pkg != null) setPackage(pkg) }
        runCatching { context.startActivity(app) }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** The four places, two to a row, under "Open song in". */
    @Composable
    fun Choices(artist: String, title: String) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var finding by remember { mutableStateOf(false) }
        val words = listOf(artist.takeIf { it != Library.UNKNOWN_ARTIST }.orEmpty(), title).filter { it.isNotBlank() }.joinToString(" ")
        val q = URLEncoder.encode(words, "UTF-8")
        val open = { p: Place ->
            Playing.pause()
            when (p) {
                Place.YOUTUBE -> if (!finding) {
                    finding = true
                    scope.launch {
                        val found = withContext(Dispatchers.IO) { runCatching { YouTube.find(artist, title) }.getOrDefault(emptyList()) }
                        finding = false
                        val id = found.firstOrNull()?.id
                        if (id != null) {
                            Scanner.note("YouTube: " + title + " → " + found.first().title + " (" + found.first().channel + "), opened in the YouTube app")
                            go(context, "https://www.youtube.com/watch?v=$id", p.pkg)
                        } else go(context, "https://www.youtube.com/results?search_query=$q", p.pkg)
                    }
                }
                Place.YOUTUBE_MUSIC -> go(context, "https://music.youtube.com/search?q=$q", p.pkg)
                Place.SPOTIFY -> SpotifyLink.openSearch(context, words)
                Place.APPLE_MUSIC -> go(context, "https://music.apple.com/gb/search?term=$q", p.pkg)
            }
            Unit
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Ui.Words("Open song in", 16, bold = true)
            Place.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        Row(
                            Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(K.Raised)
                                .clickable(onClickLabel = "Open in " + p.label) { open(p) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (p == Place.YOUTUBE && finding) CircularProgressIndicator(color = K.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                else KueSettings.Icon(icon(p), 22.dp, K.Ink, 2f)
                            }
                            Ui.Words(p.label, 15, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
