package com.archiverclient.companion.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetSocketAddress
import java.net.Socket

/** IPv4 addresses as the 32-bit numbers they are, for the arithmetic below. */
object Ipv4 {

    /**
     * A dotted quad, or null.
     *
     * Never `InetAddress.getByName`, which answers a string that is not a literal
     * by asking DNS — and these strings come off the network.
     */
    fun parse(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var out = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            out = (out shl 8) or octet
        }
        return out
    }

    fun format(address: Int): String =
        "${address ushr 24}.${address ushr 16 and 0xFF}.${address ushr 8 and 0xFF}.${address and 0xFF}"

    /**
     * Somewhere a computer could be: not "this network", loopback, link-local,
     * multicast or broadcast.
     */
    fun isHost(address: Int): Boolean {
        val first = address ushr 24
        val second = address ushr 16 and 0xFF
        return when {
            first == 0 || first == 127 || first >= 224 -> false
            first == 169 && second == 254 -> false
            else -> true
        }
    }
}

/** The network the phone is on, as wifi reports it: our own address and its prefix. */
data class Subnet(val address: Int, val prefixLength: Int) {

    init {
        require(prefixLength in 0..32) { "a prefix is 0 to 32 bits, not $prefixLength" }
    }

    fun contains(other: Int): Boolean {
        // Spelled out because `-1 shl 32` is `-1`: the shift count is taken mod 32.
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return other and mask == address and mask
    }

    /**
     * Every other address on this network worth knocking on.
     *
     * At most the phone's own /24. A home network is one, and anything larger —
     * a /16 is 65,534 hosts — would be a scan of somebody's whole network for a
     * computer that is almost always in the same /24 as the phone anyway.
     */
    fun neighbours(): List<String> {
        val prefix = maxOf(prefixLength, 24)
        val mask = -1 shl (32 - prefix)
        val network = address and mask
        val broadcast = network or mask.inv()
        // Within one /24 the sign bit never changes, so this range runs the right way.
        return (network..broadcast)
            .asSequence()
            .filter { it != address }
            // A /31 is two hosts and no network or broadcast address (RFC 3021).
            .filter { prefix >= 31 || (it != network && it != broadcast) }
            .filter(Ipv4::isHost)
            .map(Ipv4::format)
            .toList()
    }

    companion object {
        fun of(address: String, prefixLength: Int): Subnet? =
            Ipv4.parse(address)?.takeIf { prefixLength in 0..32 }?.let { Subnet(it, prefixLength) }
    }
}

/**
 * Which hosts accept a connection on a port, knocking on all of them at once.
 *
 * The fallback for when libp2p's mDNS names nowhere: it is switched off on that
 * computer, wifi ate the multicast, or something on this phone holds 5353
 * exclusively. Only a connect — nothing is sent. The few hosts that accept get a
 * pinned hello afterwards, and [NodeClient] writes the token only once the
 * handshake has proved the certificate, so a stranger that happens to listen on
 * the same port learns nothing.
 */
object LanSweep {

    /** A LAN answers in milliseconds; an address with nobody behind it never does. */
    const val CONNECT_TIMEOUT_MS = 300

    /** A /24 in about two and a half seconds when nobody is home. */
    const val PARALLEL = 32

    /** The hosts in [hosts] that accepted, in the order they were given. */
    suspend fun accepting(
        hosts: List<String>,
        port: Int,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        parallel: Int = PARALLEL,
        socket: () -> Socket = { Socket() },
    ): List<String> = coroutineScope {
        val gate = Semaphore(parallel)
        hosts.map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
                        host
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }
}
