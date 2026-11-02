package com.glamsolo.phonehop.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glamsolo.phonehop.TestFilesProvider
import com.glamsolo.phonehop.installFakeApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataLayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(name: String, size: Int) = File(context.cacheDir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    @Test
    fun `installed user apps are listed with all split apks`() = runBlocking {
        val base = file("weather-base.apk", 4000)
        val split = file("split_config.arm64_v8a.apk", 1500)
        installFakeApp(context, "com.example.weather", "Weather", listOf(base, split))
        installFakeApp(context, "com.example.notes", "Notes", listOf(file("notes.apk", 700)))
        installFakeApp(context, "com.android.systemthing", "System", listOf(file("sys.apk", 10)), system = true)
        installFakeApp(context, "com.example.service", "Background service", listOf(file("svc.apk", 10)), launcher = false)

        val apps = InstalledAppsRepository(context).load()
        val names = apps.map { it.packageName }
        assertTrue("weather listed: $names", "com.example.weather" in names)
        assertTrue("com.example.notes" in names)
        assertFalse("system apps are hidden", "com.android.systemthing" in names)
        assertFalse("apps without a launcher icon are hidden", "com.example.service" in names)

        val weather = apps.first { it.packageName == "com.example.weather" }
        assertEquals("Weather", weather.label)
        assertEquals("3.2.1", weather.versionName)
        assertEquals(321L, weather.versionCode)
        assertEquals(listOf(base.path, split.path), weather.apkPaths)
        assertEquals(5500L, weather.sizeBytes)
        // Sorted by label.
        assertTrue(names.indexOf("com.example.notes") < names.indexOf("com.example.weather"))
    }

    @Test
    fun `picked content uris are resolved with name size and type`() = runBlocking {
        TestFilesProvider.install()
        val photo = file("IMG_2042.jpg", 1234)
        val uri = TestFilesProvider.register(photo, "image/jpeg")
        val picked = FileResolver.resolve(context.contentResolver, listOf(uri)).single()
        assertEquals("IMG_2042.jpg", picked.name)
        assertEquals("IMG_2042.jpg", picked.relativePath)
        assertEquals(1234L, picked.size)
        assertEquals("image/jpeg", picked.mimeType)
    }

    @Test
    fun `device name can be changed and reset`() {
        val settings = DeviceSettings(context)
        val original = settings.deviceName.value
        assertTrue(original.isNotBlank())
        settings.rename("  Pixel of Anna  ")
        assertEquals("Pixel of Anna", settings.deviceName.value)
        assertEquals("Pixel of Anna", DeviceSettings(context).deviceName.value)
        settings.rename("")
        assertEquals(original, settings.deviceName.value)
    }

    @Test
    fun `local addresses never include loopback`() {
        val addresses = LocalAddresses.find()
        assertFalse(addresses.any { it.startsWith("127.") })
        assertNotNull(addresses)
    }
}
