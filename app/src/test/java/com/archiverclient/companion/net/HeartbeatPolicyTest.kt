package com.archiverclient.companion.net

import com.archiverclient.companion.net.HeartbeatPolicy.Decision
import com.archiverclient.companion.net.HeartbeatPolicy.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules, and the reasoning behind the numbers.
 *
 * Everything here is a choice except [HeartbeatPolicy.SCAN_INTERVAL_MS] and
 * [HeartbeatPolicy.FRESH_MS], which belong to the desktop.
 */
class HeartbeatPolicyTest {

    private val hour = 60 * 60 * 1000L

    private fun state(
        peers: Boolean = true,
        wifi: Boolean = true,
        sinceCollected: Long = Long.MAX_VALUE,
    ) = State(hasActivePeers = peers, onWifi = wifi, msSinceCollected = sinceCollected)

    @Test
    fun `nothing paired means nothing is sent, and nothing to wait for`() {
        val decision = HeartbeatPolicy.decide(state(peers = false))
        assertEquals(Decision.Dormant(null), decision)
    }

    @Test
    fun `off wifi means nothing is sent`() {
        assertEquals(Decision.Dormant(null), HeartbeatPolicy.decide(state(wifi = false)))
        // Even with a computer long overdue: there is nowhere to say it.
        assertEquals(
            Decision.Dormant(null),
            HeartbeatPolicy.decide(state(wifi = false, sinceCollected = 40 * hour)),
        )
    }

    @Test
    fun `mobile data is not wifi`() {
        // The distinction the Conditions implementation has to make; asserted here so
        // the policy's own contract is unambiguous about what `onWifi` means.
        assertEquals(Decision.Dormant(null), HeartbeatPolicy.decide(state(wifi = false)))
    }

    @Test
    fun `a computer that collected recently earns silence`() {
        val decision = HeartbeatPolicy.decide(state(sinceCollected = hour))
        assertEquals(Decision.Dormant(HeartbeatPolicy.SCAN_INTERVAL_MS - hour), decision)
    }

    /** Dormancy from a recent collection expires on its own, so it says when. */
    @Test
    fun `the wait is exactly what is left of the scan cycle`() {
        listOf(0L, hour, 8 * hour, HeartbeatPolicy.SCAN_INTERVAL_MS - 1).forEach { since ->
            val decision = HeartbeatPolicy.decide(state(sinceCollected = since))
            assertEquals(
                Decision.Dormant(HeartbeatPolicy.SCAN_INTERVAL_MS - since),
                decision,
            )
        }
    }

    @Test
    fun `dormancy with no deadline is only left by an event`() {
        assertNull((HeartbeatPolicy.decide(state(peers = false)) as Decision.Dormant).recheckAfterMs)
        assertNull((HeartbeatPolicy.decide(state(wifi = false)) as Decision.Dormant).recheckAfterMs)
    }

    @Test
    fun `a full scan cycle without being collected from starts the polling`() {
        assertEquals(
            Decision.Announce(HeartbeatPolicy.POLL_MS),
            HeartbeatPolicy.decide(state(sinceCollected = HeartbeatPolicy.SCAN_INTERVAL_MS)),
        )
        assertEquals(
            Decision.Announce(HeartbeatPolicy.POLL_MS),
            HeartbeatPolicy.decide(state(sinceCollected = 40 * hour)),
        )
    }

    /**
     * A phone that has just been paired has never been collected from, so it must
     * make itself visible rather than wait sixteen hours for a deadline that has
     * no start.
     */
    @Test
    fun `a freshly paired phone announces immediately`() {
        assertEquals(
            Decision.Announce(HeartbeatPolicy.POLL_MS),
            HeartbeatPolicy.decide(state(sinceCollected = Long.MAX_VALUE)),
        )
    }

    @Test
    fun `polling keeps going until somebody actually collects`() {
        // No attempt counter: unlike the old policy this does not give up, because
        // the only thing that ends it is a computer turning up and taking the files.
        var s = state(sinceCollected = 20 * hour)
        repeat(50) {
            assertEquals(Decision.Announce(HeartbeatPolicy.POLL_MS), HeartbeatPolicy.decide(s))
            s = s.copy(msSinceCollected = s.msSinceCollected + HeartbeatPolicy.POLL_MS)
        }
    }

    @Test
    fun `being collected from stops the polling at once`() {
        assertEquals(
            Decision.Announce(HeartbeatPolicy.POLL_MS),
            HeartbeatPolicy.decide(state(sinceCollected = 20 * hour)),
        )
        assertEquals(
            Decision.Dormant(HeartbeatPolicy.SCAN_INTERVAL_MS),
            HeartbeatPolicy.decide(state(sinceCollected = 0)),
        )
    }

    /**
     * Why announcing every fifteen minutes is enough.
     *
     * We look present for [HeartbeatPolicy.FRESH_MS] after each hello — a third
     * of the time — but a desktop that found us absent looks again every
     * [HeartbeatPolicy.AWAY_RETRY_MS], plus up to one five-second tick. Worked
     * out here over every phase between its probes and our hellos, with the
     * tick always on time and always late.
     */
    @Test
    fun `a desktop finds an announcing phone within half an hour, nearly always within twenty minutes`() {
        val poll = HeartbeatPolicy.POLL_MS
        val fresh = HeartbeatPolicy.FRESH_MS
        val tick = 5_000L
        assertTrue("probes no further apart than the window", HeartbeatPolicy.AWAY_RETRY_MS <= fresh)

        for (late in listOf(0L, tick)) {
            // Hellos at 0, poll, 2 poll…; the desktop's probes from `phase` on.
            val found = (0 until poll step 100).map { phase ->
                var probe = phase
                while (probe % poll >= fresh) probe += HeartbeatPolicy.AWAY_RETRY_MS + late
                probe
            }
            val withinTwenty = found.count { it < poll + fresh }.toDouble() / found.size
            // At worst during the third hello's window: half an hour and a few late ticks.
            assertTrue("late by $late: ${found.max()} ms at worst", found.max() < 2 * poll + fresh)
            assertTrue("late by $late: $withinTwenty within twenty minutes", withinTwenty >= 0.98)
        }
    }

    @Test
    fun `one refusal is not taken as final`() {
        val first = HeartbeatPolicy.refused(before = 0, since = 0, now = 5 * hour)
        assertEquals(HeartbeatPolicy.Refused(refusals = 1, since = 5 * hour, revoke = false), first)
    }

    @Test
    fun `a run of refusals keeps the moment it began`() {
        val first = HeartbeatPolicy.refused(0, 0, now = hour)
        val second = HeartbeatPolicy.refused(first.refusals, first.since, now = hour + HeartbeatPolicy.POLL_MS)
        assertEquals(2, second.refusals)
        assertEquals(hour, second.since)
    }

    @Test
    fun `refusals at the poll interval are believed on the fifth, an hour in`() {
        var refusals = 0
        var since = 0L
        var revokedAt: Int? = null
        for (hello in 1..10) {
            val run = HeartbeatPolicy.refused(refusals, since, now = (hello - 1) * HeartbeatPolicy.POLL_MS)
            refusals = run.refusals
            since = run.since
            if (run.revoke) {
                revokedAt = hello
                break
            }
        }
        assertEquals(5, revokedAt)
    }

    @Test
    fun `a burst of refusals is not enough without the time`() {
        // Wifi flapping nudges the loop several times a minute, and each nudge announces.
        val run = HeartbeatPolicy.refused(before = 20, since = 0, now = HeartbeatPolicy.REFUSING_FOR_MS - 1)
        assertFalse(run.revoke)
    }

    @Test
    fun `the time is not enough without several answers in it`() {
        // Refused, then off wifi for two days, then refused again: two answers, not a run.
        val run = HeartbeatPolicy.refused(before = 1, since = 0, now = 48 * hour)
        assertFalse(run.revoke)
        assertTrue(HeartbeatPolicy.refused(before = 2, since = 0, now = 48 * hour).revoke)
    }

    @Test
    fun `a computer that heard from where we still are is left in peace`() {
        assertFalse(HeartbeatPolicy.mustTell(setOf("192.168.1.140"), "192.168.1.140"))
    }

    @Test
    fun `a computer that heard from where we no longer are is told`() {
        assertTrue(HeartbeatPolicy.mustTell(setOf("192.168.1.141"), "192.168.1.140"))
    }

    @Test
    fun `any of our addresses will do, since a hello leaves from whichever the kernel picks`() {
        assertFalse(HeartbeatPolicy.mustTell(setOf("192.168.1.141", "10.0.0.7"), "10.0.0.7"))
    }

    @Test
    fun `off wifi nobody is told anything`() {
        assertFalse(HeartbeatPolicy.mustTell(emptySet(), "192.168.1.140"))
        assertFalse(HeartbeatPolicy.mustTell(emptySet(), ""))
    }

    @Test
    fun `a pairing from before this was recorded is told once, which records it`() {
        assertTrue(HeartbeatPolicy.mustTell(setOf("192.168.1.140"), ""))
    }
}
