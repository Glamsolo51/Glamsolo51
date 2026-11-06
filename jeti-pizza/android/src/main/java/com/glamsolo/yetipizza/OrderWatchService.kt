package com.glamsolo.yetipizza

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glamsolo.yetipizza.ui.rub
import com.glamsolo.yetipizza.ui.timeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Staff device at the counter: checks the server every 15 seconds and rings for every new order,
 * also with the screen off. A server push (FCM) would need a Google project, so this polls instead.
 */
class OrderWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Ждём новые заказы"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!running) {
            running = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val prefs = Prefs(this)
        while (scope.isActive) {
            val token = prefs.adminToken ?: return stopSelf()
            try {
                val orders = Api(prefs.serverUrl, token).orders()
                val waiting = orders.filter { it.status == "new" }
                val fresh = waiting.filter { it.id > prefs.lastSeenOrderId }
                fresh.forEach(::notifyNew)
                orders.maxOfOrNull { it.id }?.let { if (it > prefs.lastSeenOrderId) prefs.lastSeenOrderId = it }
                update(if (waiting.isEmpty()) "Новых заказов нет" else "Ждут подтверждения: ${waiting.size}")
            } catch (e: ApiException) {
                if (e.status == 401) {
                    prefs.watchOrders = false
                    return stopSelf()
                }
                update("Нет связи с сервером, пробуем ещё…")
            }
            delay(15_000)
        }
    }

    private fun notifyNew(o: Order) {
        val text = "${o.name} · ${rub(o.total)} · к ${timeText(o.pickupAt)}"
        val n = NotificationCompat.Builder(this, CHANNEL_ORDERS)
            .setSmallIcon(R.drawable.ic_stat_order)
            .setContentTitle("Новый заказ № ${o.id}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + o.items.joinToString { "${it.qty} × ${it.name}" }))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notify(o.id.toInt() + 1000, n)
    }

    private fun update(text: String) = notify(ONGOING_ID, ongoing(text))

    private fun notify(id: Int, n: Notification) {
        // Without the permission (Android 13+) the board in the app still works; the user is asked on login.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(id, n) }
        }
    }

    private fun ongoing(text: String) = NotificationCompat.Builder(this, CHANNEL_WATCH)
        .setSmallIcon(R.drawable.ic_stat_order)
        .setContentTitle("Принимаем заказы")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_STAFF, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ORDERS = "orders"
        private const val CHANNEL_WATCH = "watch"
        private const val ONGOING_ID = 1
        @Volatile private var running = false

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, OrderWatchService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, OrderWatchService::class.java))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ORDERS, "Новые заказы", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Звонок, когда покупатель оформил заказ" },
            )
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WATCH, "Приём заказов", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
