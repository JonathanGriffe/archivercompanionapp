package com.archiverclient.companion.net

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The lookup against a running libp2p node rather than against a reading of
 * its source.
 *
 * This is the check jmDNS never had. It parsed, it subscribed, it logged — and
 * against `libp2p-mdns` it could never produce an address, because libp2p sends
 * no SRV or A records and nothing on the Android side would ever say so. Here
 * the real responder answers the real question over a real interface.
 *
 * Skipped unless `-Dp2pnode=<binary>` names the harness in
 * `tools/verify-libp2p-mdns`. Run it with that directory's `run.sh`.
 */
class Libp2pMdnsTest {

    @Test
    fun `a running libp2p node is found by the peer id a pairing code carries`() {
        val binary = System.getProperty("p2pnode")?.takeIf { it.isNotEmpty() }?.let(::File)
        assumeTrue(
            "set -Dp2pnode to the built harness; see tools/verify-libp2p-mdns",
            binary != null && binary.canExecute(),
        )
        val lan = lanInterface()
        assumeTrue("no LAN interface to ask on", lan != null)
        val (via, address, subnet) = lan!!

        val node = ProcessBuilder(binary!!.absolutePath).redirectErrorStream(true).start()
        try {
            val said = LinkedBlockingQueue<String>()
            thread(isDaemon = true) {
                node.inputStream.bufferedReader().forEachLine { said.put(it) }
            }

            val peerId = said.poll(30, TimeUnit.SECONDS)?.removePrefix("peer ")
                ?: error("the node never said who it is")

            // Answers carry the addresses it listens on, so wait until one is on the
            // interface we are about to ask over.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var listening = false
            while (!listening && System.nanoTime() < deadline) {
                val line = said.poll(1, TimeUnit.SECONDS) ?: continue
                listening = line.startsWith("listening /ip4/$address/")
            }
            assertTrue("it never listened on $address", listening)

            // Its mDNS joins the group on its own schedule, so a first question can
            // land before anybody is there to hear it.
            var heard = emptyList<String>()
            var found = emptyList<String>()
            for (attempt in 1..3) {
                heard = P2pMdns.ask(via)
                found = P2pMdns.addressesOf(heard, peerId, subnet)
                if (found.isNotEmpty()) break
            }

            assertEquals("heard: $heard", listOf(address), found)
            assertTrue(
                "and nobody else's id matches it",
                P2pMdns.addressesOf(heard, "12D3KooWSomebodyElse", subnet).isEmpty(),
            )
        } finally {
            node.outputStream.close()
            if (!node.waitFor(5, TimeUnit.SECONDS)) node.destroyForcibly()
        }
    }

    /**
     * The interface that reaches the network, found the way the desktop finds
     * its own (`phone.rs::address`): ask the routing table, send nothing.
     */
    private fun lanInterface(): Triple<NetworkInterface, String, Subnet>? = runCatching {
        val local = DatagramSocket().use { probe ->
            probe.connect(InetSocketAddress("192.0.2.1", 9))
            probe.localAddress as? Inet4Address
        } ?: return null
        val via = NetworkInterface.getByInetAddress(local) ?: return null
        if (!via.isUp || via.isLoopback || !via.supportsMulticast()) return null
        val prefix = via.interfaceAddresses.first { it.address == local }.networkPrefixLength.toInt()
        val address = local.hostAddress ?: return null
        Triple(via, address, Subnet.of(address, prefix) ?: return null)
    }.getOrNull()
}
