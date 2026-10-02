package com.archiverclient.companion.net

import android.content.Context
import android.util.Log
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.MediaItem
import com.archiverclient.companion.data.Settings
import com.archiverclient.companion.media.Media
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.ServerSocket
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * The two calls the desktop makes to this phone.
 *
 * Over TLS with our own certificate, which the desktop pinned at pairing as the
 * only root it will accept for this device — so there is no certificate
 * authority in the picture and nothing to configure. Authorisation is the
 * pairing token, presented as a bearer.
 *
 * The bytes endpoint streams. A camera roll has files measured in gigabytes and
 * none of them should ever be held whole in memory.
 */
class CompanionServer(
    private val context: Context,
    private val db: AppDatabase,
    port: Int,
    private val onInbound: (token: String) -> Unit,
) : NanoHTTPD(port) {

    /** How many items one page may carry. The desktop asks for 200. */
    private val defaultLimit = 200
    private val maxLimit = 500

    override fun serve(session: IHTTPSession): Response {
        return try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "failed answering ${session.uri}", e)
            text(Response.Status.INTERNAL_ERROR, "no")
        }
    }

    private fun route(session: IHTTPSession): Response {
        if (session.method != Method.GET) return text(Response.Status.NOT_FOUND, "no")

        // NanoHTTPD has already percent-decoded this, so an id arrives as the phone
        // chose it rather than as the desktop encoded it.
        val path = session.uri.orEmpty()

        val token = bearer(session) ?: return unauthorised()
        val peer = runBlocking { db.peers().byToken(token) } ?: return unauthorised()
        onInbound(peer.token)

        return when {
            path == "/v1/items" -> items(session)
            path.startsWith("/v1/items/") && path.endsWith("/bytes") ->
                bytes(path.removePrefix("/v1/items/").removeSuffix("/bytes"))
            else -> text(Response.Status.NOT_FOUND, "no")
        }
    }

    /**
     * One page of the whole library.
     *
     * The desktop does not keep a cursor between scans: it walks the entire
     * listing every time and retires whatever it did not see. So this has to
     * enumerate everything present, in a stable order, and must not leave an item
     * out merely because nobody has hashed it yet.
     */
    private fun items(session: IHTTPSession): Response {
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()
            ?.coerceIn(1, maxLimit) ?: defaultLimit
        val after = session.parameters["after"]?.firstOrNull().orEmpty()

        val page = runBlocking { db.media().page(after, limit) }
        val array = JSONArray()
        for (item in page) array.put(json(item))

        val body = JSONObject().apply {
            put("items", array)
            // A short page is the last one. Anything else and the desktop asks again
            // with the last id it saw.
            put("next", if (page.size < limit) JSONObject.NULL else page.last().id)
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            body.toString(),
        )
    }

    private fun json(item: MediaItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("name", item.displayName)
        put("folder", item.bucket)
        // Published together or not at all. The desktop throws away a download whose
        // size or digest does not match what it was promised, so a stale MediaStore
        // SIZE is worse than saying nothing: silence only costs an unverified copy.
        if (item.md5 != null && item.hashedSize != null) {
            put("size", item.hashedSize)
            put("md5", item.md5)
        }
    }

    /**
     * The bytes themselves.
     *
     * A `404` is not a failure here — it is how the desktop learns a photograph
     * has been deleted since the scan, and it retires the row rather than
     * retrying. So a file we can no longer open is reported as gone in the index
     * too, and drops out of the next listing.
     */
    private fun bytes(rawId: String): Response {
        if (rawId.isEmpty() || rawId.contains('/')) {
            return text(Response.Status.NOT_FOUND, "no")
        }
        val item = runBlocking { db.media().byId(rawId) }
            ?: return text(Response.Status.NOT_FOUND, "no")

        val opened: InputStream = try {
            Media.open(context, item) ?: return gone(item)
        } catch (e: SecurityException) {
            // Not the same as gone: the permission may come back, and retiring the row
            // would have the desktop forget a photograph that is still on the phone. A
            // 500 is retried an hour later instead.
            Log.w(TAG, "not allowed to read ${item.displayName}", e)
            return text(Response.Status.INTERNAL_ERROR, "no")
        }
        // Held awake until NanoHTTPD closes it, after the last byte or a failure.
        val stream = AwakeWhileRead.of(context, opened)

        val mime = item.mimeType.ifBlank { "application/octet-stream" }

        // A `Content-Length` that turns out to be a lie is worse than none at all:
        // the desktop would either stall waiting for bytes that are not coming or
        // take a truncated file. So a length is only promised when the hashing pass
        // measured it, and MediaStore's own `SIZE` — which goes stale when a file is
        // edited in place — is never used for it. Everything else is chunked.
        return when (val length = item.hashedSize) {
            null -> newChunkedResponse(Response.Status.OK, mime, stream)
            else -> newFixedLengthResponse(Response.Status.OK, mime, stream, length)
        }
    }

    private fun gone(item: MediaItem): Response {
        runBlocking { db.media().markGone(item.id) }
        return text(Response.Status.NOT_FOUND, "no")
    }

    /**
     * The token off the `Authorization` header.
     *
     * NanoHTTPD lower-cases header names, which is the only reason this can look
     * a single key up rather than search.
     */
    private fun bearer(session: IHTTPSession): String? {
        val header = session.headers["authorization"]?.trim() ?: return null
        if (!header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        return header.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    private fun unauthorised() = text(Response.Status.UNAUTHORIZED, "no")

    private fun text(status: Response.IStatus, body: String) =
        newFixedLengthResponse(status, MIME_PLAINTEXT, body)

    companion object {
        private const val TAG = "CompanionServer"

        /**
         * Raise the server, preferring the port it had last time.
         *
         * Reusing it means a computer that already knows where we are can reach us
         * before we have said anything. It is not load-bearing: every hello carries
         * the port, and the desktop rewrites what it holds whenever it changes, so
         * a port that has moved heals itself on the next announcement.
         */
        fun start(
            context: Context,
            identity: Identity,
            settings: Settings,
            onInbound: (String) -> Unit,
        ): CompanionServer {
            val password = CharArray(24).also { chars ->
                val random = SecureRandom()
                for (i in chars.indices) chars[i] = ('a' + random.nextInt(26))
            }
            val factory = serverSocketFactory(identity, password)

            val preferred = settings.serverPort
            val port = if (preferred in 1..65535 && available(preferred)) preferred else ephemeral()

            val server = CompanionServer(context, AppDatabase.of(context), port, onInbound)
            server.makeSecure(factory, null)
            server.start(SOCKET_READ_TIMEOUT, true)
            settings.serverPort = server.listeningPort
            Log.i(TAG, "serving the camera roll on ${server.listeningPort}")
            return server
        }

        /**
         * Internal rather than private so `RustlsPinningTest` can raise a socket
         * through the very code that ships, instead of a reconstruction of it.
         */
        internal fun serverSocketFactory(
            identity: Identity,
            password: CharArray,
        ): SSLServerSocketFactory {
            val store = identity.keyStore(password)
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(store, password) }
            return SSLContext.getInstance("TLS")
                .apply { init(managers.keyManagers, null, null) }
                .serverSocketFactory
        }

        private fun available(port: Int): Boolean =
            runCatching { ServerSocket(port).close() }.isSuccess

        /** Ask the kernel for one, then let go of it so NanoHTTPD can take it. */
        private fun ephemeral(): Int =
            ServerSocket(0).use { it.localPort }
    }
}
