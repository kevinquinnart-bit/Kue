package art.kquinn.kuevault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultTest {
    private val gb = 1_000_000_000L
    private val a = VaultAccount("a@x.com", Section.PHOTOS, 0)
    private val b = VaultAccount("b@x.com", Section.PHOTOS, 1)

    @Test
    fun firstAccountTakesFilesUntilNinetyPercentFull() {
        val quotas = mapOf("a@x.com" to Quota(10 * gb, 20 * gb), "b@x.com" to Quota(0, 15 * gb))
        assertEquals("a@x.com", Vault.pick(listOf(b, a), quotas, gb)?.email)
    }

    @Test
    fun nextAccountTakesOverWhenTheFirstWouldPassNinetyPercent() {
        val quotas = mapOf("a@x.com" to Quota(17 * gb, 20 * gb), "b@x.com" to Quota(0, 15 * gb))
        assertEquals("b@x.com", Vault.pick(listOf(a, b), quotas, gb)?.email)
    }

    @Test
    fun noAccountWhenAllAreFull() {
        val quotas = mapOf("a@x.com" to Quota(19 * gb, 20 * gb), "b@x.com" to Quota(14 * gb, 15 * gb))
        assertNull(Vault.pick(listOf(a, b), quotas, gb))
    }
}
