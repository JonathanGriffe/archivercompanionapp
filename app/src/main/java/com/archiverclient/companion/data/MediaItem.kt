package com.archiverclient.companion.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photograph or video, as this phone knows it.
 *
 * Only the name and the path are kept, plus what it takes to answer "is this
 * still the same file?" — never the bytes. The desktop fetches those.
 */
@Entity(
    tableName = "media_items",
    indices = [
        // The paging order of `/v1/items`, which is the only query that has to be quick.
        Index(value = ["present", "id"]),
        Index(value = ["md5"]),
    ],
)
data class MediaItem(
    /**
     * `<volume>:<MediaStore _ID>`.
     *
     * The volume is part of it because `_ID` is only unique within one: a phone
     * with an SD card has two, and an id that collided would have the desktop
     * fetch one file believing it was another. The desktop percent-encodes the
     * colon, so it still arrives as a single path segment.
     */
    @PrimaryKey val id: String,
    val mediaStoreId: Long,
    val volume: String,
    val displayName: String,
    val relativePath: String,
    /** The album, which is what the desktop's Sort tab groups by. Empty is the camera roll. */
    val bucket: String,
    /** What MediaStore claims. Never published on its own — see [hashedSize]. */
    val storeSize: Long,
    val dateModified: Long,
    val mimeType: String,
    val isVideo: Boolean,
    /** Lowercase hex, or null until the hasher has been over it. */
    val md5: String? = null,
    /**
     * The byte count the hashing pass actually read.
     *
     * Published as `size` only alongside [md5], and only ever from the same pass.
     * A `size` the desktop cannot match makes it throw the whole download away.
     */
    val hashedSize: Long? = null,
    /** False once MediaStore stops listing it. Dropping out of `/v1/items` retires it. */
    val present: Boolean = true,
    /**
     * Which reconcile run last saw this row.
     *
     * A full reconcile stamps every row it finds with one number and then retires
     * everything in that volume still carrying an older one. Cheaper and far less
     * fragile than holding the whole set of live ids in memory to diff against,
     * which on a large camera roll is a hundred thousand strings.
     */
    val seenMark: Long = 0,
    val discoveredAt: Long = System.currentTimeMillis(),
) {
    /** Whether [md5] and [hashedSize] still describe the file MediaStore is reporting. */
    val hashIsCurrent: Boolean
        get() = md5 != null && hashedSize != null
}
