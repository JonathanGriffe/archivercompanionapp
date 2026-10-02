package com.archiverclient.companion.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.service.CompanionService

/**
 * Start the service again if something stopped it, and make the heartbeat look
 * at the time.
 *
 * Foreground services do get killed — by the system under memory pressure, and
 * far more often by the battery managers several manufacturers ship. WorkManager
 * survives process death and app-standby bucketing in a way a bare service does
 * not, so this is the thing that notices.
 *
 * The nudge matters even when the service is alive. A heartbeat waiting out a
 * scan cycle waits on a clock that stops while the phone sleeps, so its own
 * timeout can come hours late; a nudge makes it recompute against the wall
 * clock. `NetworkWakeWorker` nudges too, but only on an unmetered network.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (AppDatabase.of(applicationContext).peers().count() > 0) {
            CompanionService.ensureRunning(applicationContext)
            CompanionService.nudge(applicationContext, "the watchdog's quarter hour")
        }
        return Result.success()
    }
}
