package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicates, repeats in a playlist, suggestions and covers to pick (26 Nov 2026). The songs here are made up; the cover
 * answers are real ones from Apple Music's and Deezer's free search, saved that day (test/resources/covers).
 */
class SmartTest {
    private fun t(id: String, title: String, artist: String, ms: Long, album: String = "A", year: Int = 0, genre: String = "") =
        Track(id, "$title.mp3", "audio/mpeg", 100, "f", "t", title, artist, album, "", 1, 0, year, ms, genre, true)

    @Test
    fun theSameSongTwiceIsFoundButALongerVersionIsNot() {
        val songs = listOf(
            t("1", "Stay Another Day", "East 17", 267_000),
            t("2", "Stay Another Day (Remastered)", "East 17", 268_500, album = "Hits"),
            t("3", "Stay Another Day", "East 17", 420_000, album = "Live"),
            t("4", "Thunder", "East 17", 240_000),
        )
        val groups = Smart.duplicates(songs)
        assertEquals(1, groups.size)
        assertEquals(setOf("1", "2"), groups.first().map { it.id }.toSet())
    }

    @Test
    fun aPlaylistKeepsEachSongOnce() {
        val a = t("1", "Song", "Band", 200_000)
        val b = t("2", "Song (Remastered)", "Band", 201_000)
        val c = t("3", "Other", "Band", 180_000)
        val byId = listOf(a, b, c).associateBy { it.id }
        val (kept, gone) = Smart.withoutRepeats(listOf("1", "3", "1", "2"), byId)
        assertEquals(listOf("1", "3"), kept)
        assertEquals(2, gone)
    }

    @Test
    fun suggestionsAreTheSameArtistAndKindFirst() {
        val seed = listOf(t("1", "One", "Band", 200_000, year = 1994, genre = "Pop"))
        val all = seed + listOf(
            t("2", "Two", "Band", 200_000, year = 1995, genre = "Pop"),
            t("3", "Three", "Other", 200_000, year = 1994, genre = "Pop"),
            t("4", "Four", "Someone", 200_000, year = 1970, genre = "Jazz"),
        )
        val ideas = Smart.suggest(seed, all, LibraryData(tracks = all))
        assertEquals("2", ideas.first().id)
        assertTrue(ideas.none { it.id == "4" })
        assertTrue(ideas.none { it.id == "1" })
    }

    @Test
    fun coversFromAppleAndDeezer() {
        val apple = Smart.parseItunes(javaClass.getResource("/covers/itunes-steam.json")!!.readText())
        assertEquals("Steam", apple.first().title)
        assertEquals("East 17", apple.first().artist)
        assertTrue(apple.first().big.contains("600x600"))
        val deezer = Smart.parseDeezer(javaClass.getResource("/covers/deezer-steam.json")!!.readText())
        assertEquals("Steam", deezer.first().title)
        assertTrue(deezer.first().big.startsWith("https://"))
    }

    @Test
    fun emojiAreTakenOutOfNames() {
        assertEquals("Dinosaur Sounds", Plain.noEmoji("Dinosaur Sounds🦖"))
        assertEquals("Untitled", Plain.noEmoji("🏫"))
        assertEquals("Café © 2024", Plain.noEmoji("Café © 2024"))
    }
}
