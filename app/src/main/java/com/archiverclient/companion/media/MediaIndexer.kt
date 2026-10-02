package com.archiverclient.companion.media

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.util.Log
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.MediaItem
import com.archiverclient.companion.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Everything on this phone that is a photograph or a video, and a way to notice
 * when that changes.
 *
 * Two modes. A **full reconcile** walks every volume and stamps what it finds,
 * then retires whatever it did not see — that is what makes a deletion on the
 * phone eventually become a retirement on the desktop. An **incremental** pass
 * asks MediaStore only for rows that changed since last time, which is cheap
 * enough to run on every nudge from the observer.
 *
 * The desktop re-reads the whole listing on every scan and retires anything
 * absent from it, so the index has to be complete, not a delta. That is a
 * property of this table, not of the walk that fills it.
 */
class MediaIndexer(private val context: Context) {

    private val db = AppDatabase.of(context)
    private val settings = Settings(context)

    data class Outcome(
        val seen: Int = 0,
        val added: Int = 0,
        val retired: Int = 0,
        val full: Boolean = false,
    )

    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
        MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.GENERATION_MODIFIED,
    )

    private val mediaSelection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"

    private val mediaArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )

    suspend fun full(): Outcome = withContext(Dispatchers.IO) { onlyOneAtATime { run(force = true) } }

    suspend fun incremental(): Outcome =
        withContext(Dispatchers.IO) { onlyOneAtATime { run(force = false) } }

    /**
     * One walk at a time in this process.
     *
     * Belt to [MediaDao.retireStale]'s braces: that makes concurrent runs safe,
     * this makes them not happen. Two workers walking a hundred thousand rows
     * simultaneously is pure waste even when the result is correct.
     */
    private suspend fun <T> onlyOneAtATime(body: suspend () -> T): T = walking.withLock { body() }

    private suspend fun run(force: Boolean): Outcome {
        val volumes = runCatching { MediaStore.getExternalVolumeNames(context).toList() }
            .getOrElse {
                Log.w(TAG, "could not list media volumes", it)
                emptyList()
            }
        if (volumes.isEmpty()) return Outcome()

        val mark = System.currentTimeMillis()
        var seen = 0
        var added = 0
        // Only volumes actually walked end to end may be swept. A volume we skipped,
        // or looked at only a delta of, still has rows carrying an older mark, and
        // sweeping one of those would retire the entire rest of its camera roll.
        val walkedWhole = mutableListOf<String>()

        for (volume in volumes) {
            val since = if (force) -1L else settings.generationOf(volume)
            val current = runCatching { MediaStore.getGeneration(context, volume) }.getOrNull()

            // Nothing has changed in this volume since we last looked, and we are not being
            // asked to sweep. The cheapest possible answer.
            if (!force && current != null && since >= 0 && current == since) continue

            // No generation to compare against means we cannot trust a delta, so this
            // volume gets walked whole even if the caller only asked for an increment.
            val walkWhole = force || since < 0 || current == null
            if (walkWhole) walkedWhole += volume

            val outcome = walk(volume, if (walkWhole) -1L else since, mark, walkWhole)
            seen += outcome.first
            added += outcome.second

            if (current != null) settings.setGeneration(volume, current)
        }

        var retired = 0
        for (volume in walkedWhole) {
            retired += db.media().retireStale(volume, mark)
        }
        // Safe whenever we hold the current volume list: it only touches rows filed
        // under a volume that is no longer mounted at all.
        retired += db.media().retireMissingVolumes(volumes)

        val full = walkedWhole.size == volumes.size
        if (full) settings.lastFullReconcileAt = mark

        return Outcome(seen = seen, added = added, retired = retired, full = full)
    }

    /** Answers how many rows were looked at, and how many were new. */
    private suspend fun walk(
        volume: String,
        sinceGeneration: Long,
        mark: Long,
        walkWhole: Boolean,
    ): Pair<Int, Int> {
        val selection = StringBuilder(mediaSelection)
        val args = mediaArgs.toMutableList()
        if (!walkWhole && sinceGeneration >= 0) {
            selection.append(" AND ${MediaStore.Files.FileColumns.GENERATION_MODIFIED} > ?")
            args.add(sinceGeneration.toString())
        }

        val uri = MediaStore.Files.getContentUri(volume)
        var seen = 0
        var added = 0

        val cursor: Cursor = context.contentResolver.query(
            uri,
            projection,
            selection.toString(),
            args.toTypedArray(),
            "${MediaStore.Files.FileColumns._ID} ASC",
        ) ?: return 0 to 0

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            while (c.moveToNext()) {
                val name = c.getString(nameCol).orEmpty()
                // Checked here as well as by the desktop. A name MediaStore has no display
                // name for cannot be offered at all: the desktop rejects a page missing one.
                if (name.isBlank() || !Media.isMedia(name)) continue

                val mediaStoreId = c.getLong(idCol)
                val item = MediaItem(
                    id = Media.idOf(volume, mediaStoreId),
                    mediaStoreId = mediaStoreId,
                    volume = volume,
                    displayName = name,
                    relativePath = c.getString(pathCol).orEmpty(),
                    bucket = c.getString(bucketCol).orEmpty(),
                    storeSize = c.getLong(sizeCol),
                    dateModified = c.getLong(modCol),
                    mimeType = c.getString(mimeCol).orEmpty(),
                    isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    seenMark = mark,
                )

                // The upsert says whether it was new, so a hundred thousand rows cost one
                // query each rather than two.
                if (db.media().upsert(item, mark)) added++
                seen++
            }
        }
        return seen to added
    }

    private companion object {
        const val TAG = "MediaIndexer"

        /** Shared across every instance, because each worker builds its own. */
        val walking = Mutex()
    }
}
