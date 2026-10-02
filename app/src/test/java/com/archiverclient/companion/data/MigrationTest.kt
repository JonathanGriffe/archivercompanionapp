package com.archiverclient.companion.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * An update must not cost a pairing.
 *
 * Each test builds a database exactly as an earlier version left it on a phone —
 * from that version's exported schema in `app/schemas`, the same file Room
 * generated the migration from — puts rows in it, and opens it with the code as
 * it is now.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun `version 1 keeps every pairing and every item, with no refusals and videos to hash again`() = runBlocking {
        version1 { v1 ->
            v1.execSQL(
                """
                INSERT INTO peers (token, nodeId, certFingerprint, certName, displayName,
                    lastAddress, lastPort, pairedAt, lastHelloAt, lastHelloOk, lastInboundAt, revoked)
                VALUES ('tok', '12D3KooW', 'ab12', 'f00d.node.archiverclient', 'f00d',
                    '192.168.1.20', 8765, 1, 2, 1, 3, 0)
                """
            )
            v1.execSQL(
                """
                INSERT INTO media_items (id, mediaStoreId, volume, displayName, relativePath,
                    bucket, storeSize, dateModified, mimeType, isVideo, md5, hashedSize,
                    present, seenMark, discoveredAt)
                VALUES ('external_primary:7', 7, 'external_primary', 'IMG_7.jpg', 'DCIM/Camera/',
                    'Camera', 1000, 7, 'image/jpeg', 0, 'd41d8cd98f00b204e9800998ecf8427e', 1000,
                    1, 1, 1),
                ('external_primary:8', 8, 'external_primary', 'VID_8.mp4', 'DCIM/Camera/',
                    'Camera', 5000, 8, 'video/mp4', 1, '9e107d9d372bb6826bd81d3542a419d6', 5000,
                    1, 1, 1)
                """
            )
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val peer = db.peers().byToken("tok")
            assertNotNull("the pairing survived", peer)
            peer!!
            assertEquals("192.168.1.20", peer.lastAddress)
            assertEquals(8765, peer.lastPort)
            assertEquals("ab12", peer.certFingerprint)
            assertTrue(peer.lastHelloOk)
            assertFalse(peer.revoked)
            assertEquals(0, peer.refusals)
            assertEquals(0L, peer.refusedSince)

            val image = db.media().byId("external_primary:7")
            assertNotNull("and so did the index", image)
            assertEquals("an image keeps its digest", "d41d8cd98f00b204e9800998ecf8427e", image!!.md5)
            assertEquals(1000L, image.hashedSize)

            // Version 2 serves a video's original bytes, which its old digest was not taken over.
            val video = db.media().byId("external_primary:8")
            assertNotNull(video)
            assertNull("a video is hashed again", video!!.md5)
            assertNull(video.hashedSize)
            assertTrue("and is still listed meanwhile", video.present)
        } finally {
            db.close()
        }
    }

    /** A database as version 1 created it, filled by [fill]. */
    private fun version1(fill: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(schemaFile(1).readText()).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))

        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            // Room's own bookkeeping, identity hash included.
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))

            fill(db)
            db.version = 1
        }
    }

    /** Unit tests run in the module directory; tolerate the project root as well. */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/com.archiverclient.companion.data.AppDatabase/$version.json"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }
    }
}
