package com.archiverclient.companion.net

import android.content.Context
import android.util.Log
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Answering the code on the desktop's screen.
 *
 * One exchange, not a conversation: everything needed is in the QR, and none of
 * it crossed the network to get here — it crossed a room, which is the one
 * channel nothing on the wifi can listen to.
 *
 * The desktop gives us three minutes and will only accept one answer, so a
 * failure here means showing a fresh code rather than trying again.
 */
class Pairing(private val context: Context) {

    private val db = AppDatabase.of(context)

    sealed interface Outcome {
        data class Paired(val peer: Peer) : Outcome
        /** The desktop said no: the code lapsed, was used, or was never open. */
        data object Refused : Outcome
        data class Unreachable(val reason: String) : Outcome
        data object NotAPairingCode : Outcome
    }

    suspend fun pair(rawQr: String, serverPort: Int): Outcome = withContext(Dispatchers.IO) {
        val code = QrPayload.parse(rawQr) ?: return@withContext Outcome.NotAPairingCode
        val identity = DeviceIdentity.of(context)

        val body = JSONObject().apply {
            put("token", code.token)
            // The id, not the name: the desktop binds a source to this for ever, and a
            // name is a display string that two phones can share.
            put("id", identity.deviceId)
            put("name", DeviceIdentity.displayName())
            put("port", serverPort)
            put("cert", identity.certificatePem())
        }.toString()

        val answer = try {
            NodeClient.post(
                host = code.address,
                port = code.port,
                path = "/v1/pair",
                body = body,
                fingerprintHex = code.fingerprint,
                expectedName = code.certificateName,
            )
        } catch (e: IOException) {
            Log.w(TAG, "could not reach the computer that showed the code", e)
            return@withContext Outcome.Unreachable(e.message ?: "it did not answer")
        } catch (e: java.security.GeneralSecurityException) {
            Log.w(TAG, "the computer's certificate was not the one on screen", e)
            return@withContext Outcome.Unreachable("its certificate was not the one on screen")
        }

        if (answer.status != 204) return@withContext Outcome.Refused

        val peer = Peer(
            token = code.token,
            nodeId = code.nodeId,
            certFingerprint = code.fingerprint,
            certName = code.certificateName,
            displayName = code.certificateName.substringBefore(Names.NODE_SUFFIX),
            lastAddress = code.address,
            lastPort = code.port,
            pairedAt = System.currentTimeMillis(),
            // The desktop wrote down where the pairing came from, so that is where
            // it will fetch from until something tells it otherwise.
            lastHelloFrom = answer.from,
        )
        db.peers().put(peer)
        Outcome.Paired(peer)
    }

    private companion object {
        const val TAG = "Pairing"
    }
}
