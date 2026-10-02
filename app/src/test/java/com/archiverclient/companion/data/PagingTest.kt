package com.archiverclient.companion.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The walk `/v1/items` exposes.
 *
 * The desktop does not keep a cursor between scans: it re-reads the entire
 * listing every time and retires anything it did not see. So the only properties
 * that matter are that a full walk visits everything exactly once, and that the
 * order is stable while it is happening.
 */
@RunWith(RobolectricTestRunner::class)
class PagingTest {

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

    private fun item(volume: String, id: Long, present: Boolean = true) = MediaItem(
        id = "$volume:$id",
        mediaStoreId = id,
        volume = volume,
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        bucket = "Camera",
        storeSize = 1000 + id,
        dateModified = id,
        mimeType = "image/jpeg",
        isVideo = false,
        present = present,
    )

    /** Pages the way the desktop does and answers everything it was handed. */
    private fun walk(limit: Int): List<String> = runBlocking {
        val seen = mutableListOf<String>()
        var after = ""
        while (true) {
            val page = db.media().page(after, limit)
            seen += page.map { it.id }
            if (page.size < limit) break
            after = page.last().id
        }
        seen
    }

    @Test
    fun `a full walk visits every present item exactly once`() = runBlocking {
        val ids = (1L..250L).map { item("external_primary", it) }
        ids.forEach { db.media().upsert(it, mark = 1) }

        val seen = walk(limit = 200)
        assertEquals(250, seen.size)
        assertEquals(250, seen.toSet().size)
        assertEquals(ids.map { it.id }.toSet(), seen.toSet())
    }

    @Test
    fun `paging is consistent whatever the page size`() = runBlocking {
        (1L..97L).forEach { db.media().upsert(item("external_primary", it), mark = 1) }
        val everything = walk(limit = 500).toSet()
        listOf(1, 2, 7, 96, 97, 98).forEach { size ->
            assertEquals("page size $size", everything, walk(size).toSet())
        }
    }

    @Test
    fun `two volumes both appear and their ids never collide`() = runBlocking {
        db.media().upsert(item("external_primary", 42), mark = 1)
        db.media().upsert(item("1a2b-3c4d", 42), mark = 1)

        val seen = walk(limit = 200)
        assertEquals(2, seen.size)
        assertTrue("external_primary:42" in seen)
        assertTrue("1a2b-3c4d:42" in seen)
    }

    @Test
    fun `retired items drop out of the listing`() = runBlocking {
        (1L..10L).forEach { db.media().upsert(item("external_primary", it), mark = 1) }
        db.media().markGone("external_primary:5")

        val seen = walk(limit = 4)
        assertEquals(9, seen.size)
        assertTrue("external_primary:5" !in seen)
    }

    @Test
    fun `a reconcile retires what it did not see and keeps what it did`() = runBlocking {
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 1) }

        // A second pass that only finds three of them, as it would after two deletions.
        listOf(1L, 2L, 3L).forEach { db.media().upsert(item("external_primary", it), mark = 2) }
        db.media().retireStale("external_primary", mark = 2)

        assertEquals(
            listOf("external_primary:1", "external_primary:2", "external_primary:3"),
            walk(limit = 10).sorted(),
        )
    }

    /**
     * Two full reconciles overlapping, which is exactly what a one-shot and a
     * periodic worker both firing at startup produced on a real device: every row
     * was retired at once and `/v1/items` went empty, because the older run's
     * sweep did not recognise the rows the newer run had just re-stamped.
     */
    @Test
    fun `an older reconcile's sweep does not retire a newer one's work`() = runBlocking {
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 10) }

        // Run A stamps everything, then run B overtakes it and stamps them again.
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 20) }
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 21) }

        // Only now does A get round to sweeping, carrying its own older mark.
        assertEquals(0, db.media().retireStale("external_primary", mark = 20))
        assertEquals(5, walk(limit = 10).size)

        assertEquals(0, db.media().retireStale("external_primary", mark = 21))
        assertEquals(5, walk(limit = 10).size)
    }

    @Test
    fun `two reconciles landing in the same millisecond sweep nothing`() = runBlocking {
        (1L..3L).forEach { db.media().upsert(item("external_primary", it), mark = 7) }
        assertEquals(0, db.media().retireStale("external_primary", mark = 7))
        assertEquals(3, walk(limit = 10).size)
    }

    @Test
    fun `an incremental pass on its own must not retire anything`() = runBlocking {
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 1) }
        // Exactly what an incremental run does: touch only what changed. Nothing is
        // swept, because a delta has no idea what it did not look at.
        db.media().upsert(item("external_primary", 3), mark = 2)
        assertEquals(5, walk(limit = 10).size)
    }

    @Test
    fun `a changed file loses the hash that no longer describes it`() = runBlocking {
        db.media().upsert(item("external_primary", 1), mark = 1)
        db.media().recordHash("external_primary:1", "5d41402abc4b2a76b9719d911017c592", 1001)
        assertEquals("5d41402abc4b2a76b9719d911017c592", db.media().byId("external_primary:1")!!.md5)

        // Edited in place: same id, different size and mtime.
        db.media().upsert(
            item("external_primary", 1).copy(storeSize = 2222, dateModified = 999),
            mark = 2,
        )
        val after = db.media().byId("external_primary:1")!!
        assertNull("a stale digest would have the desktop discard the download", after.md5)
        assertNull(after.hashedSize)
    }

    @Test
    fun `re-seeing an unchanged file keeps its hash`() = runBlocking {
        db.media().upsert(item("external_primary", 1), mark = 1)
        db.media().recordHash("external_primary:1", "5d41402abc4b2a76b9719d911017c592", 1001)
        db.media().upsert(item("external_primary", 1), mark = 2)
        assertEquals(
            "5d41402abc4b2a76b9719d911017c592",
            db.media().byId("external_primary:1")!!.md5,
        )
    }

    @Test
    fun `a retired item comes back if it reappears`() = runBlocking {
        db.media().upsert(item("external_primary", 1), mark = 1)
        db.media().markGone("external_primary:1")
        assertEquals(0, walk(limit = 10).size)
        db.media().upsert(item("external_primary", 1), mark = 2)
        assertEquals(1, walk(limit = 10).size)
    }

    @Test
    fun `only unhashed items are queued for hashing`() = runBlocking {
        (1L..5L).forEach { db.media().upsert(item("external_primary", it), mark = 1) }
        db.media().recordHash("external_primary:2", "d".repeat(32), 1002)
        val queued = db.media().needingHash(10).map { it.id }
        assertEquals(4, queued.size)
        assertTrue("external_primary:2" !in queued)
        assertEquals(4, db.media().countUnhashed())
    }
}
