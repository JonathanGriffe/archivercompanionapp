package com.archiverclient.companion.net

/**
 * The names and encodings the desktop derives on its side, mirrored here.
 *
 * Every function in this file has a counterpart in
 * `ac-import/src/sources/phone.rs`, and the two have to agree exactly or a
 * connection fails at the handshake rather than anywhere useful. They are pure
 * so the unit tests can hold them against the Rust originals.
 */
object Names {

    /** Suffix of the name our own certificate is issued for. */
    const val PHONE_SUFFIX = ".phone.archiverclient"

    /** Suffix of the name the desktop's certificate is issued for. */
    const val NODE_SUFFIX = ".node.archiverclient"

    /**
     * `phone.rs::synthetic`. The desktop builds this from the device id we gave
     * it at pairing and then requires our certificate to name it.
     *
     * Because it is lossy, two device ids can collapse to one name; we sidestep
     * that by choosing an id this is the identity function on. See
     * [DeviceIdentity.newDeviceId].
     */
    fun synthetic(deviceId: String): String {
        val tame = buildString(deviceId.length) {
            for (c in deviceId) {
                append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c.lowercaseChar() else '-')
            }
        }
        return tame.trim('-') + PHONE_SUFFIX
    }

    /**
     * `phone.rs::encode`. Percent-encoding that keeps only the unreserved set,
     * which is what the desktop uses on an item id before putting it in a path.
     * We need the inverse far more often than this, but the pairing QR is
     * encoded with it and a round-trip test is worth having.
     */
    fun percentEncode(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            for (b in bytes) {
                val c = b.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '_' || c == '.' || c == '~'
                ) {
                    append(c)
                } else {
                    append('%').append("%02X".format(b.toInt() and 0xFF))
                }
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Compares two hex strings without leaking where they first differ.
     *
     * Used on the desktop's certificate fingerprint and on the bearer token, both
     * of which are secrets an attacker on the same wifi would like to guess a
     * byte at a time.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
