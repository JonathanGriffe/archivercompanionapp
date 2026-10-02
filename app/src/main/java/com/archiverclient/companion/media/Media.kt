package com.archiverclient.companion.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.archiverclient.companion.data.MediaItem
import java.io.InputStream

/**
 * Which files an archive of photographs is for.
 *
 * Mirrors `ac-import/src/source.rs::is_media`. The desktop applies this list
 * itself and ignores anything that fails it, so offering the rest would only
 * waste its time — and, on a phone, ours: hashing a few gigabytes of podcasts
 * costs real battery for something that will never be fetched.
 *
 * By extension rather than by content, exactly as the desktop does it, so that
 * the two sides agree without either having to open a file.
 */
object Media {

    private val PICTURES = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tif", "tiff", "webp",
        "avif", "heic", "heif", "jxl", "svg", "ico",
    )

    /** Raw: a picture the camera has not developed yet. */
    private val NEGATIVES = setOf(
        "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2", "dng", "raf", "orf",
        "rw2", "pef", "srw", "x3f", "3fr", "erf", "kdc", "mrw", "raw",
    )

    private val FOOTAGE = setOf(
        "mp4", "m4v", "mov", "avi", "mkv", "webm", "3gp", "3g2", "mpg", "mpeg",
        "m2ts", "mts", "ts", "wmv", "flv", "ogv", "mxf", "insv",
    )

    /** Nothing without an extension passes, which is the desktop's rule too. */
    fun isMedia(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        val ext = name.substring(dot + 1).lowercase()
        return ext in PICTURES || ext in NEGATIVES || ext in FOOTAGE
    }

    /** `<volume>:<MediaStore _ID>`, the reference the desktop holds on to. */
    fun idOf(volume: String, mediaStoreId: Long): String = "$volume:$mediaStoreId"

    /** The other direction, for a request that arrives naming one. */
    fun splitId(id: String): Pair<String, Long>? {
        val cut = id.lastIndexOf(':')
        if (cut <= 0 || cut == id.length - 1) return null
        val number = id.substring(cut + 1).toLongOrNull() ?: return null
        return id.substring(0, cut) to number
    }

    fun contentUri(item: MediaItem): Uri =
        ContentUris.withAppendedId(
            MediaStore.Files.getContentUri(item.volume),
            item.mediaStoreId,
        )

    /**
     * The uri that yields the original bytes, location and all.
     *
     * Without `setRequireOriginal` MediaStore redacts location on the way past:
     * the GPS tags in an image's EXIF, and the location boxes (`©xyz`, `loci`) in
     * an MP4 or a QuickTime video. An archive is for keeping the photograph as it
     * was taken, so videos ask for it too. Where it is refused — no
     * `ACCESS_MEDIA_LOCATION` — [open] falls back to the redacted copy rather than
     * to nothing.
     */
    fun originalUri(item: MediaItem): Uri = MediaStore.setRequireOriginal(contentUri(item))

    /**
     * Open an item's bytes, or answer null if it has genuinely gone.
     *
     * The single place both the hasher and the server read through, because the
     * digest we publish has to be taken over the bytes we later serve. Two
     * separate copies of this fallback would be two chances for them to disagree,
     * and the symptom would be the desktop discarding every download.
     *
     * The fallback is not cosmetic. `setRequireOriginal` needs
     * `ACCESS_MEDIA_LOCATION`, which the user can refuse and the system can be
     * slow to reflect, and it answers a missing-file error when it is unhappy —
     * which is indistinguishable from a deleted photograph unless we go and ask
     * the plain uri as well. Believing the first answer costs a real file: null
     * here retires the row, and a photograph the desktop has not fetched yet is
     * then forgotten by it until a reconcile lists it again.
     *
     * @throws SecurityException when this app may not read it at all: see below.
     */
    fun open(context: Context, item: MediaItem): InputStream? =
        open(item) { uri -> context.contentResolver.openInputStream(uri) }

    /**
     * The policy on its own, with the resolver handed in.
     *
     * Split out because it is the part that was wrong, and a `ContentResolver`
     * that can fail the way a real one fails is not something a JVM test can
     * conjure.
     *
     * @throws SecurityException when even the plain uri may not be read. Not
     *   being allowed to look says nothing about whether the file is there, and
     *   answering null would retire it — every one of them, once the media
     *   permission is revoked.
     */
    internal fun open(item: MediaItem, opener: (Uri) -> InputStream?): InputStream? {
        // Either it threw or it handed back null; both mean "ask the other way"
        // rather than "the photograph is gone".
        runCatching { opener(originalUri(item)) }.getOrNull()?.let { return it }
        return try {
            opener(contentUri(item))
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
