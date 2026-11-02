package com.glamsolo.phonehop

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glamsolo.phonehop.transfer.ReceiveState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real activity with the real object graph: navigation and receive mode wiring. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "ru")
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun grantPermissions() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun stopReceiving() {
        app.graph.receiver.stop()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun receiveModeStartsListeningAndStopsOnBack() {
        compose.onNodeWithText("Переезд на новый телефон").assertIsDisplayed()
        compose.onNodeWithText("Получить").performClick()
        waitForText("Готов к приёму")
        val state = app.graph.receiver.state.value
        assert(state is ReceiveState.Listening) { "receiver state: $state" }

        compose.onNodeWithContentDescription("Назад").performClick()
        waitForText("Переезд на новый телефон")
        assertEquals(ReceiveState.Idle, app.graph.receiver.state.value)
    }

    @Test
    fun sendFlowOpensTheSelectionScreen() {
        compose.onNodeWithText("Отправить").performClick()
        waitForText("Что отправить?")
        compose.onNodeWithText("Выберите, что отправить").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        waitForText("Переезд на новый телефон")
    }
}
