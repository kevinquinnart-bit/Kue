package art.kquinn.kuetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Moving apps on Home: along a row, into the next row, and the saved order coming back. */
class AppOrderTest {
    private val rows = mapOf(
        "tv" to listOf("iplayer", "itvx", "c4"),
        "films" to listOf("netflix", "prime"),
        "music" to emptyList(),
        "other" to listOf("files"),
    )

    @Test fun movesAlongItsRow() {
        assertEquals(listOf("itvx", "iplayer", "c4"), AppOrder.moved(rows, "iplayer", 1, 0)!!["tv"])
        assertEquals(listOf("iplayer", "c4", "itvx"), AppOrder.moved(rows, "c4", -1, 0)!!["tv"])
    }

    @Test fun stopsAtTheEnds() {
        assertNull(AppOrder.moved(rows, "iplayer", -1, 0))
        assertNull(AppOrder.moved(rows, "c4", 1, 0))
        assertNull(AppOrder.moved(rows, "iplayer", 0, -1))
        assertNull(AppOrder.moved(rows, "files", 0, 1))
    }

    @Test fun movesIntoTheNextRowAtTheSamePlace() {
        val next = AppOrder.moved(rows, "c4", 0, 1)!!
        assertEquals(listOf("iplayer", "itvx"), next["tv"])
        assertEquals(listOf("netflix", "prime", "c4"), next["films"])
        val down = AppOrder.moved(rows, "netflix", 0, 1)!!
        assertEquals(listOf("netflix"), down["music"])
    }

    @Test fun savedOrderComesFirstThenNames() {
        fun app(p: String, n: String) = DeviceApps.App(p, n, null)
        val apps = listOf(app("a", "Zeta") to "tv", app("b", "Alpha") to "tv", app("c", "Beta") to "tv", app("d", "Delta") to "films")
        val out = AppOrder.arrange(apps, listOf("a", "c"))
        assertEquals(listOf("a", "c", "b"), out["tv"]!!.map { it.packageName })
        assertEquals(listOf("d"), out["films"]!!.map { it.packageName })
    }
}
