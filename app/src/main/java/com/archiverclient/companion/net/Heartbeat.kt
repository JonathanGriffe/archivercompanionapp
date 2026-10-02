package com.archiverclient.companion.net

import android.content.Context
import android.util.Log
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.IOException

/**
 * Telling every paired computer where this phone is.
 *
 * Runs [HeartbeatPolicy] against real conditions. The loop only ever waits in
 * one of two ways: for the policy's next interval, or — when the policy says to
 * go dormant — for something to happen. It never spins.
 */
class Heartbeat(
    private val context: Context,
    private val scope: CoroutineScope,
    private val discovery: NodeDiscovery,
    private val conditions: Conditions,
    private val serverPort: () -> Int,
) {

    /** What the policy needs to know that only Android can answer. */
    interface Conditions {
        /**
         * Whether there is a wifi network under us right now.
         *
         * Which wifi is deliberately not asked. Reading the SSID needs a location
         * permission, and nothing here would do anything with the answer: a
         * computer is either reachable from where we are or it is not, and trying
         * is a cheaper way to find out than remembering.
         */
        fun onWifi(): Boolean
        fun now(): Long = System.currentTimeMillis()
    }

    private val db = AppDatabase.of(context)

    /** Conflated: several nudges arriving at once mean the same thing as one. */
    private val wake = Channel<String>(Channel.CONFLATED)

    private var job: Job? = null

    @Volatile
    var lastOutcome: String = "not started"
        private set

    fun start() {
        if (job?.isActive == true) return
        // Explicitly off the main thread. The scope this is handed is a service's
        // `lifecycleScope`, which is `Dispatchers.Main`, and `NodeClient` does blocking
        // socket work — the first announcement would be a `NetworkOnMainThreadException`.
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Something happened that a computer would want to know about.
     *
     * Wakes the loop so it recomputes. The reasons are all real events — a
     * request served, new media, wifi arriving, a fresh pairing — never a timer. Whether anything is then announced is the policy's call:
     * a computer that has just collected from us has re-earned its quiet.
     */
    fun nudge(reason: String) {
        wake.trySend(reason)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loop() {
        while (scope.isActive) {
            val peers = db.peers().active()
            val state = HeartbeatPolicy.State(
                hasActivePeers = peers.isNotEmpty(),
                onWifi = conditions.onWifi(),
                msSinceCollected = staleness(peers),
            )

            when (val decision = HeartbeatPolicy.decide(state)) {
                is HeartbeatPolicy.Decision.Dormant -> {
                    lastOutcome = "nothing to announce"
                    // A null recheck means only an event can change the answer, so we
                    // wait for one. Otherwise we also wake when the reason expires.
                    //
                    // A wait measured in hours will not survive doze or the process being
                    // killed, and is not asked to. The timeout runs on a clock that stops
                    // while the phone sleeps, so it only ever comes late. `WatchdogWorker`
                    // nudges the service every fifteen minutes, starting it again first if
                    // it was killed, and the loop recomputes from the database and the
                    // wall clock. So this is the fast path rather than the guarantee.
                    val recheck = decision.recheckAfterMs
                    val reason = when (recheck) {
                        null -> wake.receive()
                        else -> select {
                            wake.onReceive { it }
                            onTimeout(recheck) { "a scan cycle elapsed" }
                        }
                    }
                    Log.d(TAG, "woken by $reason")
                }

                is HeartbeatPolicy.Decision.Announce -> {
                    announce(peers)
                    // Waiting on the channel rather than sleeping, so an event arriving
                    // mid-interval is acted on at once rather than at the end of it.
                    val woken = select {
                        wake.onReceive { it }
                        onTimeout(decision.thenWaitMs) { null }
                    }
                    if (woken != null) Log.d(TAG, "woken by $woken")
                }
            }
        }
    }

    /**
     * How long the least recently served computer has been waiting.
     *
     * The oldest rather than the newest on purpose: with two computers paired, one
     * of them collecting every day must not be what keeps us quiet for the other.
     */
    private fun staleness(peers: List<Peer>): Long {
        val oldest = peers.minOfOrNull { it.lastInboundAt } ?: return Long.MAX_VALUE
        if (oldest == 0L) return Long.MAX_VALUE
        return (conditions.now() - oldest).coerceAtLeast(0)
    }

    /** True when at least one computer answered. */
    private suspend fun announce(peers: List<Peer>): Boolean {
        if (peers.isEmpty()) return false
        val port = serverPort()
        if (port <= 0) {
            lastOutcome = "the server is not up yet"
            return false
        }

        var any = false
        // Asked at most once a round, however many computers have moved: one
        // question is answered by all of them, and one sweep of a port serves
        // every computer listening on it.
        var heard: NodeDiscovery.Heard? = null
        val swept = HashMap<Int, List<String>>()

        for (peer in peers) {
            val body = JSONObject().apply {
                put("token", peer.token)
                // Our own to choose and our own to change. Because it rides on every
                // announcement, a port that moves heals itself without re-pairing.
                put("port", port)
            }.toString()

            when (say(peer, peer.lastAddress, body)) {
                Reply.Accepted -> {
                    any = true
                    continue
                }
                // It is exactly where we left it, and turned us away: nothing to look for.
                Reply.Refused -> continue
                Reply.Unanswered -> Unit
            }

            // Not where we last saw it. Ask the network where it went; the fingerprint
            // pin on each attempt is what decides whether we actually found it.
            val listened = heard ?: discovery.listen().also { heard = it }
            val named = listened.addressesOf(peer.nodeId)
            var found = named.firstOrNull { candidate ->
                candidate != peer.lastAddress && say(peer, candidate, body) == Reply.Accepted
            }

            // Knocking on every address is only for a computer mDNS did not name at
            // all. One it did name, that then did not answer, is up with its listener
            // down, and would not be found anywhere else either.
            if (found == null && named.isEmpty()) {
                found = swept.getOrPut(peer.lastPort) { discovery.sweep(peer.lastPort) }
                    .firstOrNull { candidate ->
                        candidate != peer.lastAddress && say(peer, candidate, body) == Reply.Accepted
                    }
            }
            if (found != null) {
                any = true
                Log.i(TAG, "${peer.displayName} had moved to $found")
            } else {
                db.peers().recordHelloFailed(peer.token, conditions.now())
            }
        }
        lastOutcome = if (any) "announced at ${conditions.now()}" else "nobody answered"
        return any
    }

    /** How one hello went. */
    private enum class Reply {
        Accepted,
        /** The computer we paired with answered `403`. */
        Refused,
        /** Nobody answered, something else did, or it answered something else. */
        Unanswered,
    }

    /**
     * One hello to one address.
     *
     * The socket work is blocking, and [start] keeps the whole loop on
     * [Dispatchers.IO] for that reason.
     */
    private suspend fun say(peer: Peer, address: String, body: String): Reply = try {
        when (
            val status = NodeClient.post(
                host = address,
                port = peer.lastPort,
                path = "/v1/hello",
                body = body,
                fingerprintHex = peer.certFingerprint,
                expectedName = peer.certName,
            )
        ) {
            204 -> {
                db.peers().recordHello(peer.token, address, peer.lastPort, conditions.now())
                Reply.Accepted
            }
            403 -> {
                refused(peer)
                Reply.Refused
            }
            else -> {
                Log.w(TAG, "${peer.displayName} answered $status")
                Reply.Unanswered
            }
        }
    } catch (e: IOException) {
        Reply.Unanswered
    } catch (e: java.security.GeneralSecurityException) {
        // Something is at that address, but it is not the computer we paired with.
        Reply.Unanswered
    }

    /**
     * It answered, and does not know us — or could not tell, this once.
     *
     * Counted from the [peer] this round started with, so a computer that turns
     * us away at two of its addresses in one round is one refusal, not two.
     */
    private suspend fun refused(peer: Peer) {
        val now = conditions.now()
        val run = HeartbeatPolicy.refused(peer.refusals, peer.refusedSince, now)
        db.peers().recordRefusal(peer.token, run.refusals, run.since, run.revoke, now)
        if (run.revoke) {
            // Pairing again is the fix now, and hammering it until somebody does would
            // only waste both our batteries.
            Log.w(TAG, "${peer.displayName} no longer recognises this phone")
        } else {
            Log.i(TAG, "${peer.displayName} turned this phone away (${run.refusals} in a row)")
        }
    }

    private companion object {
        const val TAG = "Heartbeat"
    }
}
