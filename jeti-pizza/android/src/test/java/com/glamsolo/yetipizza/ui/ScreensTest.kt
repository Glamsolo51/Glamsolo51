package com.glamsolo.yetipizza.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.glamsolo.yetipizza.AdminState
import com.glamsolo.yetipizza.Category
import com.glamsolo.yetipizza.Menu
import com.glamsolo.yetipizza.MenuItem
import com.glamsolo.yetipizza.Order
import com.glamsolo.yetipizza.OrderLine
import com.glamsolo.yetipizza.Report
import com.glamsolo.yetipizza.ReportRow
import com.glamsolo.yetipizza.Settings
import com.glamsolo.yetipizza.ShopInfo
import com.glamsolo.yetipizza.ShopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

private val info = ShopInfo(
    "Канаш, проспект Ленина, 22", "+7 927 667-21-21", "10:00", "22:00", 20,
    listOf("14:15", "14:30", "14:45", "15:00"), asapAllowed = true, isOpen = true, acceptingOrders = true, mapUrl = "",
)
private val menu = Menu(
    info,
    listOf(Category("pizza", "Пицца"), Category("snacks", "Фри и соусы"), Category("hot", "Горячие напитки")),
    listOf(
        MenuItem("1", "pizza", "Пепперони", "колбаски пепперони, майонезно-томатный соус, лук, сыр, зелень", "330 г", 340, true),
        MenuItem("2", "pizza", "Мясной пир", "куриное филе, колбаса, томаты, сыр, зелень", "355 г", 345, true),
        MenuItem("3", "pizza", "Брокколи", "курица, брокколи, куриный рулет, сыр", "350 г", 315, false),
        MenuItem("4", "snacks", "Картошка фри", "", "150 г", 165, true),
        MenuItem("5", "hot", "Капучино", "Большой", "", 135, true),
    ),
)
private val now = System.currentTimeMillis()
private fun order(id: Long, status: String, comment: String = "") = Order(
    id, status, now - 4 * 60_000, now + 16 * 60_000, asap = id % 2 == 0L, name = listOf("Анна", "Иван", "Мария")[(id % 3).toInt()],
    total = 1020, items = listOf(OrderLine("1", "Пепперони", "330 г", 340, 2), OrderLine("4", "Картошка фри", "150 г", 165, 2)),
    phone = "+79271112233", email = "anna@mail.ru", comment = comment, source = if (id % 2 == 0L) "app" else "web",
)

private object NoActions : AdminActions {
    override fun login(login: String, password: String) {}
    override fun refreshOrders() {}
    override fun setStatus(order: Order, status: String) { lastStatus = status }
    override fun loadHistory(date: LocalDate) {}
    override fun closeHistory() {}
    override fun loadMenu() {}
    override fun updateItem(item: MenuItem, available: Boolean?, price: Int?) {}
    override fun loadReport(from: LocalDate, to: LocalDate) {}
    override fun loadSettings() {}
    override fun saveSettings(s: Settings) {}
    override fun changePassword(current: String, next: String) {}
    override fun setWatching(on: Boolean) {}
    override fun logout() {}
    override fun clearMessage() {}
    override fun call(phone: String) {}
    override fun exit() {}
    var lastStatus: String? = null
}

/** Screens render in Russian on a phone; reference images land in build/screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "ru-w411dp-h891dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun show(name: String, content: @Composable () -> Unit) {
        compose.setContent { YetiTheme(content) }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun menuSectionsOpenOneAtATime() {
        var added: Pair<String, Int>? = null
        show("menu") {
            MenuScreen(ShopState(menu = menu, cart = mapOf("2" to 1), hasActiveOrder = true), { id, q -> added = id to q }, {}, {}, {}, {})
        }
        compose.onNodeWithTag("cart-fab").assertExists() // a round cart button, no bar along the bottom
        compose.onNodeWithContentDescription("Корзина: 1 шт. на 345 ₽").assertExists()
        compose.onNodeWithContentDescription("Вход для персонала").assertDoesNotExist()
        compose.onNodeWithText("Закончилось").assertDoesNotExist() // items are inside the sections

        compose.onNodeWithTag("category-pizza").performClick()
        compose.onRoot().captureRoboImage("build/screenshots/menu-pizza.png")
        compose.onNodeWithContentDescription("В корзину: Пепперони").performClick()
        assertEquals("1" to 1, added)
        compose.onNodeWithText("Закончилось").assertExists()
        compose.onNodeWithText("Картошка фри").assertDoesNotExist()
        compose.onNodeWithText("Дальше: Фри и соусы").performClick()
        compose.onNodeWithText("Картошка фри").assertExists()
        compose.onNodeWithContentDescription("Все разделы").performClick()
        compose.onNodeWithTag("category-hot").assertExists()
    }

    @Test fun longPressOnTheNameOpensStaffLogin() {
        var staff = false
        show("menu-closed") {
            MenuScreen(ShopState(menu = menu.copy(info = info.copy(orderingOpen = false, asapAllowed = false, slots = emptyList(), lastOrder = "21:30"))), { _, _ -> }, {}, {}, { staff = true }, {})
        }
        compose.onNodeWithText("Онлайн-заказы принимаем с 10:00 до 21:30. Ждём вас!").assertExists()
        compose.onNodeWithTag("cart-fab").assertDoesNotExist()
        compose.onNodeWithTag("brand").performTouchInput { longClick() }
        assertTrue(staff)
    }

    @Test fun offlineTestBuildAsksForServer() {
        var typed: String? = "not called"
        show("menu-offline") {
            MenuScreen(ShopState(menuError = "Нет связи с пиццерией. Проверьте интернет и попробуйте ещё раз."), { _, _ -> }, {}, {}, {}, { typed = it }, server = "http://10.0.2.2:8080")
        }
        compose.onNodeWithTag("server").assertExists()
        compose.onNodeWithText("Подключиться").performClick()
        assertEquals("http://10.0.2.2:8080", typed)
    }

    @Test fun cartShowsCooldown() {
        show("cart-cooldown") {
            CartScreen(
                ShopState(menu = menu, cart = mapOf("1" to 2, "4" to 1), name = "Анна", phone = "+7 927 111-22-33", email = "anna@mail.ru", cooldownUntil = System.currentTimeMillis() + 754_000),
                {}, { _, _ -> }, {}, {},
            )
        }
        compose.onNodeWithTag("form-error").assertExists()
        compose.onNodeWithText("Оформить самовывоз").assertExists()
    }

    @Test fun orderStatus() = show("status") {
        StatusScreen(order(12, "cooking"), null, info.address, info.phone, {}, {})
    }

    @Test fun staffOrdersOnPhone() {
        show("staff-orders") {
            AdminScreen(AdminState(loggedIn = true, active = listOf(order(14, "new", "Без лука, пожалуйста"), order(15, "new"), order(13, "cooking")), fresh = setOf(15)), NoActions, AdminTab.Orders) {}
        }
        compose.onNodeWithTag("order-14").assertExists()
        compose.onAllNodesWithText("В работу").assertCountEquals(2) // two new orders on the "Новые" filter
    }

    @Config(qualifiers = "ru-w1280dp-h800dp-xhdpi")
    @Test fun staffOrdersOnTablet() = show("staff-orders-tablet") {
        AdminScreen(
            AdminState(loggedIn = true, active = listOf(order(14, "new", "Без лука"), order(15, "new"), order(13, "cooking"), order(11, "ready")), fresh = setOf(15)),
            NoActions, AdminTab.Orders,
        ) {}
    }

    @Test fun staffReport() = show("staff-report") {
        AdminScreen(
            AdminState(
                loggedIn = true, menu = menu,
                report = Report(
                    "2026-11-06", "2026-11-06", 23, 9_870, 429, 1,
                    listOf(
                        ReportRow("Пепперони", "330 г", "pizza", 14, 4_760, .482),
                        ReportRow("Мясной пир", "355 г", "pizza", 8, 2_760, .28),
                        ReportRow("Картошка фри", "150 г", "snacks", 9, 1_485, .15),
                        ReportRow("Капучино", "", "hot", 6, 810, .082),
                    ),
                ),
            ),
            NoActions, AdminTab.Reports,
        ) {}
    }

    @Test fun staffMenu() = show("staff-menu") {
        AdminScreen(AdminState(loggedIn = true, menu = menu), NoActions, AdminTab.Menu) {}
    }

    @Test fun staffSettings() = show("staff-settings") {
        AdminScreen(AdminState(loggedIn = true, settings = Settings(true, 15), watching = true), NoActions, AdminTab.Settings) {}
    }

    @Test fun staffLogin() = show("staff-login") { LoginScreen(AdminState(loginError = "Неверный логин или пароль."), NoActions) }
}
