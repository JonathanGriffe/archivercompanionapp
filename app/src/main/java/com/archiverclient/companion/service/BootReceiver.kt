package com.archiverclient.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.work.Scheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bring everything back after a reboot or an update.
 *
 * Only when something is actually paired: an app nobody has set up yet has no
 * business holding a foreground service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AppDatabase.of(app).peers().count() > 0) {
                    Scheduling.enableAll(app)
                    CompanionService.ensureRunning(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
