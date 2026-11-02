package com.glamsolo.phonehop

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.glamsolo.phonehop.transfer.Discovery
import com.glamsolo.phonehop.transfer.PeerDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Serves registered local files as content:// URIs, like a real document provider. */
class TestFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = files.getValue(uri.lastPathSegment!!)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.length()))
        }
    }

    override fun getType(uri: Uri): String? = types[uri.lastPathSegment]

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(uri.lastPathSegment!!), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.glamsolo.phonehop.testfiles"
        private val files = ConcurrentHashMap<String, File>()
        private val types = ConcurrentHashMap<String, String>()

        fun install() {
            Robolectric.setupContentProvider(TestFilesProvider::class.java, AUTHORITY)
        }

        fun register(file: File, mime: String): Uri {
            val id = "doc${files.size + 1}"
            files[id] = file
            types[id] = mime
            return Uri.parse("content://$AUTHORITY/$id")
        }
    }
}

/** Installs a fake user app with the given APK files into Robolectric's package manager. */
fun installFakeApp(
    context: Context,
    packageName: String,
    label: String,
    apks: List<File>,
    system: Boolean = false,
    launcher: Boolean = true,
) {
    val app = ApplicationInfo().apply {
        this.packageName = packageName
        sourceDir = apks.first().path
        publicSourceDir = sourceDir
        splitSourceDirs = apks.drop(1).map { it.path }.toTypedArray().takeIf { it.isNotEmpty() }
        flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        enabled = true
        nonLocalizedLabel = label
    }
    val info = PackageInfo().apply {
        this.packageName = packageName
        versionName = "3.2.1"
        @Suppress("DEPRECATION")
        versionCode = 321
        longVersionCode = 321
        applicationInfo = app
    }
    val pm = shadowOf(context.packageManager)
    pm.installPackage(info)
    if (launcher) {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }
}

/** Discovery that does not touch the network. */
class FakeDiscovery : Discovery {
    val advertised = MutableStateFlow<List<PeerDevice>>(emptyList())

    override fun advertise(name: String, port: Int): AutoCloseable {
        val device = PeerDevice(name, "127.0.0.1", port)
        advertised.value = advertised.value + device
        return AutoCloseable { advertised.value = advertised.value - device }
    }

    override fun discover(): Flow<List<PeerDevice>> = advertised
}
