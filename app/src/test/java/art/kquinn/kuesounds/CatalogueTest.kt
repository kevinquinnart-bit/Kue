package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checks on choosing the right album, using real MusicBrainz answers saved on 20 Nov 2026 (in
 * test/resources/musicbrainz). No question is sent to MusicBrainz while these run.
 */
class CatalogueTest {
    private fun saved(name: String): String = javaClass.getResource("/musicbrainz/$name")!!.readText()

    @Test
    fun tidyAlbumNamesFromFolders() {
        assertEquals("Ropin' the Wind" to 2, Library.cleanAlbum("1 - Garth Brooks - Ropin' the Wind [Disc 2] (1991)", "Garth Brooks"))
        assertEquals("100 80s Hits" to 1, Library.cleanAlbum("100 80s Hits [Disc 1]", "Various Artists"))
        assertEquals("MeeTTechno Cr" to 0, Library.cleanAlbum("-+MeeTTechno Cr", "Faithless"))
        assertEquals("Disco 2000" to 0, Library.cleanAlbum("Disco 2000", "Pulp"))
        assertEquals("Greatest Hits" to 3, Library.cleanAlbum("Greatest Hits CD3", "Queen"))
    }

    @Test
    fun oasisGetsTheTwelveSongAlbumNotThePromoSingle() {
        val r = Catalogue.choose(Catalogue.parseReleases(saved("oasis_morning_glory.json")), "Oasis", 12)
        assertNotNull(r)
        assertEquals("(What's the Story) Morning Glory?", r!!.title)
        assertEquals("Album", r.type)
        assertEquals(1995, r.year)
        assertEquals("Oasis", r.artist)
    }

    @Test
    fun garthBrooksGetsTheTenSongOriginal() {
        val r = Catalogue.choose(Catalogue.parseReleases(saved("garth_ropin.json")), "Garth Brooks", 10)!!
        assertEquals(10, r.trackCount)
        assertEquals(1991, r.year)
    }

    @Test
    fun aCompilationMatchesOnlyAVariousArtistsRelease() {
        val found = Catalogue.parseReleases(saved("hits_80s.json"))
        val r = Catalogue.choose(found, "Various Artists", 25)!!
        assertEquals("100 80s Hits", r.title)
        assertEquals(2007, r.year)
        assertNull(Catalogue.choose(found, "Oasis", 25))
    }

    @Test
    fun theWrongArtistIsNeverTaken() {
        assertNull(Catalogue.choose(Catalogue.parseReleases(saved("garth_ropin.json")), "Faithless", 10))
    }

    @Test
    fun aLooseSongSkipsLiveAndBootlegRecords() {
        val found = Catalogue.parseSongReleases(saved("oasis_wonderwall_song.json"))
        val r = Catalogue.chooseForSong(found, "Oasis")
        if (r != null) {
            assertEquals("Album", r.type)
            assertEquals(emptyList<String>(), r.otherTypes)
        }
    }

    @Test
    fun searchWordsAreQuotedSafely() {
        assertEquals("\"Ropin' \\\"the\\\" Wind\"", Catalogue.quote("Ropin' \"the\" Wind"))
    }

    /** Only the first 15 songs found, as the app asks for. */
    private fun first15(name: String): String {
        val root = com.google.gson.JsonParser.parseString(saved(name)).asJsonObject
        val recs = root.getAsJsonArray("recordings")
        val keep = com.google.gson.JsonArray()
        for (i in 0 until minOf(15, recs.size())) keep.add(recs[i])
        root.add("recordings", keep)
        return root.toString()
    }

    @Test
    fun wonderwallGoesHomeToMorningGloryAndItsSingle() {
        val home = Catalogue.chooseSongHome(Catalogue.parseRecordings(first15("oasis_wonderwall_home.json")), "Wonderwall", "Oasis")
        assertEquals("(What's the Story) Morning Glory?", home.album?.title)
        assertEquals(1995, home.album?.year)
        assertEquals("Wonderwall", home.single?.title)
    }

    @Test
    fun billieJeanGoesHomeToThrillerAndItsSingle() {
        val home = Catalogue.chooseSongHome(Catalogue.parseRecordings(first15("mj_billie_jean_home.json")), "Billie Jean", "Michael Jackson")
        assertEquals("Thriller", home.album?.title)
        assertEquals("Billie Jean", home.single?.title)
    }

    /** MusicBrainz names no artist on the artist's own releases in a song search: they are still the artist's. */
    @Test
    fun weCloseOurEyesGoesHomeToGoWestNotA101HitsCompilation() {
        val home = Catalogue.chooseSongHome(Catalogue.parseRecordings(first15("gowest_we_close_our_eyes_home.json")), "We Close Our Eyes", "Go West")
        assertEquals("Go West", home.album?.title)
        assertEquals("Go West", home.album?.artist)
        assertEquals(1985, home.album?.year)
        assertEquals("We Close Our Eyes", home.single?.title)
    }

    @Test
    fun greatestHitsAndCollectionsAreKnownByName() {
        for (t in listOf("16 Biggest Hits", "101 80's Hits", "Greatest Hits II", "The Best of Shalamar", "The Very Best Of", "Gold", "The Singles 1992-2003", "Number Ones", "80's Alive - Mastermix"))
            assertEquals(t, true, Catalogue.looksLikeHits(t))
        for (t in listOf("A Night at the Opera", "Greatest Love of All", "Rumours", "Hitsville", "A Rush of Blood to the Head", "Golden Hour"))
            assertEquals(t, false, Catalogue.looksLikeHits(t))
    }

    /** Collections from Kevin's report of 24 November 2026 that the catalogue didn't know, and albums that must stay albums. */
    @Test
    fun moreCollectionsAreKnownByName() {
        for (t in listOf(
            "20 Golden Irish Ballads, Vol.2", "25 Rebel Songs Of Ireland", "25th Ann. Coll.", "Absolute Christmas", "As 20 Mais",
            "Back Home Again - 20 Memories Of Home & Family", "The Best, Vol 2", "The Box Set 1964-2004: Blue",
            "Rock Instrumental Classics Vol.2 : The Sixties", "Super Top 50", "Welcome to My World [16 CD Set]", "A Tribute To The Ladies",
        )) assertEquals(t, true, Catalogue.looksLikeHits(t))
        for (t in listOf("The Best Damn Thing", "Big Bam Boom", "X & Y", "So", "Be Yourself Tonight", "Count Three & Pray", "Revolver", "Labour of Love II", "Talk On Corners (Special Edition)"))
            assertEquals(t, false, Catalogue.looksLikeHits(t))
    }

    @Test
    fun aSongIsSearchedForWithoutItsBrackets() {
        assertEquals(
            "recording:\"Take My Breath Away\" AND artist:\"Berlin\" AND status:official AND (primarytype:album OR primarytype:single)",
            Catalogue.songQuery("Take My Breath Away (Love Theme From Top Gun)", "Berlin"),
        )
        assertEquals("recording:\"(Untitled)\"", Catalogue.songQuery("(Untitled)", "X").substringBefore(" AND"))
    }

    @Test
    fun anotherArtistsSongFindsNoHome() {
        val home = Catalogue.chooseSongHome(Catalogue.parseRecordings(first15("oasis_wonderwall_home.json")), "Wonderwall", "Ryan Adams")
        assertEquals(null, home.album)
        assertEquals(null, home.single)
    }

    @Test
    fun variousArtistsCountAsVarious() {
        assertEquals(true, Catalogue.isVarious("Various Artists"))
        assertEquals(true, Catalogue.isVarious("Various artists"))
        assertEquals(false, Catalogue.isVarious("Oasis"))
    }
}

/** Checks on reading an album's own list of songs, and finding which one a file is. */
class ReleaseSongsTest {
    private val json = """
        {"media":[{"position":1,"tracks":[
          {"position":1,"title":"Wonderwall","artist-credit":[{"name":"Oasis","joinphrase":""}]},
          {"position":2,"title":"Don’t Look Back in Anger","artist-credit":[{"name":"Oasis","joinphrase":""}]}
        ]},{"position":2,"tracks":[
          {"position":1,"title":"Fairytale of New York","artist-credit":[{"name":"The Pogues","joinphrase":" feat. "},{"name":"Kirsty MacColl","joinphrase":""}]}
        ]}]}
    """.trimIndent()

    @Test
    fun readsEverySongWithItsDiscPlaceNameAndArtist() {
        val songs = Catalogue.parseReleaseSongs(json)
        org.junit.Assert.assertEquals(3, songs.size)
        org.junit.Assert.assertEquals(Catalogue.Song(2, 1, "Fairytale of New York", "The Pogues feat. Kirsty MacColl"), songs[2])
        org.junit.Assert.assertEquals("Don't Look Back in Anger", songs[1].title)
    }

    @Test
    fun findsTheSongByNameWithBracketsAsideAndNeverGuesses() {
        val songs = Catalogue.parseReleaseSongs(json)
        org.junit.Assert.assertEquals("Wonderwall", Catalogue.songFor(songs, "wonderwall (Remastered)")?.title)
        org.junit.Assert.assertEquals(null, Catalogue.songFor(songs, "Some Might Say"))
    }
}
