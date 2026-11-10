package com.archiverclient.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A phone serves one computer at a time. */
@RunWith(RobolectricTestRunner::class)
class PeerDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun peer(token: String) = Peer(
        token = token,
        nodeId = "12D3KooW-$token",
        certFingerprint = "ab".repeat(32),
        certName = "f00d.node.archiverclient",
        displayName = "f00d",
        lastAddress = "192.168.1.20",
        lastPort = 8765,
        pairedAt = 1,
    )

    @Test
    fun `pairing leaves the phone with the new computer only`() = runBlocking {
        db.peers().pairWith(peer("first"))
        db.peers().pairWith(peer("second"))

        assertEquals(listOf("second"), db.peers().all().map { it.token })
    }

    @Test
    fun `a phone holding several from before keeps only the one it pairs with next`() = runBlocking {
        db.peers().put(peer("laptop"))
        db.peers().put(peer("desktop"))

        db.peers().pairWith(peer("new"))

        assertEquals(listOf("new"), db.peers().all().map { it.token })
    }
}
