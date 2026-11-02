package com.glamsolo.phonehop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.glamsolo.phonehop.R
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.glamsolo.phonehop.core.Protocol
import com.glamsolo.phonehop.core.TransferException
import com.glamsolo.phonehop.core.TransferStats
import com.glamsolo.phonehop.data.NetworkStatus
import com.glamsolo.phonehop.data.PickedFile
import com.glamsolo.phonehop.transfer.PeerDevice
import com.glamsolo.phonehop.transfer.ReceiveState
import com.glamsolo.phonehop.transfer.ReceivedApp
import com.glamsolo.phonehop.transfer.SavedFile
import com.glamsolo.phonehop.transfer.SendState
import com.glamsolo.phonehop.ui.components.AuroraBackground
import com.glamsolo.phonehop.ui.screens.DevicesScreen
import com.glamsolo.phonehop.ui.screens.HomeScreen
import com.glamsolo.phonehop.ui.screens.ReceiveActions
import com.glamsolo.phonehop.ui.screens.ReceiveScreen
import com.glamsolo.phonehop.ui.screens.ReceiveUiState
import com.glamsolo.phonehop.ui.screens.SelectActions
import com.glamsolo.phonehop.ui.screens.SelectScreen
import com.glamsolo.phonehop.ui.screens.SelectUiState
import com.glamsolo.phonehop.ui.screens.SelectionSummary
import com.glamsolo.phonehop.ui.screens.SendingScreen
import com.glamsolo.phonehop.ui.screens.parseAddress
import com.glamsolo.phonehop.ui.theme.PhoneHopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI behaviour plus reference screenshots (Russian locale, Pixel-sized screen). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            PhoneHopTheme(darkTheme = dark) { AuroraBackground { content() } }
        }
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun home() {
        var sendClicks = 0
        var receiveClicks = 0
        show {
            HomeScreen(
                deviceName = "Pixel 9 Pro",
                network = NetworkStatus(listOf("192.168.1.23")),
                onSend = { sendClicks++ },
                onReceive = { receiveClicks++ },
                onRename = {},
            )
        }
        compose.onNodeWithText("Переезд на новый телефон").assertIsDisplayed()
        compose.onNodeWithText("Wi‑Fi готов").assertIsDisplayed()
        shot("01_home_light")
        compose.onNodeWithText("Отправить").performClick()
        compose.onNodeWithText("Получить").performClick()
        assertEquals(1, sendClicks)
        assertEquals(1, receiveClicks)
    }

    @Test
    fun launcherIcon() {
        compose.setContent {
            Box(
                Modifier
                    .size(216.dp)
                    .clip(RoundedCornerShape(54.dp)),
            ) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
            }
        }
        shot("00_launcher_icon")
    }

    @Test
    fun homeDarkWithoutWifi() {
        show(dark = true) {
            HomeScreen("Pixel 9 Pro", NetworkStatus(emptyList()), {}, {}, {})
        }
        compose.onNodeWithText("Нет Wi‑Fi").assertIsDisplayed()
        shot("02_home_dark_no_wifi")
    }

    private class RecordingSelectActions : SelectActions {
        val toggled = mutableListOf<String>()
        var next = 0
        var tab = -1
        override fun onTab(tab: Int) {
            this.tab = tab
        }
        override fun onQuery(query: String) = Unit
        override fun onToggleApp(packageName: String) {
            toggled += packageName
        }
        override fun onSelectAll(select: Boolean) = Unit
        override fun onPickMedia() = Unit
        override fun onPickFiles() = Unit
        override fun onPickFolder() = Unit
        override fun onRemoveFile(file: PickedFile) = Unit
        override fun onNext() {
            next++
        }
        override fun onBack() = Unit
    }

    @Test
    fun selectApps() {
        val actions = RecordingSelectActions()
        val selected = setOf("org.telegram.messenger", "com.duolingo", "com.whatsapp")
        show {
            SelectScreen(
                SelectUiState(
                    tab = 0,
                    apps = SampleData.apps,
                    selectedApps = selected,
                    summary = SelectionSummary(apps = 3, files = 2, bytes = 640L * 1024 * 1024),
                ),
                actions,
            )
        }
        compose.onNodeWithText("3 приложения · 2 файла").assertIsDisplayed()
        compose.onNodeWithText("Далее").assertIsEnabled()
        shot("03_select_apps")
        compose.onNodeWithText("Spotify").performClick()
        compose.onNodeWithText("Далее").performClick()
        compose.onNodeWithText("Файлы · 2").performClick()
        assertEquals(listOf("com.spotify.music"), actions.toggled)
        assertEquals(1, actions.next)
        assertEquals(1, actions.tab)
    }

    @Test
    fun selectFilesDark() {
        show(dark = true) {
            SelectScreen(
                SelectUiState(
                    tab = 1,
                    apps = SampleData.apps,
                    files = SampleData.files,
                    summary = SelectionSummary(apps = 0, files = 3, bytes = 491_030_000),
                ),
                RecordingSelectActions(),
            )
        }
        compose.onNodeWithText("Договор аренды.pdf").assertIsDisplayed()
        shot("04_select_files_dark")
    }

    @Test
    fun nothingSelectedDisablesNext() {
        show { SelectScreen(SelectUiState(tab = 1), RecordingSelectActions()) }
        compose.onNodeWithText("Выберите, что отправить").assertIsDisplayed()
        compose.onNodeWithText("Далее").assertIsNotEnabled()
    }

    @Test
    fun devices() {
        var picked: PeerDevice? = null
        show {
            DevicesScreen(
                devices = listOf(PeerDevice("Pixel 9 Pro", "192.168.1.23", Protocol.DEFAULT_PORT), PeerDevice("Планшет Xiaomi", "192.168.1.31", Protocol.DEFAULT_PORT)),
                selectionText = "3 приложения · 2 файла · 640 МБ",
                onPick = { picked = it },
                onBack = {},
            )
        }
        shot("05_devices")
        compose.onNodeWithText("Pixel 9 Pro").performClick()
        assertEquals("192.168.1.23", picked?.host)
    }

    @Test
    fun addressParsing() {
        assertEquals(PeerDevice("192.168.1.23", "192.168.1.23", Protocol.DEFAULT_PORT), parseAddress(" 192.168.1.23 "))
        assertEquals(1234, parseAddress("192.168.1.23:1234")?.port)
        assertEquals("pixel.local", parseAddress("pixel.local")?.host)
        assertNull(parseAddress("192.168.1.300"))
        assertNull(parseAddress("192.168.1.2:99999"))
        assertNull(parseAddress("hello world"))
        assertNull(parseAddress(""))
    }

    @Test
    fun sendingProgress() {
        val target = PeerDevice("Pixel 9 Pro", "192.168.1.23", Protocol.DEFAULT_PORT)
        show {
            SendingScreen(
                SendState.Transferring(target, "Pixel 9 Pro", SampleData.manifest, SampleData.progress),
                onCancel = {}, onRetry = {}, onDone = {},
            )
        }
        compose.onNodeWithText("Отправка на «Pixel 9 Pro»").assertIsDisplayed()
        compose.onNodeWithText("4 из 6").assertIsDisplayed()
        shot("06_sending_progress")
    }

    @Test
    fun sendingWaitsForAnswerAndCanBeCancelled() {
        var cancelled = 0
        val target = PeerDevice("Pixel 9 Pro", "192.168.1.23", Protocol.DEFAULT_PORT)
        show {
            SendingScreen(SendState.WaitingForAnswer(target, SampleData.manifest), onCancel = { cancelled++ }, onRetry = {}, onDone = {})
        }
        compose.onNodeWithText("Ждём ответа от «Pixel 9 Pro»").assertIsDisplayed()
        shot("07_sending_waiting")
        compose.onNodeWithText("Отменить").performClick()
        compose.onNodeWithText("Остановить").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun sendingFinishedAndFailed() {
        val target = PeerDevice("Pixel 9 Pro", "192.168.1.23", Protocol.DEFAULT_PORT)
        var done = 0
        show(dark = true) {
            SendingScreen(
                SendState.Finished(target, "Pixel 9 Pro", SampleData.manifest, TransferStats(SampleData.manifest.totalBytes, 6, 31_000)),
                onCancel = {}, onRetry = {}, onDone = { done++ },
            )
        }
        compose.onNodeWithText("Отправлено!").assertIsDisplayed()
        shot("08_sending_done_dark")
        compose.onNodeWithText("Готово").performClick()
        assertEquals(1, done)
    }

    @Test
    fun sendingFailedOffersRetry() {
        var retried = 0
        val target = PeerDevice("Pixel 9 Pro", "192.168.1.23", Protocol.DEFAULT_PORT)
        show {
            SendingScreen(SendState.Failed(target, TransferException.Reason.DECLINED, null), onCancel = {}, onRetry = { retried++ }, onDone = {})
        }
        compose.onNodeWithText("Получатель отклонил передачу.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").performClick()
        assertEquals(1, retried)
    }

    private class RecordingReceiveActions : ReceiveActions {
        var accepted = 0
        var declined = 0
        val installed = mutableListOf<List<ReceivedApp>>()
        override fun onAccept() {
            accepted++
        }
        override fun onDecline() {
            declined++
        }
        override fun onCancelTransfer() = Unit
        override fun onReceiveMore() = Unit
        override fun onInstall(apps: List<ReceivedApp>) {
            installed += apps
        }
        override fun onOpenFile(file: SavedFile) = Unit
        override fun onClose() = Unit
    }

    private fun receiveUi(state: ReceiveState, received: List<com.glamsolo.phonehop.transfer.ReceivedItem> = emptyList()) =
        ReceiveUiState(state, "Pixel 9 Pro", listOf("192.168.1.23"), received, SampleData.installStatus)

    @Test
    fun receiveListening() {
        show { ReceiveScreen(receiveUi(ReceiveState.Listening(Protocol.DEFAULT_PORT)), RecordingReceiveActions()) }
        compose.onNodeWithText("Готов к приёму").assertIsDisplayed()
        compose.onNodeWithText("Виден как «Pixel 9 Pro»").assertIsDisplayed()
        compose.onNodeWithTag("address").assertIsDisplayed()
        shot("09_receive_listening")
    }

    @Test
    fun incomingRequestCanBeAcceptedOrDeclined() {
        val actions = RecordingReceiveActions()
        show(dark = true) { ReceiveScreen(receiveUi(ReceiveState.Incoming(Protocol.DEFAULT_PORT, SampleData.request)), actions) }
        compose.onNodeWithText("«Galaxy S10» хочет передать вам").assertIsDisplayed()
        compose.onNodeWithText("2 приложения · 4 файла", substring = true).assertIsDisplayed()
        shot("10_receive_incoming_dark")
        compose.onNodeWithText("Принять").performClick()
        compose.onNodeWithText("Отклонить").performClick()
        assertEquals(1, actions.accepted)
        assertEquals(1, actions.declined)
    }

    @Test
    fun receiveProgress() {
        show {
            ReceiveScreen(
                receiveUi(ReceiveState.Transferring(Protocol.DEFAULT_PORT, SampleData.request, SampleData.progress)),
                RecordingReceiveActions(),
            )
        }
        compose.onNodeWithText("Получение с «Galaxy S10»").assertIsDisplayed()
        compose.onNodeWithTag("progress_ring").assertIsDisplayed()
        shot("11_receive_progress")
    }

    @Test
    fun receivedAppsCanBeInstalled() {
        val actions = RecordingReceiveActions()
        show {
            ReceiveScreen(
                receiveUi(
                    ReceiveState.Finished(Protocol.DEFAULT_PORT, SampleData.request, TransferStats(SampleData.manifest.totalBytes, 6, 42_000)),
                    SampleData.receivedApps + SampleData.receivedFiles,
                ),
                actions,
            )
        }
        compose.onNodeWithText("Получено!").assertIsDisplayed()
        compose.onNodeWithText("Установлено").assertIsDisplayed()
        shot("12_receive_done")
        compose.onNodeWithTag("install_com.spotify.music").performClick()
        assertEquals(listOf("com.spotify.music"), actions.installed.single().map { it.packageName })
    }
}
