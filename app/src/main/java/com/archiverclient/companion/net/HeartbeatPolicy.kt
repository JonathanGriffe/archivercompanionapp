package com.archiverclient.companion.net

/**
 * How often to tell a computer we are here.
 *
 * The phone announces itself only when there is something to announce. A desktop
 * scans on a sixteen-hour cadence, so a computer that has collected from this
 * phone inside that window already has what it is going to ask for and needs
 * nothing from us until the next one comes round. The rest of the time — no
 * pairing, no wifi — there is nowhere to announce to at all.
 *
 * What is left is the case that matters: on wifi, paired, and nothing has
 * collected from us in longer than a scan cycle. Then we make ourselves visible
 * and keep doing it until somebody comes.
 *
 * Pure, and driven by an explicit clock, so the whole schedule can be exercised
 * in a unit test rather than observed over days on a handset.
 */
object HeartbeatPolicy {

    /**
     * `ac-import/src/ledger.rs::SCAN_INTERVAL`. A desktop looks at each source
     * about this often, so a computer that has collected from us more recently
     * than this has nothing to gain from hearing about us again.
     */
    const val SCAN_INTERVAL_MS = 16 * 60 * 60 * 1000L

    /** How often to announce while waiting to be collected from. */
    const val POLL_MS = 15 * 60 * 1000L

    /**
     * `listener.rs::FRESH` — the desktop's own window, recorded here because it
     * is not ours to pick and because it interacts with [POLL_MS].
     *
     * A phone counts as present only for five minutes after each hello, so while
     * we announce every fifteen we look present a third of the time. That is
     * enough because of [AWAY_RETRY_MS]: see there.
     */
    const val FRESH_MS = 5 * 60 * 1000L

    /**
     * `import_link.rs::AWAY_RETRY` — how soon the desktop looks again for a
     * phone it found absent, give or take one five-second tick.
     *
     * Probes five minutes apart can hardly step over a five-minute window, so a
     * desktop whose scan has come due finds an announcing phone within twenty
     * minutes of its first hello about 99% of the time, and within about half
     * an hour always. `HeartbeatPolicyTest` works it out over every phase
     * between the two clocks. Being found never waits for another scan cycle.
     */
    const val AWAY_RETRY_MS = 5 * 60 * 1000L

    data class State(
        /** False when nothing is paired, or every pairing has been revoked. */
        val hasActivePeers: Boolean,
        /** Wifi specifically. A phone on mobile data has no business announcing. */
        val onWifi: Boolean,
        /**
         * Since the least recently served computer last collected from us, so a
         * neglected second desktop still gets us talking. [Long.MAX_VALUE] when
         * none ever has, which is the state a freshly paired phone is in.
         */
        val msSinceCollected: Long,
    )

    sealed interface Decision {
        /**
         * Announce nothing and hold no sockets.
         *
         * [recheckAfterMs] is when this stops being true on its own — the moment a
         * scan cycle will have elapsed. Null means nothing changes with time and
         * only an event can move us: a pairing, or a network arriving.
         */
        data class Dormant(val recheckAfterMs: Long?) : Decision

        /** Announce now, then wait this long before deciding again. */
        data class Announce(val thenWaitMs: Long) : Decision
    }

    fun decide(s: State): Decision {
        // Nobody to announce ourselves to. Pairing is an event, not a deadline.
        if (!s.hasActivePeers) return Decision.Dormant(null)

        // Nowhere to announce. A network arriving is an event too.
        if (!s.onWifi) return Decision.Dormant(null)

        // Somebody has collected from us inside a scan cycle, so nothing is waiting
        // to be fetched that they will not come for anyway. This one does expire, so
        // it carries the time until it does.
        if (s.msSinceCollected < SCAN_INTERVAL_MS) {
            return Decision.Dormant(SCAN_INTERVAL_MS - s.msSinceCollected)
        }

        return Decision.Announce(POLL_MS)
    }

    /** Refusals in a row before a computer is taken at its word. */
    const val REFUSALS_TO_REVOKE = 3

    /** And how long they have to have gone on for. */
    const val REFUSING_FOR_MS = 60 * 60 * 1000L

    /** Where a run of `403`s stands after one more. */
    data class Refused(val refusals: Int, val since: Long, val revoke: Boolean)

    /**
     * One more `403`, at [now], after [before] in a row that began at [since].
     *
     * A desktop answers `403` to a hello whose token it does not know: the phone
     * source was removed, or paired again with a new code. That is for ever. But
     * `phone.rs::arrived` answers the same when it cannot open its ledger or read
     * its sources at that moment — a busy database during a large import — and
     * that passes in seconds. Taking either as final costs the same: a revoked
     * computer is never sent a hello again, so it never counts this phone as
     * present, so it never scans it again, until somebody notices and pairs
     * afresh.
     *
     * So only a run that lasts is believed: at least [REFUSALS_TO_REVOKE] in a
     * row, spanning at least [REFUSING_FOR_MS]. Both, because hellos can arrive
     * in bursts — wifi flapping nudges the loop several times a minute — and a
     * count alone would be reached in seconds; and because one refusal now and
     * one two days later is a span with only two answers in it. At [POLL_MS]
     * that is the fifth hello, an hour after the first refusal. An accepted
     * hello ends the run; one that nobody answered neither ends nor extends it.
     */
    fun refused(before: Int, since: Long, now: Long): Refused {
        val refusals = before + 1
        val began = if (before == 0) now else since
        return Refused(
            refusals = refusals,
            since = began,
            revoke = refusals >= REFUSALS_TO_REVOKE && now - began >= REFUSING_FOR_MS,
        )
    }
}
