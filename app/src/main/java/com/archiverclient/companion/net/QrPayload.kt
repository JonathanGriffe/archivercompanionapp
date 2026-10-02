package com.archiverclient.companion.net

import android.net.Uri

/**
 * What the desktop puts on screen, parsed.
 *
 * The whole of pairing crosses the room rather than the network: where the node
 * is answering now, how to recognise its certificate, and the token it will ask
 * for ever after. Produced by `phone.rs::code`:
 *
 * ```
 * ac://pair?v=1&a=<ip>&p=<port>&i=<peer id>&t=<token>&f=<fingerprint>&n=<cert name>
 * ```
 */
data class QrPayload(
    /** Where the desktop is answering right now. */
    val address: String,
    val port: Int,
    /** Its libp2p peer id. May be empty — the desktop tolerates not knowing its own. */
    val nodeId: String,
    /** 64 hex characters. Becomes the bearer token in both directions. */
    val token: String,
    /** `hex(SHA-256(certificate DER))`. The only reason to trust what answers. */
    val fingerprint: String,
    /** `<16 hex>.node.archiverclient`, the name its certificate is issued for. */
    val certificateName: String,
) {
    companion object {
        const val SCHEME = "ac"
        const val HOST = "pair"

        /** Null for anything that is not a pairing code we understand. */
        fun parse(raw: String): QrPayload? {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
            if (!HOST.equals(uri.host, ignoreCase = true)) return null
            // Refused rather than guessed at. A future version may mean anything, and a
            // half-understood pairing is worse than a message asking the user to update.
            if (uri.getQueryParameter("v") != "1") return null

            val address = uri.getQueryParameter("a")?.takeIf { it.isNotBlank() } ?: return null
            val port = uri.getQueryParameter("p")?.toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return null
            val token = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
            val fingerprint = uri.getQueryParameter("f")
                ?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: return null
            val name = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: return null

            return QrPayload(
                address = address,
                port = port,
                nodeId = uri.getQueryParameter("i").orEmpty(),
                token = token,
                fingerprint = fingerprint,
                certificateName = name,
            )
        }
    }
}
