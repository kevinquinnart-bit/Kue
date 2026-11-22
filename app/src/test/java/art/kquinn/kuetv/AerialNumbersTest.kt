package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Provider channel names matched to the aerial's numbers in Freely, from Kevin's photos (22 November 2026). */
class AerialNumbersTest {
    @Test fun namesAsProvidersWriteThem() {
        assertEquals(245, Aerial.numberFor("4 Seven"))
        assertEquals(245, Aerial.numberFor("UK: 4seven HD"))
        assertEquals(233, Aerial.numberFor("ITV 1 HD"))
        assertEquals(254, Aerial.numberFor("UK| ITV2 +1"))
        assertEquals(252, Aerial.numberFor("Channel 4 plus 1"))
        assertEquals(301, Aerial.numberFor("BBC News"))
        assertEquals(259, Aerial.numberFor("U&Dave ja vu"))
        assertEquals(1, Aerial.numberFor("UK: BBC One HD"))
        assertEquals(2, Aerial.numberFor("BBC Two"))
    }

    @Test fun notOnTheAerial() {
        assertEquals(null, Aerial.numberFor("Channel 5"))
        assertEquals(null, Aerial.numberFor("Sky Sports Main Event"))
    }
}
