package com.glamsolo.phonehop.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.glamsolo.phonehop.transfer.ReceivedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface InstallStatus {
    data object NotInstalled : InstallStatus
    data object Queued : InstallStatus
    data object Installing : InstallStatus
    data object Installed : InstallStatus
    data class Failed(val kind: FailureKind, val message: String?) : InstallStatus
}

enum class FailureKind { ABORTED, CONFLICT, INCOMPATIBLE, STORAGE, BLOCKED, OTHER }

/**
 * Installs received APKs (single or split) one after another through PackageInstaller.
 * Android shows its own confirmation for each app; that screen is opened by [MainActivity]
 * from [confirmation].
 */
class InstallController(private val context: Context, private val scope: CoroutineScope) {
    private val _statuses = MutableStateFlow<Map<String, InstallStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, InstallStatus>> = _statuses.asStateFlow()

    /** The system "Do you want to install this app?" screen, waiting to be shown. */
    private val _confirmation = MutableStateFlow<Intent?>(null)
    val confirmation: StateFlow<Intent?> = _confirmation.asStateFlow()

    private val queue = ArrayDeque<ReceivedApp>()
    private var current: ReceivedApp? = null

    /** Android 8+ requires the user to allow this app to install other apps. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun statusOf(app: ReceivedApp): InstallStatus =
        _statuses.value[app.packageName] ?: if (isInstalled(app)) InstallStatus.Installed else InstallStatus.NotInstalled

    /** True when this exact version (or a newer one) is already on the phone. */
    fun isInstalled(app: ReceivedApp): Boolean = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(app.packageName, 0)
        }
        PackageInfoCompat.getLongVersionCode(info) >= app.versionCode
    }.getOrDefault(false)

    @Synchronized
    fun install(apps: List<ReceivedApp>) {
        for (app in apps) {
            val status = statusOf(app)
            if (status == InstallStatus.Installed || status == InstallStatus.Queued || status == InstallStatus.Installing) continue
            if (app.apkFiles.none { it.exists() }) continue
            queue.addLast(app)
            setStatus(app.packageName, InstallStatus.Queued)
        }
        startNext()
    }

    @Synchronized
    private fun startNext() {
        if (current != null) return
        val next = queue.removeFirstOrNull() ?: return
        current = next
        setStatus(next.packageName, InstallStatus.Installing)
        scope.launch(Dispatchers.IO) {
            try {
                commitSession(next)
            } catch (e: Exception) {
                onResult(next.packageName, PackageInstaller.STATUS_FAILURE, e.message)
            }
        }
    }

    private fun commitSession(app: ReceivedApp) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(app.apkFiles.sumOf { it.length() })
            setInstallReason(PackageManager.INSTALL_REASON_DEVICE_SETUP)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                for (apk in app.apkFiles) {
                    apk.inputStream().use { input ->
                        session.openWrite(apk.name, 0, apk.length()).use { output ->
                            input.copyTo(output, 256 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_TARGET, app.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    fun requestConfirmation(intent: Intent) {
        _confirmation.value = intent
    }

    fun confirmationShown() {
        _confirmation.value = null
    }

    @Synchronized
    fun onResult(packageName: String, status: Int, message: String?) {
        val app = current?.takeIf { it.packageName == packageName }
        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> InstallStatus.Installed
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallStatus.Failed(FailureKind.ABORTED, message)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallStatus.Failed(FailureKind.CONFLICT, message)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallStatus.Failed(FailureKind.INCOMPATIBLE, message)
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallStatus.Failed(FailureKind.STORAGE, message)
            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallStatus.Failed(FailureKind.BLOCKED, message)
            else -> InstallStatus.Failed(FailureKind.OTHER, message)
        }
        setStatus(packageName, result)
        if (result == InstallStatus.Installed) {
            // The APKs are no longer needed once the app is installed.
            app?.apkFiles?.firstOrNull()?.parentFile?.deleteRecursively()
        }
        if (app != null) current = null
        startNext()
    }

    private fun setStatus(packageName: String, status: InstallStatus) {
        _statuses.update { it + (packageName to status) }
    }
}
