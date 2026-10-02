package com.archiverclient.companion.media

import android.content.Context
import android.util.Log
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.MediaItem
import com.archiverclient.companion.net.Names
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * MD5, because that is the only digest the desktop will accept from a phone
 * (`phone.rs` builds a `Checksum { algo: Digest::Md5 }` and nothing else). The
 * archive itself stores everything under SHA-256; this is only the second
 * opinion it checks a transfer against.
 *
 * Nothing here blocks a listing. An item with no hash yet is still offered, just
 * without a `size` or an `md5`, and the desktop transfers it unverified.
 */
class Hasher(private val context: Context) {

    private val db = AppDatabase.of(context)

    /**
     * Hash up to [budget] items and answer how many were done.
     *
     * Cooperatively cancellable between blocks, so WorkManager stopping us mid
     * file costs at most one file's worth of reading rather than the batch.
     */
    suspend fun sweep(budget: Int): Int = withContext(Dispatchers.IO) {
        val queue = db.media().needingHash(budget)
        var done = 0
        for (item in queue) {
            coroutineContext.ensureActive()
            when (val outcome = hash(item)) {
                is Outcome.Hashed -> {
                    db.media().recordHash(item.id, outcome.md5, outcome.size)
                    done++
                }
                Outcome.Gone -> db.media().markGone(item.id)
                Outcome.Unreadable -> Unit
            }
        }
        done
    }

    sealed interface Outcome {
        data class Hashed(val md5: String, val size: Long) : Outcome
        /** Deleted between the index and now, which is ordinary on a phone in use. */
        data object Gone : Outcome
        /** Still there, but we could not read it. Left for the next sweep. */
        data object Unreadable : Outcome
    }

    /**
     * Reads through exactly what `/v1/items/{id}/bytes` will read through.
     *
     * That is the whole point of sharing [Media.open]: a hash taken over the
     * redacted copy and bytes served from the original would disagree, and the
     * desktop discards a download whose digest does not match — three times, an
     * hour apart, before giving up on the file for good.
     */
    suspend fun hash(item: MediaItem): Outcome = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        try {
            val stream = Media.open(context, item) ?: return@withContext Outcome.Gone
            stream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to read ${item.displayName}", e)
            return@withContext Outcome.Unreadable
        } catch (e: java.io.IOException) {
            // Deliberately not [Outcome.Gone]. Failing part-way through a read is a read
            // that failed, not proof the photograph has been deleted, and only
            // [Media.open] answering null is allowed to mean that — a wrong answer here
            // retires the row and has the desktop drop the file from the archive.
            Log.w(TAG, "could not read ${item.displayName}", e)
            return@withContext Outcome.Unreadable
        }
        Outcome.Hashed(Names.hex(digest.digest()), total)
    }

    private companion object {
        const val TAG = "Hasher"
    }
}
