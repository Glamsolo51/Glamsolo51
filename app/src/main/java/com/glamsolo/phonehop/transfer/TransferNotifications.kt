package com.glamsolo.phonehop.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.glamsolo.phonehop.MainActivity
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.core.IncomingRequest
import com.glamsolo.phonehop.core.TransferProgress

object TransferNotifications {
    const val CHANNEL_TRANSFER = "transfer"
    const val CHANNEL_INCOMING = "incoming"
    const val ID_SERVICE = 1
    const val ID_INCOMING = 2

    /** Android 13+ lets the user turn notifications off; the service keeps working without them. */
    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER, context.getString(R.string.channel_transfer), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The ongoing notification of [TransferService]. */
    fun service(context: Context, send: SendState, receive: ReceiveState, deviceName: String): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.notif_stop), TransferService.stopIntent(context))

        when {
            send is SendState.Transferring -> progress(context, builder, R.string.notif_sending, send.progress)
            receive is ReceiveState.Transferring -> progress(context, builder, R.string.notif_receiving, receive.progress)
            send.isActive -> builder
                .setContentTitle(context.getString(R.string.notif_connecting))
                .setContentText(send.target?.name)
                .setProgress(0, 0, true)
            else -> builder
                .setContentTitle(context.getString(R.string.notif_listening))
                .setContentText(context.getString(R.string.notif_listening_text, deviceName))
        }
        return builder.build()
    }

    private fun progress(context: Context, builder: NotificationCompat.Builder, title: Int, progress: TransferProgress) {
        val percent = (progress.fraction * 100).toInt()
        val speed = Formatter.formatShortFileSize(context, progress.bytesPerSecond)
        builder
            .setContentTitle(context.getString(title, percent))
            .setContentText(listOfNotNull(context.getString(R.string.per_second, speed), progress.currentItemTitle).joinToString(" · "))
            .setProgress(100, percent, false)
    }

    /** Asks the user to open the app when a request arrives while it is in the background. */
    fun showIncoming(context: Context, request: IncomingRequest?) {
        val manager = NotificationManagerCompat.from(context)
        if (request == null) {
            manager.cancel(ID_INCOMING)
            return
        }
        val inForeground = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(true)
        if (inForeground) return
        if (!canNotify(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(context.getString(R.string.notif_incoming, request.manifest.senderName))
            .setContentText(context.getString(R.string.notif_incoming_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            manager.notify(ID_INCOMING, notification)
        } catch (_: SecurityException) {
            // Notifications were turned off in the meantime.
        }
    }
}
