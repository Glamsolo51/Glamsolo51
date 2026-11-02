package com.glamsolo.phonehop.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.glamsolo.phonehop.graph

/** Receives PackageInstaller results for the sessions started by [InstallController]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val packageName = intent.getStringExtra(EXTRA_TARGET) ?: return
        val installer = context.graph.installer
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    installer.onResult(packageName, PackageInstaller.STATUS_FAILURE, null)
                } else {
                    installer.requestConfirmation(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            else -> installer.onResult(
                packageName,
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
    }

    companion object {
        const val ACTION = "com.glamsolo.phonehop.INSTALL_RESULT"
        const val EXTRA_TARGET = "target_package"
    }
}
