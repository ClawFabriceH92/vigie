package com.fabrice.vigie

import com.fabrice.vigie.trust.TrustMonitor
import org.junit.Assert.assertEquals
import org.junit.Test

class TrustMonitorTest {

    private val now = 1_000_000_000_000L
    private val delay = 120_000L // 2 min

    @Test
    fun `confiance presente = desarme`() {
        val d = TrustMonitor.decide(listOf("aabbcc"), now, now, delay)
        assertEquals(TrustMonitor.ArmState.DISARMED, d.state)
        assertEquals(1, d.trustedPresent.size)
    }

    @Test
    fun `jamais vu = arme immediatement`() {
        // lastTrustSeenMs == 0 : l'utilisateur n'a jamais été vu → on arme
        val d = TrustMonitor.decide(emptyList(), 0L, now, delay)
        assertEquals(TrustMonitor.ArmState.ARMED, d.state)
    }

    @Test
    fun `absence courte = armement en cours`() {
        val lastSeen = now - 30_000 // 30 s sans confiance
        val d = TrustMonitor.decide(emptyList(), lastSeen, now, delay)
        assertEquals(TrustMonitor.ArmState.ARMING, d.state)
        assertEquals(90L, d.secondsUntilArmed) // 120 - 30
    }

    @Test
    fun `absence longue = arme`() {
        val lastSeen = now - 130_000 // > 2 min
        val d = TrustMonitor.decide(emptyList(), lastSeen, now, delay)
        assertEquals(TrustMonitor.ArmState.ARMED, d.state)
    }

    @Test
    fun `reapparition = desarme`() {
        val d = TrustMonitor.decide(listOf("112233445566"), now - 10_000, now, delay)
        assertEquals(TrustMonitor.ArmState.DISARMED, d.state)
    }
}
