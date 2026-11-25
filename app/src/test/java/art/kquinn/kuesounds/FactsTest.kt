package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the catalogue says about an album and its artist (25 Nov 2026), read from real answers saved that day (East 17,
 * Steam) in test/resources/musicbrainz. Nothing is asked of MusicBrainz or Wikidata while these run.
 */
class FactsTest {
    private fun saved(name: String): String = javaClass.getResource("/musicbrainz/$name")!!.readText()

    @Test
    fun anAlbumsKindsOfMusicDateAndArtist() {
        val f = Catalogue.parseGroupFacts(saved("group-steam.json"))
        assertEquals(listOf("electronic", "house", "pop"), f.genres)
        assertEquals("1994-10-13", f.firstOut)
        assertEquals("Album", f.type)
        assertEquals("ee20d564-25ca-4ef5-aba7-93a39f78ed60", f.artistId)
    }

    @Test
    fun theReleasesLabelCountryAndBarcode() {
        val f = Catalogue.parseReleaseFacts(saved("release-steam-labels.json"), AlbumFacts(genres = listOf("pop")))
        assertEquals("London Records", f.label)
        assertEquals("828 542-2", f.catalogueNo)
        assertEquals("GB", f.country)
        assertEquals("042282854223", f.barcode)
        assertEquals("1994-10-13", f.released)
        assertEquals(listOf("pop"), f.genres)
        assertEquals(true, f.detailed)
    }

    @Test
    fun theArtistsPictureIsFoundThroughWikidata() {
        assertEquals("Q1277715", Catalogue.wikidataOf(saved("artist-east17.json")))
        assertEquals("East 17 - Promotour in Cologne-1182.jpg", Catalogue.pictureNameOf(saved("wikidata-east17.json"), "Q1277715"))
    }

    @Test
    fun datesAndKindsWrittenForPeople() {
        assertEquals("13 October 1994", Library.date("1994-10-13"))
        assertEquals("November 1994", Library.date("1994-11"))
        assertEquals("1994", Library.date("1994"))
        assertEquals("Synth-Pop", Library.tidyGenre("synth-pop"))
        assertEquals("Hip Hop", Library.tidyGenre("hip hop"))
    }
}
