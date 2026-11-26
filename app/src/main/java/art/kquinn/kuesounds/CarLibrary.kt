package art.kquinn.kuesounds

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * KueSounds in the car (Kevin, 26 November 2026: "optimise for car play"). Android Auto shows this list on the car's
 * screen: Shuffle all, Favourites, Recently added, Playlists, Albums and Artists, each album with its cover. Tapping a
 * song plays it and carries on through the rest of its album or list. "Play ... on KueSounds" by voice, and the car's
 * own search, look through song names, artists and albums.
 *
 * Apple CarPlay needs an iPhone app; KueSounds is an Android app, so it isn't there.
 */
class CarLibrary(private val context: Context) : MediaLibraryService.MediaLibrarySession.Callback {
    private val root = "root"
    private val car = "com.google.android.projection.gearhead"

    private fun folder(id: String, title: String, art: File? = null, subtitle: String = "") = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle.ifEmpty { null }).setArtworkUri(shared(art))
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    /** A song in a list: playing it plays the list from there. */
    private fun song(parent: String, t: Track) = MediaItem.Builder()
        .setMediaId("car|$parent|" + t.id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                .setArtworkUri(shared(Scanner.artForTrack(t))).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
        ).build()

    /** A cover the car's screen can read: shared through KueSounds' own file door, for Android Auto only. */
    private fun shared(file: File?): Uri? {
        if (file == null || !file.exists()) return null
        return runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            context.grantUriPermission(car, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        }.getOrNull()
    }

    private fun code(s: String) = android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    private fun decode(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE)) }.getOrDefault("")

    private fun recent(): List<Album> = Scanner.view.albums.sortedByDescending { al -> al.tracks.maxOfOrNull { it.modified }.orEmpty() }.take(100)

    /** What is inside each folder on the car's screen. */
    private fun children(parent: String): List<MediaItem> {
        val view = Scanner.view
        return when {
            parent == root -> listOf(
                MediaItem.Builder().setMediaId("shuffle").setMediaMetadata(
                    MediaMetadata.Builder().setTitle("Shuffle all your music").setIsBrowsable(false).setIsPlayable(true).build(),
                ).build(),
                folder("fav", "Favourites"),
                folder("recent", "Recently added"),
                folder("playlists", "Playlists"),
                folder("albums", "Albums"),
                folder("artists", "Artists"),
            )
            parent == "fav" -> favourites().map { song("fav", it) }
            parent == "recent" -> recent().map { folder("album:" + code(it.key), it.title, Scanner.artFor(it), it.artist) }
            parent == "playlists" -> Playlists.all().map { folder("pl:" + it.id, it.name, subtitle = count(it.ids.size, "song")) }
            parent == "albums" -> view.albums.map { folder("album:" + code(it.key), it.title, Scanner.artFor(it), it.artist) }
            parent == "artists" -> view.artists.map { a -> folder("artist:" + code(a.name), a.name, Scanner.artistPicture(a.name) ?: a.albums.firstNotNullOfOrNull { Scanner.artFor(it) }) }
            parent.startsWith("artist:") -> view.artists.firstOrNull { it.name == decode(parent.removePrefix("artist:")) }?.albums
                ?.map { folder("album:" + code(it.key), it.title, Scanner.artFor(it), it.year.takeIf { y -> y > 0 }?.toString().orEmpty()) }.orEmpty()
            else -> songsOf(parent).map { song(parent, it) }
        }
    }

    private fun favourites(): List<Track> {
        val ids = Sync.favouriteIds()
        return Scanner.view.songs.filter { it.id in ids }
    }

    /** The songs of a list, in order: an album, a playlist, the favourites. */
    private fun songsOf(parent: String): List<Track> {
        val byId = Scanner.data.tracks.associateBy { it.id }
        return when {
            parent == "fav" -> favourites()
            parent.startsWith("album:") -> Scanner.view.albums.firstOrNull { it.key == decode(parent.removePrefix("album:")) }?.tracks.orEmpty()
            parent.startsWith("pl:") -> Playlists.get(parent.removePrefix("pl:"))?.ids?.mapNotNull { byId[it] }.orEmpty()
            else -> emptyList()
        }
    }

    /** Songs, albums and artists whose names hold the words asked for, by voice or the car's search. */
    private fun search(words: String): List<Track> {
        val q = Library.matchName(words)
        if (q.isEmpty()) return emptyList()
        val songs = Scanner.view.songs
        return (songs.filter { Library.matchName(it.title).contains(q) } +
            songs.filter { Library.matchName(it.artist).contains(q) } +
            songs.filter { Library.matchName(it.album).contains(q) }).distinctBy { it.id }.take(200)
    }

    override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(root, "KueSounds"), params))

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
        page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val all = children(parentId)
        val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
    }

    override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
        val t = Scanner.data.tracks.firstOrNull { it.id == mediaId.substringAfterLast('|') }
        return Futures.immediateFuture(if (t != null) LibraryResult.ofItem(Playing.item(t), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
    }

    override fun onSearch(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, search(query).size, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
        page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val all = search(query).map { song("search:" + code(query), it) }
        val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(all.size.toLong()).toInt()
        return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params))
    }

    /**
     * What to play. KueSounds' own screens send songs ready to play; the car sends a song's name from the list (played
     * with the rest of its list after it), Shuffle all, or words said aloud.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val one = mediaItems.singleOrNull()
        if (one != null && one.localConfiguration == null) {
            val id = one.mediaId
            val asked = one.requestMetadata.searchQuery
            val list: List<Track>
            var start = 0
            when {
                id == "shuffle" -> list = Scanner.view.songs.shuffled()
                id.startsWith("car|") -> {
                    val parent = id.substringAfter("car|").substringBeforeLast('|')
                    val trackId = id.substringAfterLast('|')
                    list = if (parent.startsWith("search:")) search(decode(parent.removePrefix("search:"))) else songsOf(parent)
                    start = list.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                }
                !asked.isNullOrBlank() -> list = search(asked).ifEmpty { Scanner.view.songs.shuffled() }
                else -> list = listOfNotNull(Scanner.data.tracks.firstOrNull { it.id == id })
            }
            if (list.isNotEmpty()) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(list.map { Playing.item(it) }, start, 0L))
        }
        return Futures.transform(onAddMediaItems(mediaSession, controller, mediaItems), { MediaSession.MediaItemsWithStartPosition(it, startIndex, startPositionMs) }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
    }

    /** Songs added to what is playing: those ready to play as they are, the rest found by their name. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        val byId = Scanner.data.tracks.associateBy { it.id }
        val out = mediaItems.mapNotNull { m ->
            if (m.localConfiguration != null) m else byId[m.mediaId.substringAfterLast('|')]?.let { Playing.item(it) }
        }.toMutableList()
        return Futures.immediateFuture(out)
    }

    private fun count(n: Int, word: String) = String.format("%,d", n) + " " + word + if (n == 1) "" else "s"
}
