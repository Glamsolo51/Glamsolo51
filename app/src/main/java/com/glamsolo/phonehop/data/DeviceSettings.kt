package com.glamsolo.phonehop.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The name other phones see. Defaults to the system device name. */
class DeviceSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("phonehop", Context.MODE_PRIVATE)
    private val _deviceName = MutableStateFlow(prefs.getString(KEY_NAME, null) ?: defaultName())
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    fun rename(name: String) {
        val clean = name.trim().take(MAX_LENGTH)
        if (clean.isEmpty()) {
            prefs.edit { remove(KEY_NAME) }
            _deviceName.value = defaultName()
        } else {
            prefs.edit { putString(KEY_NAME, clean) }
            _deviceName.value = clean
        }
    }

    private fun defaultName(): String {
        val system = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!system.isNullOrBlank()) return system.take(MAX_LENGTH)
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase() }
        val model = Build.MODEL.orEmpty()
        val name = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return name.trim().ifEmpty { "Android" }.take(MAX_LENGTH)
    }

    companion object {
        const val MAX_LENGTH = 40
        private const val KEY_NAME = "device_name"
    }
}
