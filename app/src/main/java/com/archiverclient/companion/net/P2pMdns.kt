package com.archiverclient.companion.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Asking libp2p's mDNS where a desktop is.
 *
 * The desktop runs `libp2p-mdns`, which is not DNS-SD. Asked for
 * `_p2p._udp.local`, it answers with one PTR record and, beside it, one TXT
 * record per address it listens on:
 *
 * ```
 * dnsaddr=/ip4/192.168.1.20/tcp/4001/p2p/12D3KooW…
 * ```
 *
 * There is no SRV record and no A record, and it answers no question but that
 * one, so a general-purpose resolver — jmDNS, `NsdManager` — never gets as far
 * as an address. The TXT strings are enough on their own: an IP, and the peer id
 * the pairing code carried as `i=`, which says whose IP it is.
 *
 * Written against `libp2p-mdns-0.48.0/src/behaviour/iface/dns.rs` and
 * `iface.rs`, and checked against the real thing by
 * `tools/verify-libp2p-mdns`. Pure apart from [ask].
 */
object P2pMdns {

    const val GROUP = "224.0.0.251"
    const val PORT = 5353

    /** How long to gather answers. libp2p answers at once; wifi delivers multicast late. */
    const val WINDOW_MS = 3_000L

    /** When the question goes out again, for the copy wifi dropped. */
    private const val RESEND_MS = 1_000L

    /** RFC 6762's ceiling on an mDNS packet. */
    private const val MAX_PACKET = 9_000

    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val CLASS_IN = 1
    private const val DNSADDR = "dnsaddr="

    /** `dns.rs::build_query`: one question, `_p2p._udp.local` PTR, and nothing else. */
    fun query(): ByteArray {
        val out = ByteArrayOutputStream(33)
        fun u16(value: Int) {
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }
        // The id is libp2p's to ignore, and it does.
        u16(0)
        // A standard query.
        u16(0)
        // One question; no answers, authorities or additionals.
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        for (label in listOf("_p2p", "_udp", "local")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        u16(TYPE_PTR)
        u16(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Every multiaddr a packet advertises, `dnsaddr=` taken off.
     *
     * Empty for a question — ours comes straight back on the multicast loopback,
     * and the desktop asks its own every few minutes — and for anything that is
     * not DNS. Whatever was read before a packet turns out to be malformed is
     * kept: none of it is trusted anyway, because the pinned hello that follows
     * is what decides whether an address is the computer we paired with.
     */
    fun multiaddrs(packet: ByteArray, length: Int = packet.size): List<String> {
        val found = mutableListOf<String>()
        val read = Reader(packet, length.coerceAtMost(packet.size))
        try {
            read.skip(2)
            if (read.u16() and 0x8000 == 0) return emptyList()
            val questions = read.u16()
            val records = read.u16() + read.u16() + read.u16()

            repeat(questions) {
                read.skipName()
                read.skip(4)
            }
            repeat(records) {
                read.skipName()
                val type = read.u16()
                // Class, then TTL. libp2p sets the cache-flush bit on the class; neither matters here.
                read.skip(2 + 4)
                val end = read.within(read.u16())
                if (type == TYPE_TXT) {
                    while (read.at < end) {
                        val text = read.string(end)
                        if (text.startsWith(DNSADDR)) found += text.removePrefix(DNSADDR)
                    }
                }
                read.at = end
            }
        } catch (_: Malformed) {
            // See above: what came before the break is still worth a pinned try.
        }
        return found
    }

    /**
     * The IPv4 address in one advertised multiaddr, if it is [nodeId]'s.
     *
     * `/ip4/192.168.1.20/tcp/4001/p2p/<id>`. The port in it is libp2p's own and
     * is ignored: the phone listener is on another one, which the desktop keeps
     * across restarts and we already hold. An empty [nodeId] — a pairing code
     * that did not carry one — matches any node, leaving the pin to tell them
     * apart.
     */
    fun ipv4Of(multiaddr: String, nodeId: String): String? {
        val parts = multiaddr.split('/')
        if (parts.size < 5 || parts[0].isNotEmpty() || parts[1] != "ip4") return null
        if (parts[parts.size - 2] != "p2p") return null
        if (nodeId.isNotEmpty() && parts.last() != nodeId) return null
        return Ipv4.parse(parts[2])?.let(Ipv4::format)
    }

    /**
     * Where [nodeId] says it can be reached from [near], without repeats.
     *
     * libp2p lists every address it listens on — loopback, Docker bridges, a VPN —
     * and dialling one of those costs a connect timeout for nothing. mDNS only
     * crosses one link, so a desktop that answered has an address on the phone's
     * own network, and only those are kept. With [near] unknown, everything that
     * could be a host at all is.
     */
    fun addressesOf(multiaddrs: List<String>, nodeId: String, near: Subnet?): List<String> =
        multiaddrs
            .mapNotNull { ipv4Of(it, nodeId) }
            .distinct()
            .filter { address ->
                val number = Ipv4.parse(address) ?: return@filter false
                Ipv4.isHost(number) && (near == null || near.contains(number))
            }

    /**
     * Ask, and gather every multiaddr advertised in answer for [windowMs].
     *
     * Bound to 5353 rather than to a port of our own, because `libp2p-mdns`
     * sends every answer to the group on 5353 whatever port the question came
     * from (`iface.rs`, `mdns_socket`), rather than back to the asker as RFC 6762
     * §6.7 would have it. Sharing that port is ordinary — Android's own mDNS,
     * Play services and other apps may hold it too — and works because each of
     * them, this included, sets `SO_REUSEADDR` before binding: `MulticastSocket`
     * does that itself. Should something have taken it exclusively, this throws
     * and the caller treats it as having heard nothing.
     *
     * @param via the interface to ask on; null leaves it to the routing table.
     * @param prepare called on the socket before anything is sent. Android ties
     *   it to the wifi network here.
     */
    fun ask(
        via: NetworkInterface?,
        windowMs: Long = WINDOW_MS,
        prepare: (MulticastSocket) -> Unit = {},
    ): List<String> {
        // A literal, so no lookup.
        val group = InetAddress.getByName(GROUP)
        val heard = mutableListOf<String>()
        val question = query()
        val buffer = ByteArray(MAX_PACKET)

        MulticastSocket(InetSocketAddress(PORT)).use { socket ->
            prepare(socket)
            socket.timeToLive = 255
            if (via != null) socket.networkInterface = via
            socket.joinGroup(InetSocketAddress(group, PORT), via)

            val began = System.nanoTime()
            var asked = 0
            while (true) {
                val elapsed = (System.nanoTime() - began) / 1_000_000
                if (asked < 2 && elapsed >= asked * RESEND_MS) {
                    socket.send(DatagramPacket(question, question.size, group, PORT))
                    asked++
                    continue
                }
                val left = windowMs - elapsed
                if (left <= 0) break

                val untilResend = if (asked < 2) asked * RESEND_MS - elapsed else left
                socket.soTimeout = minOf(left, untilResend).coerceAtLeast(1).toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                heard += multiaddrs(buffer, packet.length)
            }
            runCatching { socket.leaveGroup(InetSocketAddress(group, PORT), via) }
        }
        return heard
    }

    private object Malformed : Exception()

    /** A cursor that refuses to step outside the packet, rather than one that has to be trusted not to. */
    private class Reader(private val bytes: ByteArray, private val limit: Int) {
        var at = 0

        fun within(length: Int): Int {
            val end = at + length
            if (end > limit) throw Malformed
            return end
        }

        fun skip(length: Int) {
            at = within(length)
        }

        fun u8(): Int {
            if (at >= limit) throw Malformed
            return bytes[at++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        /** One `<character-string>`: a length, then that many bytes, all before [end]. */
        fun string(end: Int): String {
            val length = u8()
            if (at + length > end) throw Malformed
            val text = String(bytes, at, length, Charsets.UTF_8)
            at += length
            return text
        }

        /**
         * Past a name, without reading it. A compression pointer ends one where
         * it stands, so nothing here ever jumps — and so nothing can loop.
         */
        fun skipName() {
            while (true) {
                val length = u8()
                when {
                    length == 0 -> return
                    length and 0xC0 == 0xC0 -> {
                        skip(1)
                        return
                    }
                    // 0x40 and 0x80 are label types nobody uses.
                    length and 0xC0 != 0 -> throw Malformed
                    else -> skip(length)
                }
            }
        }
    }
}
