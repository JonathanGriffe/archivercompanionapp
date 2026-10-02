package com.archiverclient.companion.net

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The two calls this phone makes to a desktop, written out by hand.
 *
 * Deliberately not an HTTP client library. The desktop's listener
 * (`phone/listener.rs`) reads a request line, headers, and exactly as many body
 * bytes as `Content-Length` promised — no chunked encoding, no second request on
 * the same connection — and answers `400` to anything else. A pooling client
 * would eventually reuse a socket the desktop has already closed, so we open
 * one connection per request and say `Connection: close` out loud.
 *
 * Trust is a single pinned fingerprint read off a QR code. No certificate
 * authority is consulted and there is no trust-on-first-use prompt, because
 * there is nothing to prompt about: either the certificate is the one the screen
 * showed or we are talking to somebody else.
 */
object NodeClient {

    /** Hard ceilings mirroring the desktop's own. Its answers are `204` or a word. */
    private const val MAX_RESPONSE_LINE = 4096
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    /**
     * Lint objects to a hand-written trust manager, and is usually right to. Here
     * the whole point is to trust one certificate and nothing else: the desktop
     * has no CA, its name resolves nowhere, and the fingerprint came off a screen
     * in the same room. Falling back to the platform's validation would be
     * strictly worse — it would accept any certificate a public authority signed.
     */
    @Suppress("CustomX509TrustManager")
    class Pinned(private val fingerprintHex: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // We are the client. Nothing ever presents one to us here.
            throw java.security.cert.CertificateException("no client certificates are accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("the server sent no certificate")
            val actual = Names.hex(MessageDigest.getInstance("SHA-256").digest(leaf.encoded))
            if (!Names.constantTimeEquals(actual, fingerprintHex)) {
                throw java.security.cert.CertificateException(
                    "this is not the computer the pairing code named"
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun factory(fingerprintHex: String): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(Pinned(fingerprintHex)), null)
        }.socketFactory

    /**
     * `POST` one small JSON body and answer with the status code.
     *
     * @throws IOException if the computer cannot be reached, or answers something
     *   that is not an HTTP status line.
     */
    fun post(
        host: String,
        port: Int,
        path: String,
        body: String,
        fingerprintHex: String,
        expectedName: String,
    ): Int {
        val bytes = body.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024) { "the desktop refuses a body over 8 KiB" }

        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        plain.soTimeout = READ_TIMEOUT_MS

        val socket = factory(fingerprintHex).createSocket(plain, host, port, true) as SSLSocket
        socket.use {
            // The certificate names something no resolver in the world would answer for,
            // so the name has to be carried in SNI rather than found from the address.
            it.sslParameters = it.sslParameters.apply {
                serverNames = listOf(SNIHostName(expectedName))
            }
            it.startHandshake()
            verifyName(it, expectedName)

            val head = buildString {
                append("POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(expectedName).append("\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = it.outputStream
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(bytes)
            out.flush()

            return status(BufferedInputStream(it.inputStream))
        }
    }

    /**
     * The certificate has already been pinned by fingerprint, so this cannot
     * catch an impostor. It catches us dialling the wrong one of two paired
     * computers, which pins alone would let through.
     */
    private fun verifyName(socket: SSLSocket, expected: String) {
        val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: return
        val names = leaf.subjectAlternativeNames.orEmpty()
            .mapNotNull { entry -> (entry.getOrNull(1) as? String)?.lowercase() }
        if (names.isNotEmpty() && expected.lowercase() !in names) {
            throw IOException("that computer's certificate names ${names.joinToString()}")
        }
    }

    /** The status code off the first line, and nothing else read. */
    private fun status(input: BufferedInputStream): Int {
        val line = StringBuilder()
        while (line.length < MAX_RESPONSE_LINE) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        val parts = line.toString().split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw IOException("that computer did not answer with HTTP")
        }
        return parts[1].toIntOrNull() ?: throw IOException("unreadable status: $line")
    }
}
