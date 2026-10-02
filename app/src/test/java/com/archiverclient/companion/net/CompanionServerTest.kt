package com.archiverclient.companion.net

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.MediaItem
import com.archiverclient.companion.data.Peer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * What the desktop actually reads off the wire.
 *
 * The client here is pinned the way `phone.rs::pinned` pins, and every assertion
 * is about a field name or a status code the desktop's `serde` derive or its
 * `fetch` arm depends on. The JSON shape in particular is unforgiving: a page
 * missing `id` or `name` is rejected whole, with the desktop reporting only that
 * "this phone answered with nonsense".
 */
@RunWith(RobolectricTestRunner::class)
class CompanionServerTest {

    private lateinit var db: AppDatabase
    private lateinit var server: CompanionServer
    private lateinit var identity: Identity
    private var inbound = mutableListOf<String>()

    private val token = "t".repeat(64)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()

        val deviceId = DeviceIdentity.newDeviceId()
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        identity = Identity(
            deviceId,
            pair.private,
            DeviceIdentity.selfSign(Names.synthetic(deviceId), pair.public, pair.private),
        )

        runBlocking {
            db.peers().put(
                Peer(
                    token = token,
                    nodeId = "12D3KooWtest",
                    certFingerprint = "f".repeat(64),
                    certName = "abc.node.archiverclient",
                    displayName = "desk",
                    lastAddress = "192.168.1.2",
                    lastPort = 4000,
                    pairedAt = 1,
                )
            )
        }

        server = CompanionServer(context, db, 0) { inbound += it }
        val password = "probe".toCharArray()
        server.makeSecure(CompanionServer.serverSocketFactory(identity, password), null)
        server.start(5000, true)
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
    }

    private fun item(id: Long, md5: String? = null, size: Long? = null) = MediaItem(
        id = "external_primary:$id",
        mediaStoreId = id,
        volume = "external_primary",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        bucket = "Camera",
        storeSize = 4_000_000 + id,
        dateModified = id,
        mimeType = "image/jpeg",
        isVideo = false,
        md5 = md5,
        hashedSize = size,
    )

    /** Pinned exactly as the desktop pins: this certificate, and nothing else. */
    private fun get(path: String, bearer: String? = token): Pair<Int, String> {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, a: String?) {
                if (chain?.firstOrNull()?.encoded?.contentEquals(identity.certificate.encoded) != true) {
                    throw java.security.cert.CertificateException("not the pinned certificate")
                }
            }
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trust), null)
        }
        val url = java.net.URL("https://localhost:${server.listeningPort}$path")
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ctx.socketFactory
            // The certificate names a synthetic host nothing resolves; pinning is the
            // check that matters, exactly as it is on the desktop.
            setHostnameVerifier { _, _ -> true }
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            connectTimeout = 5000
            readTimeout = 5000
        }
        val code = conn.responseCode
        // Tolerant on purpose. A `/bytes` route under Robolectric reaches a stream that
        // is not a real file, so the body can end early; the status line has already
        // told us what the route decided, which is what these tests are about.
        val body = runCatching {
            (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
        }.getOrDefault("")
        conn.disconnect()
        return code to body
    }

    @Test
    fun `a listing carries exactly the field names the desktop deserialises`() {
        runBlocking {
            db.media().upsert(item(1, md5 = "5d41402abc4b2a76b9719d911017c592", size = 1234), 1)
        }
        val (code, body) = get("/v1/items?limit=200")
        assertEquals(200, code)

        val json = JSONObject(body)
        val entry = json.getJSONArray("items").getJSONObject(0)
        assertEquals("external_primary:1", entry.getString("id"))
        assertEquals("IMG_1.jpg", entry.getString("name"))
        assertEquals("Camera", entry.getString("folder"))
        assertEquals(1234L, entry.getLong("size"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", entry.getString("md5"))
        assertTrue("the last page must say so", json.isNull("next"))
    }

    /**
     * A size or digest the desktop cannot match makes it discard the download and
     * retry three times an hour apart. Saying nothing costs only an unverified
     * copy, so an item that has not been hashed publishes neither field.
     */
    @Test
    fun `an unhashed item offers neither a size nor a digest`() {
        runBlocking { db.media().upsert(item(1), 1) }
        val entry = JSONObject(get("/v1/items").second).getJSONArray("items").getJSONObject(0)
        assertTrue(entry.has("id"))
        assertFalse("a stale MediaStore size must not be published", entry.has("size"))
        assertFalse(entry.has("md5"))
    }

    @Test
    fun `paging walks the whole library and then stops`() {
        runBlocking { (1L..7L).forEach { db.media().upsert(item(it), 1) } }

        val seen = mutableListOf<String>()
        var after: String? = null
        var pages = 0
        while (true) {
            val query = "/v1/items?limit=3" + (after?.let { "&after=$it" } ?: "")
            val json = JSONObject(get(query).second)
            val items = json.getJSONArray("items")
            for (i in 0 until items.length()) seen += items.getJSONObject(i).getString("id")
            pages++
            if (json.isNull("next")) break
            after = json.getString("next")
            check(pages < 20) { "paging did not terminate" }
        }
        assertEquals(7, seen.size)
        assertEquals(7, seen.toSet().size)
    }

    @Test
    fun `an empty library is a valid page rather than an error`() {
        val (code, body) = get("/v1/items")
        assertEquals(200, code)
        val json = JSONObject(body)
        assertEquals(0, json.getJSONArray("items").length())
        assertTrue(json.isNull("next"))
    }

    @Test
    fun `limit is honoured and clamped`() {
        runBlocking { (1L..12L).forEach { db.media().upsert(item(it), 1) } }
        assertEquals(
            5,
            JSONObject(get("/v1/items?limit=5").second).getJSONArray("items").length(),
        )
        // Nonsense falls back to the default rather than failing the scan.
        assertEquals(
            12,
            JSONObject(get("/v1/items?limit=nonsense").second).getJSONArray("items").length(),
        )
    }

    @Test
    fun `no token and a wrong token are both refused`() {
        assertEquals(401, get("/v1/items", bearer = null).first)
        assertEquals(401, get("/v1/items", bearer = "w".repeat(64)).first)
        assertEquals(401, get("/v1/items", bearer = "").first)
    }

    @Test
    fun `a request from a paired computer is noted as proof it is up`() {
        get("/v1/items")
        assertEquals(listOf(token), inbound)
    }

    /**
     * The desktop reads `404` as "deleted since the scan" and retires the row
     * rather than retrying it, which is the right outcome for a photograph that
     * has genuinely gone.
     */
    @Test
    fun `bytes for an item we do not have is a 404`() {
        assertEquals(404, get("/v1/items/external_primary:999/bytes").first)
    }

    @Test
    fun `an unknown path is a 404, not a listing`() {
        assertEquals(404, get("/v1/nonsense").first)
        assertEquals(404, get("/").first)
    }

    /**
     * `phone.rs::encode` percent-encodes the colon in our ids, so every fetch
     * arrives as `external_primary%3A1`. NanoHTTPD decodes it before we see it,
     * and the row has to be found under the id we actually published.
     *
     * The tell is the status: an id that failed to decode would not match a row
     * and would answer `404` without ever opening anything.
     */
    @Test
    fun `an id arrives percent-decoded, as the desktop encoded it`() {
        runBlocking { db.media().upsert(item(1), 1) }
        assertEquals(
            HttpURLConnection.HTTP_OK,
            get("/v1/items/external_primary%3A1/bytes").first,
        )
        assertEquals(404, get("/v1/items/external_primary%3A999/bytes").first)
    }

    @Test
    fun `a retired item is not offered`() {
        runBlocking {
            db.media().upsert(item(1), 1)
            db.media().markGone("external_primary:1")
        }
        assertEquals(0, JSONObject(get("/v1/items").second).getJSONArray("items").length())
    }

    @Test
    fun `the folder is empty rather than absent for the camera roll itself`() {
        runBlocking { db.media().upsert(item(1).copy(bucket = ""), 1) }
        val entry = JSONObject(get("/v1/items").second).getJSONArray("items").getJSONObject(0)
        assertEquals("", entry.getString("folder"))
        assertNull(null)
    }
}
