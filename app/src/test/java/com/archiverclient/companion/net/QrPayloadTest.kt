package com.archiverclient.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrPayloadTest {

    /** Assembled exactly as `phone.rs::code` does, values percent-encoded. */
    private fun code(
        address: String = "192.168.1.24",
        port: Int = 43117,
        nodeId: String = "12D3KooWveryLongPeerIdGoesHere",
        token: String = "a".repeat(64),
        fingerprint: String = "b".repeat(64),
        name: String = "0123456789abcdef.node.archiverclient",
    ) = "ac://pair?v=1" +
        "&a=${Names.percentEncode(address)}" +
        "&p=$port" +
        "&i=${Names.percentEncode(nodeId)}" +
        "&t=${Names.percentEncode(token)}" +
        "&f=${Names.percentEncode(fingerprint)}" +
        "&n=${Names.percentEncode(name)}"

    @Test
    fun `the desktop's code round-trips`() {
        val parsed = QrPayload.parse(code())!!
        assertEquals("192.168.1.24", parsed.address)
        assertEquals(43117, parsed.port)
        assertEquals("12D3KooWveryLongPeerIdGoesHere", parsed.nodeId)
        assertEquals("a".repeat(64), parsed.token)
        assertEquals("b".repeat(64), parsed.fingerprint)
        assertEquals("0123456789abcdef.node.archiverclient", parsed.certificateName)
    }

    @Test
    fun `surrounding whitespace from a paste is tolerated`() {
        assertEquals(43117, QrPayload.parse("  ${code()}\n")!!.port)
    }

    /**
     * The desktop may not know its own peer id when it draws the code, and says
     * so by leaving the field empty. That is not a reason to refuse to pair —
     * discovery falls back to trying candidates and checking the fingerprint.
     */
    @Test
    fun `an empty peer id is still a valid code`() {
        assertEquals("", QrPayload.parse(code(nodeId = ""))!!.nodeId)
    }

    @Test
    fun `a version we do not understand is refused rather than guessed at`() {
        assertNull(QrPayload.parse(code().replace("v=1", "v=2")))
        assertNull(QrPayload.parse(code().replace("v=1&", "")))
    }

    @Test
    fun `anything that is not a pairing code is refused`() {
        assertNull(QrPayload.parse("https://example.com/"))
        assertNull(QrPayload.parse("ac://something-else?v=1"))
        assertNull(QrPayload.parse(""))
        assertNull(QrPayload.parse("WIFI:S=home;T=WPA;P=hunter2;;"))
    }

    @Test
    fun `a fingerprint that is not 64 hex characters is refused`() {
        assertNull(QrPayload.parse(code(fingerprint = "b".repeat(63))))
        assertNull(QrPayload.parse(code(fingerprint = "z".repeat(64))))
    }

    @Test
    fun `a port outside the range is refused`() {
        assertNull(QrPayload.parse(code(port = 0)))
        assertNull(QrPayload.parse(code(port = 70000)))
    }
}
