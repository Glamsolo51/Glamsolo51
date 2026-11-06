package com.glamsolo.yetipizza

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glamsolo.yetipizza.ui.Moscow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

data class ShopState(
    val menu: Menu? = null,
    val menuError: String? = null,
    val cart: Map<String, Int> = emptyMap(),
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val pickupTime: String = "asap",
    val comment: String = "",
    val submitting: Boolean = false,
    val formError: String? = null,
    val errorField: String? = null,
    val cooldownUntil: Long = 0,
    val order: Order? = null,
    val orderError: String? = null,
    val hasActiveOrder: Boolean = false,
) {
    val cartCount get() = cart.values.sum()
    val cartTotal get() = cart.entries.sumOf { (id, qty) -> (menu?.items?.find { it.id == id }?.price ?: 0) * qty }
}

class ShopViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val api get() = Api(prefs.serverUrl)
    private val _state = MutableStateFlow(ShopState(cart = prefs.cart, name = prefs.name, phone = prefs.phone, email = prefs.email))
    val state = _state.asStateFlow()

    init {
        loadMenu()
        prefs.orders.firstOrNull()?.let { saved ->
            viewModelScope.launch {
                runCatching { io { api.order(saved.id, saved.token) } }.onSuccess { o ->
                    _state.update { it.copy(hasActiveOrder = o.status !in FINAL) }
                }
            }
        }
    }

    fun loadMenu() = viewModelScope.launch {
        runCatching { io { api.menu() } }
            .onSuccess { menu ->
                _state.update { s ->
                    val cart = s.cart.filterKeys { id -> menu.items.any { it.id == id } }
                    val pickup = s.pickupTime.takeIf { it == "asap" && menu.info.asapAllowed || it in menu.info.slots }
                        ?: if (menu.info.asapAllowed) "asap" else menu.info.slots.firstOrNull() ?: "asap"
                    s.copy(menu = menu, menuError = null, cart = cart, pickupTime = pickup)
                }
                prefs.cart = _state.value.cart
            }
            .onFailure { e -> _state.update { it.copy(menuError = e.message) } }
    }

    val server get() = prefs.serverUrl

    /** Test builds only: points the whole app (shop and staff) at another server, e.g. a computer on the same Wi-Fi. */
    fun setServer(input: String) {
        prefs.serverUrl = serverUrl(input)
        loadMenu()
    }

    fun setQty(id: String, qty: Int) {
        _state.update { s -> s.copy(cart = if (qty <= 0) s.cart - id else s.cart + (id to qty.coerceAtMost(20))) }
        prefs.cart = _state.value.cart
    }

    fun edit(f: (ShopState) -> ShopState) = _state.update { f(it).copy(formError = null, errorField = null) }

    fun submit(onPlaced: () -> Unit) {
        val s = _state.value
        val missing = when {
            s.name.isBlank() -> "name" to "Укажите имя."
            s.phone.count { it.isDigit() } < 10 -> "phone" to "Укажите телефон."
            !Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""").matches(s.email.trim()) -> "email" to "Укажите почту."
            else -> null
        }
        if (missing != null) return _state.update { it.copy(errorField = missing.first, formError = missing.second) }
        _state.update { it.copy(submitting = true, formError = null) }
        viewModelScope.launch {
            runCatching { io { api.createOrder(s.name.trim(), s.phone, s.email.trim(), s.pickupTime, s.comment.trim(), s.cart) } }
                .onSuccess { order ->
                    prefs.name = s.name.trim(); prefs.phone = s.phone; prefs.email = s.email.trim()
                    prefs.orders = listOf(SavedOrder(order.id, order.token)) + prefs.orders
                    prefs.cart = emptyMap()
                    _state.update { it.copy(submitting = false, cart = emptyMap(), comment = "", order = order, hasActiveOrder = true) }
                    onPlaced()
                }
                .onFailure { e ->
                    val api = e as? ApiException
                    _state.update {
                        it.copy(
                            submitting = false,
                            formError = e.message,
                            errorField = api?.field,
                            cooldownUntil = if (api?.status == 429) System.currentTimeMillis() + api.retryAfterSeconds * 1000L else it.cooldownUntil,
                        )
                    }
                    if (api?.status == 409 || api?.field == "pickupTime") loadMenu()
                }
        }
    }

    /**
     * Follows the customer's latest order while its screen is open. The server holds each request until the
     * kitchen changes the order (or 25 seconds pass), so a new status shows within a moment.
     */
    suspend fun watchLatestOrder() {
        val saved = prefs.orders.firstOrNull() ?: return
        var since: Long? = null
        var failures = 0
        while (true) {
            val started = System.currentTimeMillis()
            val result = runCatching { io { api.order(saved.id, saved.token, since) } }
            val o = result.getOrNull()
            if (o == null) {
                _state.update { it.copy(orderError = result.exceptionOrNull()?.message) }
                if ((result.exceptionOrNull() as? ApiException)?.status == 404) return
                delay(minOf(30_000L, 2_000L shl failures.coerceAtMost(4))) // no connection: retry, slower each time
                failures++
                continue
            }
            failures = 0
            val changed = o.updatedAt != since
            since = o.updatedAt
            _state.update { it.copy(order = o, orderError = null, hasActiveOrder = o.status !in FINAL) }
            if (o.status in FINAL) return
            // A busy server answers at once without a change: then wait before asking again.
            if (!changed && System.currentTimeMillis() - started < 2_000) delay(3_000)
        }
    }

    companion object {
        val FINAL = setOf("done", "cancelled")
    }
}

data class AdminState(
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
    val loginError: String? = null,
    val active: List<Order> = emptyList(),
    val fresh: Set<Long> = emptySet(),
    val ordersError: String? = null,
    val updatedAt: Long = 0,
    val historyDate: LocalDate = LocalDate.now(Moscow),
    val history: List<Order>? = null,
    val menu: Menu? = null,
    val reportFrom: LocalDate = LocalDate.now(Moscow),
    val reportTo: LocalDate = LocalDate.now(Moscow),
    val report: Report? = null,
    val settings: Settings? = null,
    val watching: Boolean = false,
    val message: String? = null,
)

class AdminViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val api get() = Api(prefs.serverUrl, prefs.adminToken)
    private val _state = MutableStateFlow(AdminState(loggedIn = prefs.adminToken != null, watching = prefs.watchOrders))
    val state = _state.asStateFlow()
    private var knownIds: Set<Long>? = null

    /** Runs a staff call; a dead session sends the user back to the login form. */
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401) signOutLocally() else _state.update { it.copy(message = e.message) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun login(login: String, password: String) {
        _state.update { it.copy(busy = true, loginError = null) }
        viewModelScope.launch {
            runCatching { io { api.login(login.trim(), password) } }
                .onSuccess { token ->
                    prefs.adminToken = token
                    _state.update { it.copy(loggedIn = true, busy = false) }
                    setWatching(true) // the counter device should ring right away
                }
                .onFailure { e -> _state.update { it.copy(busy = false, loginError = e.message) } }
        }
    }

    fun logout() = viewModelScope.launch {
        runCatching { io { api.logout() } }
        signOutLocally()
    }

    private fun signOutLocally() {
        setWatching(false)
        prefs.adminToken = null
        knownIds = null
        _state.value = AdminState()
    }

    fun refreshOrders() = launch {
        try {
            val orders = io { api.orders() }
            val known = knownIds
            val newIds = if (known == null) emptySet() else orders.map { it.id }.filter { it !in known }.toSet()
            knownIds = (known ?: emptySet()) + orders.map { it.id }
            _state.update {
                it.copy(active = orders, fresh = it.fresh + newIds, ordersError = null, updatedAt = System.currentTimeMillis())
            }
        } catch (e: ApiException) {
            if (e.status == 401) throw e
            _state.update { it.copy(ordersError = e.message) }
        }
    }

    fun setStatus(order: Order, status: String) = launch {
        io { api.setStatus(order.id, status) }
        _state.update { it.copy(fresh = it.fresh - order.id) }
        refreshOrders()
        _state.value.history?.let { loadHistory(_state.value.historyDate) }
    }

    fun loadHistory(date: LocalDate) = launch {
        _state.update { it.copy(historyDate = date) }
        val list = io { api.orders(date.toString()) }
        _state.update { it.copy(history = list) }
    }

    fun closeHistory() = _state.update { it.copy(history = null) }

    fun loadMenu() = launch {
        val menu = io { api.adminMenu() }
        _state.update { it.copy(menu = menu) }
    }

    fun updateItem(item: MenuItem, available: Boolean? = null, price: Int? = null) = launch {
        val updated = io { api.updateItem(item.id, available, price) }
        _state.update { s -> s.copy(menu = s.menu?.copy(items = s.menu.items.map { if (it.id == updated.id) updated else it })) }
    }

    fun loadReport(from: LocalDate, to: LocalDate) = launch {
        val (a, b) = if (from <= to) from to to else to to from
        _state.update { it.copy(reportFrom = a, reportTo = b) }
        val report = io { api.report(a.toString(), b.toString()) }
        val menu = _state.value.menu ?: io { api.adminMenu() }
        _state.update { it.copy(report = report, menu = menu) }
    }

    fun loadSettings() = launch {
        val s = io { api.settings() }
        _state.update { it.copy(settings = s) }
    }

    fun saveSettings(s: Settings) = launch {
        val saved = io { api.saveSettings(s) }
        _state.update { it.copy(settings = saved, message = "Настройки сохранены") }
    }

    fun changePassword(current: String, next: String) = launch {
        io { api.changePassword(current, next) }
        signOutLocally()
        _state.update { it.copy(loginError = "Пароль изменён. Войдите с новым паролем.") }
    }

    fun setWatching(on: Boolean) {
        prefs.watchOrders = on
        if (on) OrderWatchService.start(app) else OrderWatchService.stop(app)
        _state.update { it.copy(watching = on) }
    }
}
