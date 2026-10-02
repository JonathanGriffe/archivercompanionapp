package com.archiverclient.companion.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LanSweepTest {

    @Test
    fun `a dotted quad round-trips and nothing else parses`() {
        for (address in listOf("192.168.1.20", "0.0.0.0", "255.255.255.255", "10.0.0.1")) {
            assertEquals(address, Ipv4.format(Ipv4.parse(address)!!))
        }
        for (junk in listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "a.b.c.d", " 1.2.3.4", "1..3.4", "1.2.3.-4", "1234.1.1.1")) {
            assertNull(junk, Ipv4.parse(junk))
        }
    }

    @Test
    fun `only somewhere a computer could be counts as a host`() {
        assertTrue(Ipv4.isHost(Ipv4.parse("192.168.1.20")!!))
        assertTrue(Ipv4.isHost(Ipv4.parse("10.0.0.1")!!))
        for (not in listOf("0.0.0.0", "127.0.0.1", "169.254.10.10", "224.0.0.251", "255.255.255.255")) {
            assertFalse(not, Ipv4.isHost(Ipv4.parse(not)!!))
        }
    }

    @Test
    fun `a subnet knows what is on it`() {
        val home = Subnet.of("192.168.1.140", 24)!!
        assertTrue(home.contains(Ipv4.parse("192.168.1.1")!!))
        assertTrue(home.contains(Ipv4.parse("192.168.1.254")!!))
        assertFalse(home.contains(Ipv4.parse("192.168.2.1")!!))
        assertTrue("a /0 holds everything", Subnet.of("10.0.0.1", 0)!!.contains(Ipv4.parse("8.8.8.8")!!))
        assertNull(Subnet.of("192.168.1.140", 33))
        assertNull(Subnet.of("nonsense", 24))
    }

    @Test
    fun `a sweep covers the phone's own slash 24 and nothing of it twice`() {
        val hosts = Subnet.of("192.168.1.140", 24)!!.neighbours()

        assertEquals("254 hosts, less the phone itself", 253, hosts.size)
        assertEquals(hosts.size, hosts.toSet().size)
        assertFalse("not the phone", "192.168.1.140" in hosts)
        assertFalse("not the network address", "192.168.1.0" in hosts)
        assertFalse("not broadcast", "192.168.1.255" in hosts)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `a larger network is swept no wider than the phone's own slash 24`() {
        val hosts = Subnet.of("172.16.40.7", 16)!!.neighbours()
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("172.16.40.") })
    }

    @Test
    fun `small networks are swept exactly`() {
        assertEquals(listOf("10.0.0.2"), Subnet.of("10.0.0.1", 30)!!.neighbours())
        assertEquals("a /31 has no network or broadcast address", listOf("10.0.0.0"), Subnet.of("10.0.0.1", 31)!!.neighbours())
        assertTrue("a /32 has no neighbours", Subnet.of("10.0.0.1", 32)!!.neighbours().isEmpty())
    }

    @Test
    fun `a sweep finds the host that is listening, and only that one`() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 50, loopback).use { listening ->
            val found = LanSweep.accepting(
                hosts = listOf("127.0.0.2", "127.0.0.1", "127.0.0.3"),
                port = listening.localPort,
            )
            assertEquals(listOf("127.0.0.1"), found)
        }
    }
}
