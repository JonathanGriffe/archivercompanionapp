package com.archiverclient.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY pairedAt ASC")
    suspend fun all(): List<Peer>

    @Query("SELECT * FROM peers ORDER BY pairedAt ASC")
    fun watch(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE revoked = 0 ORDER BY pairedAt ASC")
    suspend fun active(): List<Peer>

    @Query("SELECT COUNT(*) FROM peers")
    suspend fun count(): Int

    @Query("SELECT * FROM peers WHERE token = :token")
    suspend fun byToken(token: String): Peer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(peer: Peer)

    @Query("DELETE FROM peers")
    suspend fun forgetAll()

    /**
     * Pair with [peer] and nobody else: a phone serves one computer at a time.
     * One transaction, so the phone is never left with both, or with neither.
     */
    @Transaction
    suspend fun pairWith(peer: Peer) {
        forgetAll()
        put(peer)
    }

    /**
     * Accepted: wherever it was, and however long it had been refusing, that is
     * over — and it now knows us at [from].
     */
    @Query(
        """
        UPDATE peers SET lastAddress = :address, lastPort = :port, lastHelloAt = :at,
            lastHelloOk = 1, revoked = 0, refusals = 0, refusedSince = 0, lastHelloFrom = :from
        WHERE token = :token
        """
    )
    suspend fun recordHello(token: String, address: String, port: Int, at: Long, from: String)

    /** Nobody answered. Says nothing about a run of refusals either way, so leaves it be. */
    @Query("UPDATE peers SET lastHelloAt = :at, lastHelloOk = 0 WHERE token = :token")
    suspend fun recordHelloFailed(token: String, at: Long)

    /** A `403`, and where the run of them stands now: see `HeartbeatPolicy.refused`. */
    @Query(
        """
        UPDATE peers SET refusals = :refusals, refusedSince = :since, revoked = :revoked,
            lastHelloOk = 0, lastHelloAt = :at
        WHERE token = :token
        """
    )
    suspend fun recordRefusal(token: String, refusals: Int, since: Long, revoked: Boolean, at: Long)

    @Query("UPDATE peers SET lastInboundAt = :at WHERE token = :token")
    suspend fun recordInbound(token: String, at: Long)

    @Query("DELETE FROM peers WHERE token = :token")
    suspend fun forget(token: String)
}
