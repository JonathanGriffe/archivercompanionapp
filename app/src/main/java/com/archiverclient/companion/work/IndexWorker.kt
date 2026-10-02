package com.archiverclient.companion.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.archiverclient.companion.media.MediaIndexer
import com.archiverclient.companion.service.CompanionService

/**
 * Bring the index up to date.
 *
 * In WorkManager rather than in the service so it survives the service being
 * killed, and so a full reconcile of a large camera roll can be interrupted and
 * picked up again rather than having to finish in one go.
 */
class IndexWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val full = inputData.getBoolean(KEY_FULL, false)
        val indexer = MediaIndexer(applicationContext)
        return try {
            val outcome = if (full) indexer.full() else indexer.incremental()
            Log.i(TAG, "indexed: $outcome")

            if (outcome.added > 0) {
                // A new photograph is precisely when a computer should come looking, so
                // this is worth an announcement rather than waiting for the next one.
                CompanionService.nudge(applicationContext, "new media")
            }
            if (outcome.added > 0 || outcome.full) {
                Scheduling.enqueueHashing(applicationContext)
            }
            Result.success()
        } catch (e: SecurityException) {
            // The media permission was refused or revoked. Retrying would only fail the
            // same way; the UI is where this gets resolved.
            Log.w(TAG, "not allowed to read media", e)
            Result.failure()
        } catch (e: Exception) {
            Log.w(TAG, "indexing failed", e)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "IndexWorker"
        const val KEY_FULL = "full"
    }
}
