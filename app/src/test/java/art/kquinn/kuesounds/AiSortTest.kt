package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sort with AI's reading of answers. The OpenRouter model list is its real public list, saved 28 November 2026. The
 * Gemini and OpenRouter replies are made by hand in the shape their documents give (no key was used here), so these
 * prove the reading, not a live answer.
 */
class AiSortTest {
    private fun text(name: String) = javaClass.getResourceAsStream("/ai/$name")!!.readBytes().decodeToString()

    @Test fun picksABigFreeModel() {
        assertEquals("google/gemma-4-31b-it:free", AiSort.pickFree(text("openrouter_models.json")))
    }

    @Test fun readsGeminiWrappedInFences() {
        val reply = """{"candidates":[{"content":{"parts":[{"text":"```json\n{\"title\":\"Clare To Here\",\"artist\":\"Paddy Reilly\",\"album\":\"The Town I Loved So Well\",\"albumArtist\":\"Paddy Reilly\",\"year\":\"1983\",\"track\":\"3/12\",\"disc\":0,\"genre\":\"Folk\",\"cover\":\"\",\"discogs\":\"https://www.discogs.com/release/123\",\"wikipedia\":\"\",\"sure\":\"High\"}\n```"}],"role":"model"}}]}"""
        val o = AiSort.jsonIn(AiSort.geminiText(reply))
        assertNotNull(o)
        val s = AiSort.said(o!!, album = false, from = "AI")
        assertEquals("Clare To Here", s.look.title)
        assertEquals("1983", s.look.year)
        assertEquals(3, s.look.trackNo)
        assertEquals("high", s.sure)
        assertEquals(listOf("https://www.discogs.com/release/123"), s.links)
    }

    @Test fun readsOpenRouterWithWordsRound() {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"Here it is: {\"title\":\"\",\"artist\":\"The Fureys\",\"album\":\"A Drop Of Irish\",\"year\":\"released 1999\",\"pick\":2,\"sure\":\"medium\"} done"}}]}"""
        val s = AiSort.said(AiSort.jsonIn(AiSort.openRouterText(reply))!!, album = true, from = "AI")
        assertEquals("A Drop Of Irish", s.look.album)
        assertEquals("The Fureys", s.look.albumArtist)
        assertEquals("1999", s.look.year)
        assertEquals(2, s.pick)
        assertTrue(s.look.title.isEmpty())
    }

    @Test fun knowsWhoseKey() {
        assertEquals("Google", AiSort.whose("AIzaSyExample"))
        assertEquals("Google", AiSort.whose("AQ.Ab8RN6Example"))
        assertEquals("OpenRouter", AiSort.whose("sk-or-v1-abc"))
        assertEquals("", AiSort.whose("hello"))
    }

    @Test fun readsGooglesReason() {
        val reply = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertEquals("API key not valid. Please pass a valid API key.", AiSort.saidWhy(reply))
    }

    @Test fun picksGooglesNewestFlashLite() {
        val list = """{"models":[
          {"name":"models/gemini-2.5-flash-lite","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.6-flash-lite-preview-11-2026","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-embedding-2","supportedGenerationMethods":["embedContent"]}]}"""
        assertEquals("gemini-3.5-flash-lite", AiSort.pickGoogle(list))
    }

    @Test fun fileNameAsWords() {
        assertEquals("Paddy Reilly - Clare To Here", AiSort.nameWords("03 - Paddy_Reilly - Clare To Here (Live).mp3").replace(" Live", ""))
    }

    @Test fun knowsCompilationsAndMixes() {
        assertTrue(AiSort.compilation("The Very Best Of Foster & Allen"))
        assertTrue(AiSort.compilation("Greatest Hits Vol. 2"))
        assertTrue(AiSort.compilation("The Collection"))
        assertTrue(!AiSort.compilation("A Drop Of Irish"))
        assertTrue(!AiSort.compilation("The Town I Loved So Well"))
        assertTrue(AiSort.mixOfSongs("Irish Party Medley"))
        assertTrue(AiSort.mixOfSongs("80s Megamix (Non-Stop)"))
        assertTrue(!AiSort.mixOfSongs("Clare To Here"))
    }

    /** Wikipedia's real answer (saved 28 November 2026) for "My Little Son" Luke Kelly: The Dubliners' Drinkin' and Courtin'. */
    @Test fun findsTheOriginalAlbumOnWikipedia() {
        val found = Covers.parseWikiRecords(text("wikipedia_my_little_son.json"), "Come My Little Son")
        val f = found.first()
        assertEquals("Drinkin' and Courtin'", f.album)
        assertEquals("The Dubliners", f.artist)
        assertEquals("1968", f.year)
        assertEquals("studio album", f.kind)
        assertEquals("My Little Son", f.title)
        assertEquals(15, f.trackNo)
        assertEquals("Irish folk", f.genre)
        assertTrue(f.big.startsWith("https://upload.wikimedia.org/"))
        assertTrue(!AiSort.isCompilation(f))
    }

    @Test fun knowsTheLeadSingerIsInTheBand() {
        val f = Covers.parseWikiRecords(text("wikipedia_my_little_son.json"), "Come My Little Son", "Luke Kelly").first()
        assertEquals("The Dubliners", f.artist)
        assertEquals("Luke Kelly", f.credits)
    }

    /** Wikipedia's real answer (saved 28 November 2026) for "Don't Go" Yazoo: the song page and Upstairs at Eric's. */
    @Test fun findsDontGoOnUpstairsAtErics() {
        val found = Covers.parseWikiRecords(text("wikipedia_dont_go.json"), "Don't Go", "Yazoo")
        val album = found.first { it.kind == "studio album" }
        assertEquals("Upstairs at Eric's", album.album)
        assertEquals("Yazoo", album.artist)
        assertEquals("1982", album.year)
        assertEquals("Don't Go", album.title)
        assertEquals(1, album.trackNo)
        assertTrue(album.big.startsWith("https://upload.wikimedia.org/"))
        val song = found.first { it.kind.startsWith("song page") }
        assertEquals("Upstairs at Eric's", song.album)
        assertTrue(song.big.isEmpty())
    }

    @Test fun searchesWithTheFirstArtist() {
        assertEquals("Yazoo", AiSort.searchArtist("Yazoo - Alison Moyet"))
        assertEquals("Kenny Rogers", AiSort.searchArtist("Kenny Rogers with Dolly Parton"))
        assertEquals("The Killers", AiSort.searchArtist("The Killers feat. Lou Reed"))
        assertEquals("Luke Kelly", AiSort.searchArtist("Luke Kelly"))
    }
}
