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

    // Fix it by hand, 27 November 2026: whole finds, names, album, year and cover, from live answers saved that day.
    @Test fun appleSongsForFixByHand() {
        val got = Covers.parseItunesLookups(text("itunes_song_fureys.json"))
        assertEquals(3, got.size)
        val first = got.first()
        assertEquals("A Drop of Irish Rain (feat. Ronnie Drew and Finbar Furey)", first.title)
        assertEquals("Francie Conway", first.artist)
        assertEquals("The Green Album", first.album)
        assertEquals("2010", first.year)
        assertEquals(2, first.trackNo)
        assertTrue(first.big.endsWith("1000x1000bb.jpg"))
    }

    @Test fun deezerSongsForFixByHand() {
        val got = Covers.parseDeezerLookups(text("deezer_track_haunted.json"))
        assertEquals("Haunted", got.first().title)
        assertEquals("Shane MacGowan", got.first().artist)
        assertEquals("Haunted", got.first().album)
        assertTrue(got.first().big.isNotEmpty())
    }

    // A link to Wikipedia's page for the cover Kevin wanted (saved by him from his phone, 27 November 2026).
    @Test fun pictureFromAWikipediaPage() {
        val got = Covers.pictureAddress(text("wikipedia_file_haunted.html"), "https://en.wikipedia.org/wiki/File:Haunted_(The_Pogues_single_-_cover_art).jpg")
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/en/f/fc/Haunted_%28The_Pogues_single_-_cover_art%29.jpg?utm_source=en.wikipedia.org&utm_campaign=index&utm_content=thumbnail_unscaled",
            got,
        )
    }

    // Wikipedia's answer in the shape its search gives (written here from its documented form, not saved live).
    @Test fun wikipediaPagesWithPictures() {
        val json = """{"batchcomplete":true,"query":{"pages":[
            {"pageid":2,"ns":0,"title":"Haunted (Poe song)","index":2,"description":"song","original":{"source":"https://upload.wikimedia.org/wikipedia/en/a/ab/Poe.png","width":300,"height":300}},
            {"pageid":1,"ns":0,"title":"Haunted (The Pogues song)","index":1,"description":"1986 single by the Pogues","original":{"source":"https://upload.wikimedia.org/wikipedia/en/f/fc/Haunted_%28The_Pogues_single_-_cover_art%29.jpg","width":316,"height":316}},
            {"pageid":3,"ns":0,"title":"Haunted","index":3,"description":"Topics referred to by the same term"}]}}"""
        val got = Covers.parseWikipedia(json)
        assertEquals(2, got.size)
        assertEquals("Haunted (The Pogues song)", got[0].title)
        assertTrue(got[0].coverOnly)
        assertEquals("Wikipedia", got[0].from)
    }
}
