package com.glamsolo.phonehop.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** An installed app that can be sent: its APK files are copied as they are. */
data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** base.apk first, then split APKs. */
    val apkPaths: List<String>,
    val sizeBytes: Long,
)

class InstalledAppsRepository(private val context: Context) {

    /** User-installed apps that have a launcher icon, sorted by name. */
    suspend fun load(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchable = launcherPackages(pm)
        installedPackages(pm)
            .asSequence()
            .mapNotNull { info -> toAppInfo(pm, info, launchable) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun toAppInfo(pm: PackageManager, info: PackageInfo, launchable: Set<String>): AppInfo? {
        val app = info.applicationInfo ?: return null
        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) return null
        if (!app.enabled || info.packageName !in launchable) return null
        val paths = buildList {
            app.sourceDir?.let(::add)
            app.splitSourceDirs?.let { addAll(it) }
        }.filter { File(it).canRead() }
        if (paths.isEmpty()) return null
        return AppInfo(
            packageName = info.packageName,
            label = app.loadLabel(pm).toString().ifBlank { info.packageName },
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            apkPaths = paths,
            sizeBytes = paths.sumOf { File(it).length() },
        )
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

    private fun launcherPackages(pm: PackageManager): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return activities.mapNotNull { it.activityInfo?.packageName }.toSet()
    }
}

/** Loads app icons off the main thread and keeps the recent ones in memory. */
class AppIconLoader(private val context: Context) {
    private val cache = LruCache<String, ImageBitmap>(300)

    fun cached(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(packageName: String, sizePx: Int = 144): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            bitmap(packageName, sizePx)?.asImageBitmap()?.also { cache.put(packageName, it) }
        }
    }

    /** A small PNG of the icon, sent along with the app so the receiver can show it. */
    fun pngBytes(packageName: String, sizePx: Int = 96): ByteArray? {
        val bitmap = bitmap(packageName, sizePx) ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun bitmap(packageName: String, sizePx: Int): Bitmap? = runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
