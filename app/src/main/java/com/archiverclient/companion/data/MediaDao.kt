package com.archiverclient.companion.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    /**
     * One page of `/v1/items`, in the order the cursor walks.
     *
     * Ordered by the primary key so the walk is stable while it is happening:
     * the desktop pages through the whole library on every scan, and a row
     * inserted mid-walk must not be able to shuffle another one out of sight.
     */
    @Query(
        """
        SELECT * FROM media_items
        WHERE present = 1 AND id > :after
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun page(after: String, limit: Int): List<MediaItem>

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun byId(id: String): MediaItem?

    /** The hashing queue: oldest first, so the initial sweep is in a predictable order. */
    @Query(
        """
        SELECT * FROM media_items
        WHERE present = 1 AND (md5 IS NULL OR hashedSize IS NULL)
        ORDER BY dateModified DESC
        LIMIT :limit
        """
    )
    suspend fun needingHash(limit: Int): List<MediaItem>

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1")
    fun countPresent(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND md5 IS NOT NULL")
    fun countHashed(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media_items WHERE present = 1 AND md5 IS NULL")
    suspend fun countUnhashed(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(items: List<MediaItem>): List<Long>

    @Query(
        """
        UPDATE media_items SET
            seenMark = :mark,
            displayName = :displayName,
            relativePath = :relativePath,
            bucket = :bucket,
            storeSize = :storeSize,
            dateModified = :dateModified,
            mimeType = :mimeType,
            isVideo = :isVideo,
            present = 1,
            md5 = CASE WHEN storeSize = :storeSize AND dateModified = :dateModified
                       THEN md5 ELSE NULL END,
            hashedSize = CASE WHEN storeSize = :storeSize AND dateModified = :dateModified
                              THEN hashedSize ELSE NULL END
        WHERE id = :id
        """
    )
    suspend fun refresh(
        id: String,
        mark: Long,
        displayName: String,
        relativePath: String,
        bucket: String,
        storeSize: Long,
        dateModified: Long,
        mimeType: String,
        isVideo: Boolean,
    )

    @Query("UPDATE media_items SET md5 = :md5, hashedSize = :size WHERE id = :id")
    suspend fun recordHash(id: String, md5: String, size: Long)

    @Query("UPDATE media_items SET present = 0 WHERE id = :id")
    suspend fun markGone(id: String)

    /**
     * Retire everything in this volume that the run stamped with [mark] did not see.
     *
     * Only ever called after a *full* reconcile: an incremental pass looks at a
     * slice of the library, and sweeping on one of those would retire the entire
     * rest of the camera roll.
     *
     * `seenMark < :mark`, emphatically not `!=`. Two reconciles running at once —
     * which is exactly what a one-shot and a periodic both firing at startup
     * produces — would otherwise have the older one's sweep retire every row the
     * newer one had just re-stamped, and the whole library would drop out of
     * `/v1/items` at once. With `<`, a run can only ever retire rows that no run
     * since has touched, and two runs landing in the same millisecond sweep
     * nothing rather than sweeping each other's work.
     */
    @Query("UPDATE media_items SET present = 0 WHERE present = 1 AND volume = :volume AND seenMark < :mark")
    suspend fun retireStale(volume: String, mark: Long): Int

    /** Rows a full reconcile found in a volume that has since disappeared entirely. */
    @Query("UPDATE media_items SET present = 0 WHERE present = 1 AND volume NOT IN (:volumes)")
    suspend fun retireMissingVolumes(volumes: List<String>): Int

    /** True when the row was new, so the caller need not ask separately. */
    @Transaction
    suspend fun upsert(item: MediaItem, mark: Long): Boolean {
        val inserted = insertIgnoring(listOf(item.copy(seenMark = mark)))
        if (inserted.firstOrNull() != -1L) return true
        refresh(
            id = item.id,
            mark = mark,
            displayName = item.displayName,
            relativePath = item.relativePath,
            bucket = item.bucket,
            storeSize = item.storeSize,
            dateModified = item.dateModified,
            mimeType = item.mimeType,
            isVideo = item.isVideo,
        )
        return false
    }
}
