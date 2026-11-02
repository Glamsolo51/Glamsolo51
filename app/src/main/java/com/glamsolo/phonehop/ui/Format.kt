package com.glamsolo.phonehop.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.core.TransferException.Reason
import com.glamsolo.phonehop.core.TransferManifest
import com.glamsolo.phonehop.install.FailureKind
import com.glamsolo.phonehop.install.InstallStatus

object Format {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

    fun speed(context: Context, bytesPerSecond: Long): String =
        context.getString(R.string.per_second, bytes(context, bytesPerSecond))

    fun duration(context: Context, seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> context.getString(R.string.duration_seconds, s.toInt().coerceAtLeast(1))
            s < 3600 -> context.getString(R.string.duration_minutes, (s / 60).toInt(), (s % 60).toInt())
            else -> context.getString(R.string.duration_hours, (s / 3600).toInt(), ((s % 3600) / 60).toInt())
        }
    }

    /** "3 apps · 12 files", leaving out zero parts. */
    fun counts(context: Context, apps: Int, files: Int): String {
        val res = context.resources
        val parts = buildList {
            if (apps > 0) add(res.getQuantityString(R.plurals.apps_count, apps, apps))
            if (files > 0) add(res.getQuantityString(R.plurals.files_count, files, files))
        }
        return if (parts.isEmpty()) res.getQuantityString(R.plurals.items_count, 0, 0) else parts.joinToString(" · ")
    }

    fun manifestSummary(context: Context, manifest: TransferManifest): String =
        context.getString(R.string.dot_join, counts(context, manifest.appCount, manifest.fileCount), bytes(context, manifest.totalBytes))

    fun error(context: Context, reason: Reason): String = context.getString(
        when (reason) {
            Reason.CONNECTION_FAILED -> R.string.error_connection_failed
            Reason.CONNECTION_LOST -> R.string.error_connection_lost
            Reason.DECLINED -> R.string.error_declined
            Reason.BUSY -> R.string.error_busy
            Reason.INCOMPATIBLE -> R.string.error_incompatible
            Reason.PEER_CANCELLED -> R.string.error_peer_cancelled
            Reason.REMOTE_ERROR -> R.string.error_remote
            Reason.CHECKSUM_MISMATCH -> R.string.error_checksum
            Reason.PROTOCOL_ERROR -> R.string.error_protocol
            Reason.SOURCE_ERROR -> R.string.error_source
            Reason.STORAGE_ERROR -> R.string.error_storage
            Reason.TIMEOUT -> R.string.error_timeout
        },
    )

    fun install(context: Context, status: InstallStatus): String? = when (status) {
        InstallStatus.NotInstalled -> null
        InstallStatus.Queued -> context.getString(R.string.install_queued)
        InstallStatus.Installing -> context.getString(R.string.installing)
        InstallStatus.Installed -> context.getString(R.string.installed)
        is InstallStatus.Failed -> context.getString(
            when (status.kind) {
                FailureKind.ABORTED -> R.string.install_error_aborted
                FailureKind.CONFLICT -> R.string.install_error_conflict
                FailureKind.INCOMPATIBLE -> R.string.install_error_incompatible
                FailureKind.STORAGE -> R.string.install_error_storage
                FailureKind.BLOCKED -> R.string.install_error_blocked
                FailureKind.OTHER -> R.string.install_error_other
            },
        )
    }
}

@Composable
fun formatBytes(bytes: Long): String = Format.bytes(LocalContext.current, bytes)
