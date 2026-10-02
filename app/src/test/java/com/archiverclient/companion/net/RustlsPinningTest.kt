package com.archiverclient.companion.net

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * The one property this side cannot check on its own: whether the desktop's
 * rustls will actually complete a handshake with the certificate we mint.
 *
 * It is worth the trouble because the failure mode is invisible from here. A
 * certificate that parses, names the right host and verifies under its own key
 * can still be refused outright — marking it `CA:true` earns
 * `invalid peer certificate: Other(CaUsedAsEndEntity)` — and nothing on the
 * Android side would ever say so. It is also the cheapest way to notice the
 * desktop's TLS stack drifting from the one this was built against.
 *
 * Skipped unless `-Dpincheck=<binary>` names the harness in
 * `tools/verify-rustls-pinning`. Run the lot with that directory's `run.sh`.
 */
@RunWith(RobolectricTestRunner::class)
class RustlsPinningTest {

    @Test
    fun `the desktop's rustls accepts the certificate we mint`() {
        val binary = System.getProperty("pincheck")?.let(::File)
        assumeTrue(
            "set -Dpincheck to the built harness; see tools/verify-rustls-pinning",
            binary != null && binary.canExecute(),
        )

        // The real thing, from the code that ships.
        val deviceId = DeviceIdentity.newDeviceId()
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val identity = Identity(
            deviceId,
            pair.private,
            DeviceIdentity.selfSign(Names.synthetic(deviceId), pair.public, pair.private),
        )

        val pem = File.createTempFile("phone-cert", ".pem").apply {
            deleteOnExit()
            writeText(identity.certificatePem())
        }

        val password = "probe".toCharArray()
        val socket = CompanionServer.serverSocketFactory(identity, password)
            .createServerSocket(0) as SSLServerSocket
        socket.soTimeout = 20_000

        var serverFailure: Throwable? = null
        val served = thread(start = true) {
            runCatching {
                (socket.accept() as SSLSocket).use { client ->
                    val reader = client.inputStream.bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val json = """{"items":[],"next":null}"""
                    client.outputStream.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${json.length}\r\nConnection: close\r\n\r\n$json")
                            .toByteArray()
                    )
                    client.outputStream.flush()
                }
            }.onFailure { serverFailure = it }
        }

        val process = ProcessBuilder(
            binary!!.absolutePath,
            pem.absolutePath,
            identity.certificateName,
            "127.0.0.1:${socket.localPort}",
            "a".repeat(64),
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        served.join(5_000)
        runCatching { socket.close() }

        assertTrue(
            "rustls refused the certificate: $output" +
                (serverFailure?.let { "\nserver side: $it" } ?: ""),
            output.startsWith("OK status=200"),
        )
    }
}
