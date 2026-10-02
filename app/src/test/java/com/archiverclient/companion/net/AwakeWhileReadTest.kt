package com.archiverclient.companion.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class AwakeWhileReadTest {

    private class Lock : AwakeWhileRead.Lock {
        val holds = mutableListOf<Long>()
        var releases = 0

        override fun hold(forMs: Long) {
            holds += forMs
        }

        override fun release() {
            releases++
        }
    }

    private var clock = 0L

    private fun stream(lock: Lock, size: Int = 1_000_000) =
        AwakeWhileRead(ByteArrayInputStream(ByteArray(size)), lock) { clock }

    @Test
    fun `the phone is held awake from the moment the body opens`() {
        val lock = Lock()
        stream(lock)
        assertEquals(listOf(AwakeWhileRead.STALL_MS), lock.holds)
    }

    @Test
    fun `bytes moving renew the hold, once a minute rather than once a chunk`() {
        val lock = Lock()
        val body = stream(lock)
        val chunk = ByteArray(1024)

        repeat(50) { body.read(chunk) }
        assertEquals("no renewal inside the first minute", 1, lock.holds.size)

        clock += AwakeWhileRead.RENEW_MS
        body.read(chunk)
        body.read(chunk)
        assertEquals("one renewal, however many chunks", 2, lock.holds.size)

        clock += AwakeWhileRead.RENEW_MS
        body.read()
        assertEquals("the one-byte read counts too", 3, lock.holds.size)
    }

    @Test
    fun `a body nobody reads lets the hold run out`() {
        val lock = Lock()
        stream(lock)
        clock += 10 * AwakeWhileRead.STALL_MS
        // Time passing without a read renews nothing: the last hold simply lapses.
        assertEquals(1, lock.holds.size)
    }

    @Test
    fun `the end of the file is not progress`() {
        val lock = Lock()
        val body = stream(lock, size = 0)
        clock += AwakeWhileRead.RENEW_MS
        assertEquals(-1, body.read(ByteArray(16)))
        assertEquals(-1, body.read())
        assertEquals(1, lock.holds.size)
    }

    @Test
    fun `closing lets go, once, however often it is closed`() {
        val lock = Lock()
        val body = stream(lock)
        body.close()
        body.close()
        assertEquals(1, lock.releases)
    }
}
