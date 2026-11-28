package art.kquinn.kuesounds

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Remastered" taken out of names (Kevin, 28 November 2026: "Shakedown (Remastered)"). */
class RemasterTest {
    @Test fun takesRemasteredOut() {
        assertEquals("Shakedown", Library.withoutRemaster("Shakedown (Remastered)"))
        assertEquals("Paint It Black", Library.withoutRemaster("Paint It Black - Remastered 2009"))
        assertEquals("Heroes", Library.withoutRemaster("Heroes - 2017 Remaster"))
        assertEquals("Drinkin' & Courtin'", Library.withoutRemaster("Drinkin' & Courtin' (Remastered)"))
        assertEquals("Rumours", Library.withoutRemaster("Rumours [2004 Remaster]"))
        assertEquals("Live (Deluxe)", Library.withoutRemaster("Live (Deluxe) (Remastered Version)"))
        assertEquals("Come My Little Son", Library.withoutRemaster("Come My Little Son"))
    }
}
