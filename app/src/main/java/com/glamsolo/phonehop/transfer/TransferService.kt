package com.glamsolo.phonehop.transfer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glamsolo.phonehop.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps the process (and Wi-Fi at full speed) alive while this phone is receiving or
 * sending, so a long transfer survives the screen turning off or switching apps.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TransferNotifications.ensureChannels(this)
        val graph = graph
        scope.launch {
            combine(graph.sender.state, graph.receiver.state, graph.settings.deviceName) { send, receive, name ->
                Triple(send, receive, name)
            }.conflate().collect { (send, receive, name) ->
                val busy = send.isActive || receive.isListening
                if (!busy) {
                    stopSelfCompletely()
                    return@collect
                }
                holdLocks(send.isActive || receive is ReceiveState.Transferring || receive is ReceiveState.Incoming)
                if (TransferNotifications.canNotify(this@TransferService)) {
                    try {
                        NotificationManagerCompat.from(this@TransferService).notify(
                            TransferNotifications.ID_SERVICE,
                            TransferNotifications.service(this@TransferService, send, receive, name),
                        )
                    } catch (_: SecurityException) {
                        // The permission was revoked a moment ago.
                    }
                }
                // Progress changes many times per second; the notification does not need to.
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = graph
        if (intent?.action == ACTION_STOP) {
            graph.sender.cancel()
            graph.receiver.stop()
        }
        val notification = TransferNotifications.service(
            this, graph.sender.state.value, graph.receiver.state.value, graph.settings.deviceName.value,
        )
        ServiceCompat.startForeground(
            this,
            TransferNotifications.ID_SERVICE,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent?.action == ACTION_STOP) stopSelfCompletely()
        return START_NOT_STICKY
    }

    /** Android 15+ limits data sync services; stop gracefully when the time is up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        graph.receiver.stop()
        graph.sender.cancel()
        stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        holdLocks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun holdLocks(hold: Boolean) {
        if (hold) {
            if (wifiLock == null) {
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PhoneHop:transfer")
                    ?.apply { setReferenceCounted(false); acquire() }
            }
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneHop:transfer")
                    ?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
        } else {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        holdLocks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.glamsolo.phonehop.STOP"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
