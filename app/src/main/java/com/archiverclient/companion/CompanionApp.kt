package com.archiverclient.companion

import android.app.Application
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.service.CompanionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CompanionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Anything paired means the service should be up. The process may have been
        // started by a worker, a boot receiver, or the launcher; none of them can
        // assume one of the others already did this.
        CoroutineScope(Dispatchers.IO).launch {
            if (AppDatabase.of(this@CompanionApp).peers().count() > 0) {
                CompanionService.ensureRunning(this@CompanionApp)
            }
        }
    }
}
