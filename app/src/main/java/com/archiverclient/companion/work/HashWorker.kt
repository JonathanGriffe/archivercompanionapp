package com.archiverclient.companion.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.media.Hasher

/**
 * Work through the backlog of unhashed files.
 *
 * A digest is optional in the protocol — an item with none is still listed and
 * still fetched, just unverified — so this is never on the critical path. It
 * runs in batches and re-enqueues itself, which keeps any single run short
 * enough that the system is happy to schedule it and losing one costs a batch
 * rather than the whole sweep.
 */
class HashWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val hasher = Hasher(applicationContext)
        return try {
            val done = hasher.sweep(BATCH)
            val left = AppDatabase.of(applicationContext).media().countUnhashed()
            Log.i(TAG, "hashed $done, $left to go")
            if (left > 0 && done > 0) Scheduling.enqueueHashing(applicationContext)
            Result.success()
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to read media", e)
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "hashing failed", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "HashWorker"
        /** Small enough to finish well inside any execution window WorkManager gives us. */
        const val BATCH = 40
    }
}
