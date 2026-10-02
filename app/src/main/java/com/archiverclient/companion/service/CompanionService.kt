package com.archiverclient.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.archiverclient.companion.R
import com.archiverclient.companion.data.AppDatabase
import com.archiverclient.companion.data.Settings
import com.archiverclient.companion.media.MediaObserver
import com.archiverclient.companion.net.CompanionServer
import com.archiverclient.companion.net.DeviceIdentity
import com.archiverclient.companion.net.Heartbeat
import com.archiverclient.companion.net.NodeDiscovery
import com.archiverclient.companion.ui.MainActivity
import com.archiverclient.companion.work.Scheduling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Everything that has to be running for a computer to be able to reach this
 * phone: the HTTPS server, the heartbeat, and the watch on the camera roll.
 *
 * A foreground service of type `connectedDevice`, and deliberately not
 * `dataSync`: Android 15 stops a `dataSync` service after six hours in any
 * twenty-four and will not let it restart until somebody opens the app. Against
 * a desktop that scans every sixteen hours, a phone that goes quiet after six
 * would be invisible about half the time. See the manifest for why this is not
 * backed by a companion-device association.
 *
 * Nothing here is proof against a manufacturer's battery manager. The defences,
 * in order of how much they actually help: the battery-optimisation exemption
 * the user grants on the main screen, `START_STICKY`, `BootReceiver`, and
 * `WatchdogWorker` noticing every fifteen minutes that we are gone.
 *
 * Indexing and hashing deliberately live in WorkManager instead, so they outlive
 * this being killed.
 */
class CompanionService : LifecycleService() {

    private lateinit var settings: Settings
    private var server: CompanionServer? = null
    private var heartbeat: Heartbeat? = null
    private var conditions: NetworkConditions? = null
    private var observer: MediaObserver? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        channels()
        goForeground(NOTIFICATION_ID, notification("Starting…"))
        bringUp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_NUDGE -> heartbeat?.nudge(intent.getStringExtra(EXTRA_REASON) ?: "something")
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Restarted by the system if it kills us, which is the first line of defence
        // before the watchdog worker notices.
        return START_STICKY
    }

    private fun bringUp() {
        val identity = DeviceIdentity.of(this)

        val started = runCatching {
            CompanionServer.start(this, identity, settings) { token ->
                // A computer asking us for something is proof it is up and reachable,
                // and worth an announcement straight back so it counts us fresh for the
                // rest of whatever it is doing.
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.of(this@CompanionService).peers()
                        .recordInbound(token, System.currentTimeMillis())
                }
                heartbeat?.nudge("a computer asked for something")
            }
        }.onFailure { Log.e(TAG, "could not raise the server", it) }.getOrNull()
        server = started

        val watch = NetworkConditions(this) { reason -> heartbeat?.nudge(reason) }
        conditions = watch

        val beat = Heartbeat(
            context = this,
            scope = lifecycleScope,
            discovery = NodeDiscovery(this, watch::wifiNetwork),
            conditions = watch,
            serverPort = { server?.listeningPort ?: settings.serverPort },
        )
        heartbeat = beat

        watch.register()
        beat.start()

        observer = MediaObserver(this) {
            Scheduling.enqueueIndex(this, full = false)
        }.also { it.start() }

        lifecycleScope.launch(Dispatchers.IO) { refreshNotification() }
    }

    private suspend fun refreshNotification() {
        val db = AppDatabase.of(this)
        val peers = db.peers().count()
        val items = db.media().countUnhashed()
        val text = when {
            peers == 0 -> "Not paired with a computer yet"
            else -> "$peers computer${if (peers == 1) "" else "s"} · " +
                if (items == 0) "everything indexed" else "$items still to hash"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        observer?.stop()
        heartbeat?.stop()
        conditions?.unregister()
        runCatching { server?.stop() }
        super.onDestroy()
    }

    private fun channels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.channel_service_name),
                // Low: this is a status line, not something to interrupt anybody with.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_service_desc) }
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun goForeground(id: Int, notification: Notification) {
        ServiceCompat.startForeground(
            this,
            id,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        private const val TAG = "CompanionService"
        private const val CHANNEL = "companion"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_NUDGE = "nudge"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_REASON = "reason"

        fun ensureRunning(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, CompanionService::class.java))
            }.onFailure { Log.w(TAG, "could not start the service", it) }
        }

        /** Tell a running service that something worth announcing has happened. */
        fun nudge(context: Context, reason: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, CompanionService::class.java).apply {
                        action = ACTION_NUDGE
                        putExtra(EXTRA_REASON, reason)
                    }
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionService::class.java))
        }
    }
}
