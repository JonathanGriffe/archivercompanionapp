package com.archiverclient.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * The certificate the desktop pins.
 *
 * Everything checked here is something rustls checks on the other side, where a
 * failure shows up only as a handshake that did not complete.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityTest {

    private fun identity(deviceId: String = DeviceIdentity.newDeviceId()): Identity {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val cert = DeviceIdentity.selfSign(Names.synthetic(deviceId), pair.public, pair.private)
        return Identity(deviceId, pair.private, cert)
    }

    @Test
    fun `the certificate names exactly what the desktop will ask for`() {
        val id = identity("0123456789abcdef")
        assertEquals("0123456789abcdef.phone.archiverclient", id.certificateName)

        val sans = id.certificate.subjectAlternativeNames.orEmpty()
            .mapNotNull { it.getOrNull(1) as? String }
        assertTrue(
            "SAN was $sans",
            "0123456789abcdef.phone.archiverclient" in sans,
        )
    }

    /**
     * The desktop pins this one certificate as its sole trust anchor and then
     * validates the same certificate against it. That works only while it is an
     * end entity: marking it `CA:true`, which is the intuitive thing to do for
     * something being used as a root, makes rustls refuse the handshake with
     * `invalid peer certificate: Other(CaUsedAsEndEntity)`.
     *
     * This assertion is the cheap guard on that. The expensive one — a real
     * rustls handshake — lives in `tools/verify-rustls-pinning`.
     */
    @Test
    fun `the certificate is an end entity, which is what rustls will accept`() {
        val cert = identity().certificate
        assertEquals(
            "CA:true would fail the handshake with CaUsedAsEndEntity",
            -1,
            cert.basicConstraints,
        )
        // keyUsage: [0] digitalSignature ... [5] keyCertSign
        val usage = cert.keyUsage
        assertTrue("digitalSignature", usage[0])
        assertFalse("a non-CA must not claim keyCertSign", usage[5])
        assertTrue("serverAuth", "1.3.6.1.5.5.7.3.1" in cert.extendedKeyUsage.orEmpty())
        // Self-signed: issuer is subject, and it verifies under its own key.
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        cert.verify(cert.publicKey)
    }

    @Test
    fun `the pem round-trips back to the same certificate`() {
        val id = identity()
        val pem = id.certificatePem()
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))

        val der = android.util.Base64.decode(
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\n", ""),
            android.util.Base64.DEFAULT,
        )
        assertEquals(
            Names.hex(MessageDigest.getInstance("SHA-256").digest(id.certificate.encoded)),
            Names.hex(MessageDigest.getInstance("SHA-256").digest(der)),
        )
    }

    @Test
    fun `the keystore holds a usable private key entry`() {
        val id = identity()
        val password = "test-password".toCharArray()
        val store = id.keyStore(password)
        assertTrue(store.isKeyEntry(Identity.ALIAS))
        assertEquals(1, store.getCertificateChain(Identity.ALIAS).size)
    }

    @Test
    fun `it is valid now and for years to come`() {
        val cert = identity().certificate
        cert.checkValidity()
        cert.checkValidity(java.util.Date(System.currentTimeMillis() + 3000L * 24 * 3600 * 1000))
    }
}
