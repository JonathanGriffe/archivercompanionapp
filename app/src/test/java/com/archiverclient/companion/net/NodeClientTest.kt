package com.archiverclient.companion.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The hello, over a real handshake to a listener on this machine standing in for
 * the desktop's: pinned by fingerprint, checked by name, and reporting the
 * address the desktop will take the phone to be at.
 */
@RunWith(RobolectricTestRunner::class)
class NodeClientTest {

    private lateinit var desktop: Identity
    private lateinit var listener: SSLServerSocket
    private val received = mutableListOf<String>()

    @Before
    fun listen() {
        // Any self-signed certificate will do for a desktop: the phone pins it by
        // fingerprint and nothing else.
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val name = "0123456789abcdef${Names.NODE_SUFFIX}"
        desktop = Identity("desktop", pair.private, DeviceIdentity.selfSign(name, pair.public, pair.private))

        listener = CompanionServer.serverSocketFactory(desktop, "probe".toCharArray())
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        listener.soTimeout = 10_000
        thread(isDaemon = true) {
            runCatching {
                (listener.accept() as SSLSocket).use { socket ->
                    val reader = socket.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        received += line
                        if (line.isEmpty()) break
                    }
                    socket.outputStream.write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                    socket.outputStream.flush()
                }
            }
        }
    }

    @After
    fun close() = listener.close()

    private val fingerprint: String
        get() = Names.hex(MessageDigest.getInstance("SHA-256").digest(desktop.certificate.encoded))

    private fun hello(pin: String = fingerprint) = NodeClient.post(
        host = "127.0.0.1",
        port = listener.localPort,
        path = "/v1/hello",
        body = """{"token":"t","port":8443}""",
        fingerprintHex = pin,
        expectedName = "0123456789abcdef${Names.NODE_SUFFIX}",
    )

    @Test
    fun `a hello says what was answered and where the desktop saw it come from`() {
        val answer = hello()

        assertEquals(204, answer.status)
        assertEquals(
            "the address the desktop reads off the socket, which is what it fetches from",
            "127.0.0.1",
            answer.from,
        )
        assertTrue(received.joinToString(), received.first().startsWith("POST /v1/hello HTTP/1.1"))
    }

    @Test
    fun `a certificate other than the pinned one is refused before anything is sent`() {
        try {
            hello(pin = "00".repeat(32))
            fail("an unpinned certificate was accepted")
        } catch (_: IOException) {
            // A handshake failure, which is an IOException on this stack.
        } catch (_: java.security.GeneralSecurityException) {
        }
        assertTrue("nothing reached the listener: $received", received.none { it.startsWith("POST") })
    }
}
