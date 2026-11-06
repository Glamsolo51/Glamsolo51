package com.glamsolo.yetipizza

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glamsolo.yetipizza.ui.AdminActions
import com.glamsolo.yetipizza.ui.AdminScreen
import com.glamsolo.yetipizza.ui.AdminTab
import com.glamsolo.yetipizza.ui.CartScreen
import com.glamsolo.yetipizza.ui.LoginScreen
import com.glamsolo.yetipizza.ui.MenuScreen
import com.glamsolo.yetipizza.ui.StatusScreen
import com.glamsolo.yetipizza.ui.YetiTheme
import java.time.LocalDate

enum class Screen { Menu, Cart, Status, Staff }

class MainActivity : ComponentActivity() {
    private val shop: ShopViewModel by viewModels()
    private val admin: AdminViewModel by viewModels()
    private var openStaff by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openStaff = intent.getBooleanExtra(EXTRA_STAFF, false)
        setContent { YetiTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_STAFF, false)) openStaff = true
    }

    private fun dial(phone: String) = startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))

    @Composable
    private fun App() {
        var screen by rememberSaveable { mutableStateOf(Screen.Menu) }
        var tab by rememberSaveable { mutableStateOf(AdminTab.Orders) }
        val shopState by shop.state.collectAsStateWithLifecycle()
        val adminState by admin.state.collectAsStateWithLifecycle()

        LaunchedEffect(openStaff) {
            if (openStaff) {
                screen = Screen.Staff
                tab = AdminTab.Orders
                openStaff = false
            }
        }
        // Android 13+: the order alarm needs the notification permission; ask once the staff turned it on.
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(adminState.watching) {
            if (adminState.watching && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        BackHandler(enabled = screen != Screen.Menu) { screen = Screen.Menu }

        val actions = remember(admin) { staffActions { screen = Screen.Menu } }
        when (screen) {
            Screen.Menu -> MenuScreen(
                state = shopState,
                onQty = shop::setQty,
                onOpenCart = { shop.loadMenu(); screen = Screen.Cart },
                onOpenOrder = { screen = Screen.Status },
                onStaff = { screen = Screen.Staff },
                onRetry = { address -> if (address != null) shop.setServer(address) else shop.loadMenu() },
                server = if (BuildConfig.SERVER_EDITABLE) shop.server else null,
            )
            Screen.Cart -> CartScreen(
                state = shopState,
                onBack = { screen = Screen.Menu },
                onQty = shop::setQty,
                onEdit = shop::edit,
                onSubmit = { shop.submit { screen = Screen.Status } },
            )
            Screen.Status -> {
                LaunchedEffect(Unit) { shop.watchLatestOrder() }
                StatusScreen(
                    order = shopState.order,
                    error = shopState.orderError,
                    address = shopState.menu?.info?.address ?: "пр. Ленина, 22",
                    phone = shopState.menu?.info?.phone ?: "+7 927 667-21-21",
                    onBack = { screen = Screen.Menu },
                    onCall = { dial(shopState.menu?.info?.phone ?: "+79276672121") },
                )
            }
            Screen.Staff ->
                if (adminState.loggedIn) AdminScreen(adminState, actions, tab) { tab = it }
                else LoginScreen(adminState, actions)
        }
    }

    private fun staffActions(exit: () -> Unit) = object : AdminActions {
        override fun login(login: String, password: String) = admin.login(login, password)
        override fun refreshOrders() { admin.refreshOrders() }
        override fun setStatus(order: Order, status: String) { admin.setStatus(order, status) }
        override fun loadHistory(date: LocalDate) { admin.loadHistory(date) }
        override fun closeHistory() = admin.closeHistory()
        override fun loadMenu() { admin.loadMenu() }
        override fun updateItem(item: MenuItem, available: Boolean?, price: Int?) { admin.updateItem(item, available, price) }
        override fun loadReport(from: LocalDate, to: LocalDate) { admin.loadReport(from, to) }
        override fun loadSettings() { admin.loadSettings() }
        override fun saveSettings(s: Settings) { admin.saveSettings(s) }
        override fun changePassword(current: String, next: String) { admin.changePassword(current, next) }
        override fun setWatching(on: Boolean) = admin.setWatching(on)
        override fun logout() { admin.logout() }
        override fun clearMessage() = admin.clearMessage()
        override fun call(phone: String) = dial(phone)
        override fun exit() = exit()
    }

    companion object {
        const val EXTRA_STAFF = "staff"
    }
}
