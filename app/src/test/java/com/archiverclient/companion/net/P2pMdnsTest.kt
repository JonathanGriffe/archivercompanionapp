package com.archiverclient.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Held against `libp2p-mdns-0.48.0/src/behaviour/iface/dns.rs`, which is what
 * the desktop answers with. `tools/verify-libp2p-mdns` holds the same code
 * against a running libp2p node.
 */
class P2pMdnsTest {

    private val node = "12D3KooWRBhwfeP2Y4TCx1SM6s9rUoHhR5STiGwxBhgFRcw3UERE"
    private val other = "12D3KooWJWoaqZhDaoEFshF7Rh1bpY9ohihFhzcW6d69Lr2NASuq"

    @Test
    fun `the question is libp2p's own, byte for byte`() {
        // `build_query`, with the random id it never reads set to nought.
        val expected = "0000 0000 0001 0000 0000 0000" +
            "045f703270 045f756470 056c6f63616c 00" +
            "000c 0001"
        assertEquals(expected.replace(" ", ""), Names.hex(P2pMdns.query()))
        assertEquals(33, P2pMdns.query().size)
    }

    @Test
    fun `an answer is read the way libp2p writes it`() {
        val packet = libp2pAnswer(
            node,
            "/ip4/192.168.1.20/tcp/4001",
            "/ip4/127.0.0.1/tcp/4001",
            "/ip4/192.168.1.20/udp/4001/quic-v1",
        )

        assertEquals(
            listOf(
                "/ip4/192.168.1.20/tcp/4001/p2p/$node",
                "/ip4/127.0.0.1/tcp/4001/p2p/$node",
                "/ip4/192.168.1.20/udp/4001/quic-v1/p2p/$node",
            ),
            P2pMdns.multiaddrs(packet),
        )
    }

    @Test
    fun `a question is not an answer`() {
        // Ours comes back on the multicast loopback, and the desktop sends its own.
        assertTrue(P2pMdns.multiaddrs(P2pMdns.query()).isEmpty())
    }

    @Test
    fun `another responder's answer is stepped through rather than misread`() {
        // What a printer or a Chromecast sends: an echoed question, compression
        // pointers, an A record, and a TXT record carrying several strings.
        val out = Packet()
        out.u16(0)
        out.u16(0x8400)
        out.u16(1)
        out.u16(3)
        out.u16(0)
        out.u16(0)
        out.name("_p2p._udp.local")
        out.u16(12)
        out.u16(1)
        // A PTR whose name points back at the question (offset 12).
        out.pointer(12)
        out.u16(12)
        out.u16(1)
        out.u32(120)
        val target = Packet().apply { label("printer"); pointer(12) }.bytes()
        out.u16(target.size)
        out.raw(target)
        // A TXT under a pointer, with three strings, one of them ours.
        out.pointer(12)
        out.u16(16)
        out.u16(0x8001)
        out.u32(120)
        val strings = Packet().apply {
            string("txtvers=1")
            string("dnsaddr=/ip4/10.0.0.5/tcp/1/p2p/$node")
            string("")
        }.bytes()
        out.u16(strings.size)
        out.raw(strings)
        // An A record, which is not what libp2p sends and is not what we read.
        out.pointer(12)
        out.u16(1)
        out.u16(1)
        out.u32(120)
        out.u16(4)
        out.raw(byteArrayOf(10, 0, 0, 9))

        assertEquals(listOf("/ip4/10.0.0.5/tcp/1/p2p/$node"), P2pMdns.multiaddrs(out.bytes()))
    }

    @Test
    fun `a packet that breaks off keeps what came before the break`() {
        val whole = libp2pAnswer(node, "/ip4/192.168.1.20/tcp/1", "/ip4/192.168.1.21/tcp/2")
        // Somewhere inside the second TXT record.
        val cut = whole.copyOf(whole.size - 10)

        assertEquals(listOf("/ip4/192.168.1.20/tcp/1/p2p/$node"), P2pMdns.multiaddrs(cut))
        assertEquals(
            "the length handed in is what is read, not the buffer it sits in",
            listOf("/ip4/192.168.1.20/tcp/1/p2p/$node"),
            P2pMdns.multiaddrs(whole.copyOf(whole.size + 100), whole.size - 10),
        )
    }

    @Test
    fun `a string claiming more than its record holds is not read into the next`() {
        val out = Packet()
        out.u16(0)
        out.u16(0x8400)
        out.u16(0)
        out.u16(1)
        out.u16(0)
        out.u16(0)
        out.name("x.local")
        out.u16(16)
        out.u16(1)
        out.u32(1)
        out.u16(3)
        // Says 200 bytes; the record has room for two.
        out.raw(byteArrayOf(200.toByte(), 'd'.code.toByte(), 'n'.code.toByte()))
        out.raw("dnsaddr=/ip4/10.0.0.1/tcp/1/p2p/$node".toByteArray())

        assertTrue(P2pMdns.multiaddrs(out.bytes()).isEmpty())
    }

    @Test
    fun `nothing anyone sends makes the reader throw`() {
        val random = Random(20261002)
        val real = libp2pAnswer(node, "/ip4/192.168.1.20/tcp/1")
        repeat(5_000) {
            val junk = when (it % 2) {
                0 -> random.nextBytes(random.nextInt(0, 600))
                // A real answer with a few bytes flipped: the hard case.
                else -> real.copyOf().also { bytes ->
                    repeat(random.nextInt(1, 6)) { bytes[random.nextInt(bytes.size)] = random.nextInt().toByte() }
                }
            }
            P2pMdns.multiaddrs(junk)
        }
    }

    @Test
    fun `only the paired node's IPv4 addresses are taken from a multiaddr`() {
        assertEquals("192.168.1.20", P2pMdns.ipv4Of("/ip4/192.168.1.20/tcp/4001/p2p/$node", node))
        assertEquals(
            "192.168.1.20",
            P2pMdns.ipv4Of("/ip4/192.168.1.20/udp/4001/quic-v1/p2p/$node", node),
        )

        assertNull("another node", P2pMdns.ipv4Of("/ip4/192.168.1.20/tcp/4001/p2p/$other", node))
        assertNull("no peer id", P2pMdns.ipv4Of("/ip4/192.168.1.20/tcp/4001", node))
        assertNull("IPv6", P2pMdns.ipv4Of("/ip6/fe80::1/tcp/4001/p2p/$node", node))
        assertNull("not an address", P2pMdns.ipv4Of("/ip4/example.com/tcp/4001/p2p/$node", node))
        assertNull("not a multiaddr", P2pMdns.ipv4Of("ip4/192.168.1.20/p2p/$node", node))
        assertNull(P2pMdns.ipv4Of("", node))

        // A code with no peer id in it matches any node; the pin tells them apart.
        assertEquals("192.168.1.20", P2pMdns.ipv4Of("/ip4/192.168.1.20/tcp/1/p2p/$other", ""))
    }

    @Test
    fun `only addresses on the phone's own network are worth dialling`() {
        val advertised = listOf(
            "/ip4/127.0.0.1/tcp/4001/p2p/$node",
            "/ip4/172.17.0.1/tcp/4001/p2p/$node",
            "/ip4/192.168.1.20/tcp/4001/p2p/$node",
            "/ip4/192.168.1.20/udp/4001/quic-v1/p2p/$node",
            "/ip4/169.254.3.3/tcp/4001/p2p/$node",
            "/ip4/192.168.1.99/tcp/4001/p2p/$other",
        )
        val phone = Subnet.of("192.168.1.140", 24)!!

        assertEquals(listOf("192.168.1.20"), P2pMdns.addressesOf(advertised, node, phone))
        assertEquals(
            "with the network unknown, anything that could be a host",
            listOf("172.17.0.1", "192.168.1.20"),
            P2pMdns.addressesOf(advertised, node, null),
        )
        assertTrue(P2pMdns.addressesOf(emptyList(), node, phone).isEmpty())
    }

    /**
     * `query_response_packet` around `append_txt_record`, as libp2p builds an
     * answer: one PTR to a random peer name, then a TXT per address under that
     * name, with the cache-flush bit set on its class.
     */
    private fun libp2pAnswer(peerId: String, vararg addresses: String): ByteArray {
        val peerName = Packet().apply { name("k3b9xq7mzt2c8wj4vr6n1ps5hd0gyfle._p2p._udp.local") }.bytes()
        val out = Packet()
        out.u16(0)
        out.u16(0x8400)
        out.u16(0)
        out.u16(1)
        out.u16(0)
        out.u16(addresses.size)

        out.name("_p2p._udp.local")
        out.u16(0x000c)
        out.u16(0x0001)
        out.u32(360)
        out.u16(peerName.size)
        out.raw(peerName)

        for (address in addresses) {
            val value = "dnsaddr=$address/p2p/$peerId"
            out.raw(peerName)
            out.raw(byteArrayOf(0x00, 0x10, 0x80.toByte(), 0x01))
            out.u32(360)
            out.u16(1 + value.length)
            out.string(value)
        }
        return out.bytes()
    }

    private class Packet {
        private val out = ByteArrayOutputStream()

        fun u16(value: Int) {
            out.write(value ushr 8 and 0xFF)
            out.write(value and 0xFF)
        }

        fun u32(value: Long) {
            u16((value ushr 16).toInt())
            u16(value.toInt() and 0xFFFF)
        }

        fun raw(bytes: ByteArray) = out.write(bytes)

        fun label(text: String) {
            out.write(text.length)
            out.write(text.toByteArray(Charsets.US_ASCII))
        }

        fun name(dotted: String) {
            dotted.split('.').forEach(::label)
            out.write(0)
        }

        fun pointer(offset: Int) = u16(0xC000 or offset)

        fun string(text: String) {
            out.write(text.length)
            out.write(text.toByteArray(Charsets.US_ASCII))
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
