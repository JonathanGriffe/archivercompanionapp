package com.archiverclient.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Held against the Rust originals in `ac-import/src/sources/phone.rs`. If one of
 * these fails the two sides have drifted, and the symptom in the field would be
 * a TLS handshake that fails for no visible reason.
 */
class NamesTest {

    @Test
    fun `synthetic matches the desktop's own examples`() {
        // Both taken verbatim from the tests beside `phone.rs::synthetic`.
        assertEquals("pixel.phone.archiverclient", Names.synthetic("Pixel"))
        assertEquals(
            "jonathan-s-iphone-15.phone.archiverclient",
            Names.synthetic("Jonathan's iPhone 15"),
        )
    }

    @Test
    fun `a device id we generate survives the desktop's sanitiser untouched`() {
        // The whole reason ids are hex: the name in our certificate has to be the
        // name the desktop asks for, not merely one it would also have arrived at.
        repeat(200) {
            val id = DeviceIdentity.newDeviceId()
            assertEquals(16, id.length)
            assertEquals("$id${Names.PHONE_SUFFIX}", Names.synthetic(id))
        }
    }

    @Test
    fun `sanitising trims leading and trailing dashes as the desktop does`() {
        assertEquals("a.phone.archiverclient", Names.synthetic("--a--"))
        assertEquals("a-b.phone.archiverclient", Names.synthetic("!a!b!"))
    }

    @Test
    fun `percent encoding keeps only the unreserved set`() {
        assertEquals("abcABC123-_.~", Names.percentEncode("abcABC123-_.~"))
        // The desktop's own example: a slash is encoded, so an id stays one path segment.
        assertEquals("media-store%2F42%20891", Names.percentEncode("media-store/42 891"))
        assertEquals("external%3A17", Names.percentEncode("external:17"))
    }

    @Test
    fun `hex is lowercase and zero padded`() {
        assertEquals("000f10ff", Names.hex(byteArrayOf(0, 15, 16, -1)))
    }

    @Test
    fun `md5 of a known blob is what the desktop would compute`() {
        val digest = MessageDigest.getInstance("MD5").digest("hello".toByteArray())
        assertEquals("5d41402abc4b2a76b9719d911017c592", Names.hex(digest))
    }

    @Test
    fun `constant time comparison still compares`() {
        assertTrue(Names.constantTimeEquals("abc", "abc"))
        assertFalse(Names.constantTimeEquals("abc", "abd"))
        assertFalse(Names.constantTimeEquals("abc", "abcd"))
        assertTrue(Names.constantTimeEquals("", ""))
    }
}
