package com.glamsolo.phonehop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glamsolo.phonehop.core.Protocol
import com.glamsolo.phonehop.data.NetworkStatus
import com.glamsolo.phonehop.transfer.PeerDevice
import com.glamsolo.phonehop.ui.screens.DevicesScreen
import com.glamsolo.phonehop.ui.screens.HomeScreen
import com.glamsolo.phonehop.ui.theme.PhoneHopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Dialog interactions (kept apart from the screenshot tests). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ru")
class DialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun renameDevice() {
        var renamed: String? = null
        compose.setContent {
            PhoneHopTheme { HomeScreen("Pixel 9 Pro", NetworkStatus(emptyList()), {}, {}, { renamed = it }) }
        }
        compose.onNodeWithText("Pixel 9 Pro").performClick()
        compose.onNodeWithText("Имя телефона").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Телефон Анны")
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("Телефон Анны", renamed)
    }

    @Test
    fun manualAddress() {
        var picked: PeerDevice? = null
        compose.setContent {
            PhoneHopTheme { DevicesScreen(emptyList(), "1 файл · 2 МБ", onPick = { picked = it }, onBack = {}) }
        }
        compose.onNodeWithText("Ввести адрес").performClick()
        compose.onNodeWithTag("address_field").performTextInput("192.168.1.")
        compose.onNodeWithText("Подключиться").performClick()
        compose.onNodeWithText("Введите адрес вида 192.168.1.23").assertIsDisplayed()
        compose.onNodeWithTag("address_field").performTextInput("44:5000")
        compose.onNodeWithText("Подключиться").performClick()
        assertEquals(PeerDevice("192.168.1.44", "192.168.1.44", 5000), picked)
        assertEquals(Protocol.DEFAULT_PORT, PeerDevice("x", "x", Protocol.DEFAULT_PORT).port)
    }
}
