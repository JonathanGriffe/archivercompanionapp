package com.archiverclient.companion.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A computer this phone has paired with.
 *
 * More than one is ordinary: people have a laptop and a desktop, and each pairs
 * separately and holds its own token.
 */
@Entity(tableName = "peers")
data class Peer(
    /**
     * The pairing token from the QR, which is the whole of the relationship: we
     * echo it in every hello, and it arrives back as the bearer token on every
     * request the computer makes to us.
     */
    @PrimaryKey val token: String,
    /** Its libp2p peer id, used to pick it out of mDNS. May be empty. */
    val nodeId: String,
    /** `hex(SHA-256(cert DER))` — the only reason to trust anything that answers. */
    val certFingerprint: String,
    /** The name its certificate is issued for; carried in SNI. */
    val certName: String,
    val displayName: String,
    /** Where it last answered. Rewritten whenever discovery finds it somewhere new. */
    val lastAddress: String,
    val lastPort: Int,
    val pairedAt: Long,
    val lastHelloAt: Long = 0,
    val lastHelloOk: Boolean = false,
    /** Last time this computer asked us for something. Proof it is up, for free. */
    val lastInboundAt: Long = 0,
    /**
     * Set once it has answered `403` to every hello for long enough to mean it:
     * it no longer knows this phone, and the fix is pairing again rather than
     * trying harder. See [com.archiverclient.companion.net.HeartbeatPolicy.refused].
     */
    val revoked: Boolean = false,
    /** `403`s in a row, with nothing accepted in between. Nought once one is. */
    @ColumnInfo(defaultValue = "0")
    val refusals: Int = 0,
    /** When the current run of [refusals] began. Nought when there is none. */
    @ColumnInfo(defaultValue = "0")
    val refusedSince: Long = 0,
)
