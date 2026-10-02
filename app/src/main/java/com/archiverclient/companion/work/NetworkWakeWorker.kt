package com.archiverclient.companion.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.service.CompanionService

/**
 * The path back from a dead process when the phone joins wifi.
 *
 * An in-process `NetworkCallback` cannot fire when there is no process, and this
 * is the case it covers: the app was killed, the phone comes home, and something
 * has to notice. A JobScheduler network constraint does wake a dead process,
 * which is why this is a worker and not a receiver.
 */
class NetworkWakeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (AppDatabase.of(applicationContext).peers().count() == 0) return Result.success()
        CompanionService.ensureRunning(applicationContext)
        CompanionService.nudge(applicationContext, "joined a network")
        return Result.success()
    }
}
