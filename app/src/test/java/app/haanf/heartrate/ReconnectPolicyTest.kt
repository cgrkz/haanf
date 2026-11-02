package app.haanf.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy()

    @Test
    fun `backs off and then holds at the cap forever`() {
        assertEquals(1_000, policy.delayMs(1))
        assertEquals(2_000, policy.delayMs(2))
        assertEquals(30_000, policy.delayMs(6))
        assertEquals(30_000, policy.delayMs(1_000))
    }

    @Test
    fun `rescans on every third attempt`() {
        assertFalse(policy.shouldRescan(1))
        assertFalse(policy.shouldRescan(2))
        assertTrue(policy.shouldRescan(3))
        assertTrue(policy.shouldRescan(6))
    }
}
