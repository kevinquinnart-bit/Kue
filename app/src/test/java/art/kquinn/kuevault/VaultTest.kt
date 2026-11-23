package art.kquinn.kuevault

import org.junit.Assert.assertEquals
import org.junit.Test

class VaultTest {
    private val gb = 1_000_000_000L

    @Test
    fun aPlaceWithRoomTakesTheFile() {
        assertEquals(Room.OK, Vault.room(Quota(10 * gb, 20 * gb), gb))
    }

    @Test
    fun aPlaceGettingFullWarnsButStillTakesTheFile() {
        assertEquals(Room.NEARLY_FULL, Vault.room(Quota(17 * gb, 20 * gb), gb))
    }

    @Test
    fun aFullPlaceTakesNothingAndNothingSpillsElsewhere() {
        assertEquals(Room.FULL, Vault.room(Quota(19 * gb, 20 * gb), gb))
    }

    @Test
    fun aPlaceThatGivesNoLimitAlwaysHasRoom() {
        assertEquals(Room.OK, Vault.room(Quota(500 * gb, null), gb))
        assertEquals(Room.OK, Vault.room(null, gb))
    }

    /** Version 1's accounts: each address one place, each section living in its first account. */
    @Test
    fun versionOneAccountsBecomePlaces() {
        val old = """[{"email":"b@x.com","section":"PHOTOS","order":1},{"email":"a@x.com","section":"PHOTOS","order":0},
            {"email":"c@x.com","section":"DOCUMENTS","order":0},{"email":"m@x.com","section":"MAIL","order":0}]"""
        val (places, homes) = Vault.fromAccounts(old)
        assertEquals(listOf("g:b@x.com", "g:a@x.com", "g:c@x.com", "g:m@x.com"), places.map { it.id })
        assertEquals("g:a@x.com", homes[Section.PHOTOS])
        assertEquals("g:c@x.com", homes[Section.DOCUMENTS])
        assertEquals(2, homes.size)
    }

    @Test
    fun placesAndHomesReadBack() {
        val places = Vault.readPlaces("""[{"id":"h:pc/vault","kind":"HOME","label":"vault on pc","host":"pc","share":"vault","user":"kevin"},{"id":"x","kind":"NOPE","label":"?"}]""")
        assertEquals(1, places.size)
        assertEquals("kevin", places[0].user)
        assertEquals(mapOf(Section.FILMS to "h:pc/vault"), Vault.readHomes("""{"FILMS":"h:pc/vault","GONE":"x"}"""))
    }
}
