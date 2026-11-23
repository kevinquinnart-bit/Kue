package art.kquinn.kuevault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only where a test computer is sharing a folder: KUEVAULT_SMB=host/share/user/password. */
class HomeStoreLiveTest {
    @Test
    fun aSharedFolderTakesListsGivesBackAndBinsAFile() {
        val spec = System.getenv("KUEVAULT_SMB")
        assumeTrue(spec != null)
        val (host, share, user, password) = spec!!.split('/')
        val place = Place.home(host, share, user)
        val store = HomeStore(place, password)
        val quota = store.check()
        assertTrue(quota != null && quota.limit!! > 0)
        assertEquals(emptyList<Store.Item>(), store.list(Section.DOCUMENTS))
        val bytes = ByteArray(700_000) { (it % 251).toByte() }
        val seen = ArrayList<Int>()
        store.onShare { s ->
            s.mkdir("KueVault\\Documents")
            store.copyUp(s, "KueVault\\Documents\\Report.pdf", bytes.inputStream(), bytes.size.toLong()) { seen += it }
        }
        assertEquals(100, seen.last())
        val items = store.list(Section.DOCUMENTS)
        assertEquals(listOf("Report.pdf"), items.map { it.name })
        assertEquals(700_000L, items[0].size)
        val target = java.io.File.createTempFile("kuevault", ".pdf")
        store.download(items[0], target) {}
        assertTrue(bytes.contentEquals(target.readBytes()))
        store.bin(items[0])
        assertEquals(emptyList<Store.Item>(), store.list(Section.DOCUMENTS))
        assertTrue(store.onShare { it.fileExists("KueVault\\Bin\\Report.pdf") })
    }

    @Test
    fun aWrongPasswordIsSaidPlainly() {
        val spec = System.getenv("KUEVAULT_SMB")
        assumeTrue(spec != null)
        val (host, share, user, _) = spec!!.split('/')
        val e = runCatching { HomeStore(Place.home(host, share, user), "wrong").check() }.exceptionOrNull()
        assertTrue(e is HomeStore.Unreachable)
        assertEquals("$host turned the name and password down.", e!!.message)
    }
}
