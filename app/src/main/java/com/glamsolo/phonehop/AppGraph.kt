package com.glamsolo.phonehop

import android.content.Context
import com.glamsolo.phonehop.data.AppIconLoader
import com.glamsolo.phonehop.data.DeviceSettings
import com.glamsolo.phonehop.data.InstalledAppsRepository
import com.glamsolo.phonehop.data.NetworkMonitor
import com.glamsolo.phonehop.install.InstallController
import com.glamsolo.phonehop.transfer.Discovery
import com.glamsolo.phonehop.transfer.FileStores
import com.glamsolo.phonehop.transfer.NsdDiscovery
import com.glamsolo.phonehop.transfer.ReceiveController
import com.glamsolo.phonehop.transfer.SendController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppGraph(
    context: Context,
    val discovery: Discovery = NsdDiscovery(context.applicationContext),
) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = DeviceSettings(appContext)
    val network = NetworkMonitor(appContext, scope)
    val apps = InstalledAppsRepository(appContext)
    val icons = AppIconLoader(appContext)
    val installer = InstallController(appContext, scope)
    val sender = SendController(appContext, scope, settings, icons)
    val receiver = ReceiveController(
        context = appContext,
        scope = scope,
        settings = settings,
        discovery = discovery,
        fileStoreFactory = { FileStores.default(appContext) },
    )
}
