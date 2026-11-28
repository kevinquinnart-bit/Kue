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
}
