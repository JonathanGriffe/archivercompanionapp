package com.archiverclient.companion.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Everything this app asks WorkManager to do, in one place. */
object Scheduling {

    private const val INDEX_ONCE = "index-once"
    private const val INDEX_PERIODIC = "index-periodic"
    private const val HASH = "hash"
    private const val WATCHDOG = "watchdog"
    private const val NETWORK_WAKE = "network-wake"

    /** Everything that should be running for as long as a computer is paired. */
    fun enableAll(context: Context) {
        enqueueIndex(context, full = true)
        schedulePeriodicIndex(context)
        scheduleWatchdog(context)
        scheduleNetworkWake(context)
    }

    fun disableAll(context: Context) {
        val work = WorkManager.getInstance(context)
        listOf(INDEX_ONCE, INDEX_PERIODIC, HASH, WATCHDOG, NETWORK_WAKE)
            .forEach { work.cancelUniqueWork(it) }
    }

    /**
     * @param full a complete reconcile. Only a full pass may retire rows, so this
     *   is what eventually turns a deletion on the phone into one on the desktop.
     */
    fun enqueueIndex(context: Context, full: Boolean) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(Data.Builder().putBoolean(IndexWorker.KEY_FULL, full).build())
            .build()
        WorkManager.getInstance(context)
            // Kept rather than replaced: a burst of new photographs arrives as many
            // nudges, and the run already under way will see all of them.
            .enqueueUniqueWork(INDEX_ONCE, ExistingWorkPolicy.KEEP, request)
    }

    /** The sweep that notices deletions. The desktop only scans every sixteen hours. */
    private fun schedulePeriodicIndex(context: Context) {
        val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS)
            .setInputData(Data.Builder().putBoolean(IndexWorker.KEY_FULL, true).build())
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            // A periodic worker runs as soon as it is scheduled unless told otherwise,
            // which at startup means racing the one-shot reconcile that [enableAll] has
            // just queued. They have different unique names, so WorkManager will not
            // collapse them for us.
            .setInitialDelay(6, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            INDEX_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /**
     * Hashing is optional work: an unhashed item is still offered and still
     * fetched. So it waits for a phone that is not scraping the bottom of its
     * battery, and never blocks anything.
     */
    fun enqueueHashing(context: Context) {
        val request = OneTimeWorkRequestBuilder<HashWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(HASH, ExistingWorkPolicy.KEEP, request)
    }

    /** Ignores the battery constraint: the user asked for it, on this screen, now. */
    fun hashNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HASH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HashWorker>().build(),
        )
    }

    private fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    /**
     * Fires when the phone has an unmetered network — which is what wakes the app
     * back up after it has been killed.
     *
     * Periodic rather than one-shot because a one-shot is spent the first time it
     * runs, and this has to keep working for every wifi the phone ever joins.
     */
    private fun scheduleNetworkWake(context: Context) {
        val request = PeriodicWorkRequestBuilder<NetworkWakeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NETWORK_WAKE, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }
}
