package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers from Deezer, read from Deezer's real answers saved on 26 November 2026 for Kevin's own songs that had no
 * cover: Derek Ryan's "A Mother's Son" (no picture in MusicBrainz) and Nathan Carter's "Home To Donegal".
 */
class CoversTest {
    private fun text(name: String) = javaClass.getResourceAsStream("/covers/$name")!!.readBytes().decodeToString()

    @Test fun findsAMothersSon() {
        val hits = Covers.parseDeezerAlbums(text("deezer_album_mothers_son.json"))
        val hit = hits.first { Covers.same(it.album, "A Mothers Son") && Covers.sameArtist(it.artist, "Derek Ryan") }
        assertEquals("A Mother's Son", hit.album)
        assertTrue(hit.cover.startsWith("https://"))
    }

    @Test fun findsHomeToDonegalsAlbum() {
        val songs = Covers.parseDeezerSongs(text("deezer_song_donegal.json"))
        val hit = songs.filter { Covers.same(it.first, "Home to Donegal") && Covers.sameArtist(it.second.artist, "Nathan Carter") }.map { it.second }.first()
        assertEquals("Live At The Marquee Cork", hit.album)
    }

    @Test fun neverTakesAnotherArtist() {
        // Skerryvore's "Home to Donegal (Live at Floors Castle)" is in the same answer: not Nathan Carter's.
        val songs = Covers.parseDeezerSongs(text("deezer_song_donegal.json"))
        assertFalse(songs.any { it.second.artist == "Skerryvore" && Covers.sameArtist(it.second.artist, "Nathan Carter") })
        // Deezer names only Shane MacGowan for "Haunted"; the file says The Pogues & Sinead O'Connor: not taken by name.
        val haunted = Covers.parseDeezerSongs(text("deezer_song_haunted.json"))
        assertFalse(haunted.any { Covers.sameArtist(it.second.artist, "The Pogues & Sinead O'Connor") })
    }

    @Test fun artistsMatchOneByOne() {
        assertTrue(Covers.sameArtist("Shane MacGowan & Sinead O'Connor", "The Pogues & Sinéad O'Connor") || Covers.sameArtist("Shane MacGowan & Sinead O'Connor", "The Pogues & Sinead O'Connor"))
        assertTrue(Covers.same("A Mothers Son", "A Mother's Son"))
        assertFalse(Covers.same("Steam", "Stay Another Day"))
    }
}
