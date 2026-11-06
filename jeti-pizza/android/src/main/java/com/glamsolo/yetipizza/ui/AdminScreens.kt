package com.glamsolo.yetipizza.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.yetipizza.AdminState
import com.glamsolo.yetipizza.MenuItem
import com.glamsolo.yetipizza.Order
import com.glamsolo.yetipizza.Settings
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the staff screens can ask for; implemented by AdminViewModel plus a couple of intents. */
interface AdminActions {
    fun login(login: String, password: String)
    fun refreshOrders()
    fun setStatus(order: Order, status: String)
    fun confirmPayment(order: Order)
    fun loadHistory(date: LocalDate)
    fun closeHistory()
    fun loadMenu()
    fun updateItem(item: MenuItem, available: Boolean? = null, price: Int? = null)
    fun loadReport(from: LocalDate, to: LocalDate)
    fun loadSettings()
    fun saveSettings(s: Settings)
    fun changePassword(current: String, next: String)
    fun setWatching(on: Boolean)
    fun logout()
    fun clearMessage()
    fun call(phone: String)
    fun exit()
}

enum class AdminTab(val title: String, val icon: ImageVector) {
    Orders("Заказы", Icons.AutoMirrored.Outlined.ReceiptLong),
    Menu("Меню", Icons.Outlined.RestaurantMenu),
    Reports("Отчёты", Icons.Outlined.BarChart),
    Settings("Настройки", Icons.Outlined.Settings),
}

private val NEXT = mapOf("new" to ("cooking" to "В работу"), "cooking" to ("ready" to "Готов"), "ready" to ("done" to "Выдан"))
private val dayFormat = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("ru"))
private val shortDay = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(state: AdminState, actions: AdminActions) {
    var login by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = Yeti.Charcoal,
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = { IconButton(onClick = actions::exit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "К меню пиццерии") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Yeti.Charcoal),
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().padding(24.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Кабинет\nпиццерии", style = MaterialTheme.typography.headlineLarge)
            Text("Для сотрудников «Йети Пиццы»: заказы, стоп-лист и отчёты.", color = Yeti.Muted)
            OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            state.loginError?.let { ErrorText(it) }
            Button(
                onClick = { actions.login(login, password) },
                enabled = !state.busy && login.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text(if (state.busy) "Входим…" else "Войти") }
        }
    }
}

@Composable
private fun ErrorText(text: String) = Text(
    text, color = MaterialTheme.colorScheme.onErrorContainer,
    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium).padding(12.dp),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(state: AdminState, actions: AdminActions, tab: AdminTab, onTab: (AdminTab) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.clearMessage()
        }
    }
    val newCount = state.active.count { it.status == "new" }
    Scaffold(
        containerColor = Yeti.Charcoal,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(tab.title, style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = actions::exit) { Icon(Icons.Outlined.Storefront, "Открыть меню для покупателей") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Yeti.Charcoal),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Yeti.Panel) {
                AdminTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        icon = {
                            BadgedBox(badge = { if (t == AdminTab.Orders && newCount > 0) Badge { Text("$newCount") } }) { Icon(t.icon, null) }
                        },
                        label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Yeti.Panel2, selectedIconColor = Yeti.Cream, selectedTextColor = Yeti.Cream),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                AdminTab.Orders -> OrdersTab(state, actions)
                AdminTab.Menu -> MenuTab(state, actions)
                AdminTab.Reports -> ReportsTab(state, actions)
                AdminTab.Settings -> SettingsTab(state, actions)
            }
        }
    }
}

/* ---------- Orders ---------- */

@Composable
private fun OrdersTab(state: AdminState, actions: AdminActions) {
    // Polls while the board is on screen; the background service rings separately.
    LaunchedEffect(Unit) {
        while (true) {
            actions.refreshOrders()
            delay(5_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(selected = state.history == null, onClick = actions::closeHistory, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Текущие") }
                SegmentedButton(selected = state.history != null, onClick = { actions.loadHistory(state.historyDate) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("История") }
            }
        }
        state.ordersError?.let { Text(it, color = Yeti.Warn, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.history != null) History(state, actions) else Board(state, actions)
    }
}

@Composable
private fun Board(state: AdminState, actions: AdminActions) {
    val columns = listOf("new" to "Новые", "cooking" to "Готовятся", "ready" to "Готовы к выдаче")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            // Counter tablet: the same three columns as on the website.
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                columns.forEach { (status, title) ->
                    val list = state.active.filter { it.status == status }
                    Column(Modifier.weight(1f).background(Yeti.Panel, MaterialTheme.shapes.large).padding(10.dp)) {
                        Row(Modifier.padding(6.dp)) {
                            Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${list.size}", color = Yeti.Muted, fontWeight = FontWeight.ExtraBold)
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(list, key = { it.id }) { OrderCard(it, it.id in state.fresh, false, actions) }
                        }
                    }
                }
            }
        } else {
            var filter by rememberSaveable { mutableStateOf("new") }
            Column {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(columns) { (status, title) ->
                        val n = state.active.count { it.status == status }
                        FilterChip(
                            selected = filter == status, onClick = { filter = status },
                            label = { Text("$title · $n") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Panel2, selectedLabelColor = Yeti.Cream),
                        )
                    }
                }
                val list = state.active.filter { it.status == filter }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (list.isEmpty()) {
                        item {
                            Text(
                                if (filter == "new") "Новых заказов нет. Телефон позвонит, когда придёт заказ." else "Пусто",
                                color = Yeti.Muted, modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    items(list, key = { it.id }) { OrderCard(it, it.id in state.fresh, false, actions) }
                }
            }
        }
    }
}

@Composable
private fun History(state: AdminState, actions: AdminActions) {
    Column {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.loadHistory(state.historyDate.minusDays(1)) }) { Icon(Icons.Outlined.ChevronLeft, "Предыдущий день") }
            Text(state.historyDate.format(dayFormat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { actions.loadHistory(state.historyDate.plusDays(1)) },
                enabled = state.historyDate < LocalDate.now(Moscow),
            ) { Icon(Icons.Outlined.ChevronRight, "Следующий день") }
        }
        val list = state.history.orEmpty()
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (list.isEmpty()) item { Text("В этот день заказов не было.", color = Yeti.Muted) }
            items(list, key = { it.id }) { OrderCard(it, false, true, actions) }
        }
    }
}

@Composable
fun OrderCard(o: Order, fresh: Boolean, history: Boolean, actions: AdminActions) {
    val now = System.currentTimeMillis()
    val late = o.status in setOf("new", "cooking") && o.pickupAt < now
    var confirmCancel by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var confirmPay by remember { mutableStateOf(false) }
    Surface(
        color = Yeti.Panel2,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (fresh) 2.dp else 1.dp, if (fresh) Yeti.Sign else Yeti.Line),
        modifier = Modifier.fillMaxWidth().testTag("order-${o.id}"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("№ ${o.id}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text(if (o.source == "app") "ПРИЛОЖЕНИЕ" else "САЙТ", color = Yeti.Wood, style = MaterialTheme.typography.labelSmall)
                if (history) {
                    Spacer(Modifier.width(8.dp))
                    Text(STATUS_STAFF.getValue(o.status), style = MaterialTheme.typography.labelMedium,
                        color = when (o.status) { "done" -> Yeti.Led; "cancelled" -> MaterialTheme.colorScheme.error; else -> Yeti.Cream })
                }
            }
            Text(
                if (o.asap) "Как можно скорее · к ${timeText(o.pickupAt)}" else "К ${timeText(o.pickupAt)}",
                fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = if (late) MaterialTheme.colorScheme.error else Yeti.Cream,
            )
            Text(
                if (late) "Время выдачи уже прошло" else "Создан в ${timeText(o.createdAt)} · ${((now - o.createdAt) / 60_000).coerceAtLeast(0)} мин назад",
                color = Yeti.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = { actions.call(o.phone) }, colors = ButtonDefaults.textButtonColors(contentColor = Yeti.Cream)) {
                    Text(phoneText(o.phone), textDecoration = TextDecoration.Underline)
                }
            }
            HorizontalDivider(color = Yeti.LineStrong)
            o.items.forEach { line ->
                Row {
                    Text("${line.qty} × ", fontWeight = FontWeight.ExtraBold)
                    Text(line.name + if (line.unit.isNotBlank()) " · ${line.unit}" else "", Modifier.weight(1f))
                    Text(rub(line.price * line.qty), color = Yeti.Muted)
                }
            }
            HorizontalDivider(color = Yeti.LineStrong)
            if (o.payment == "transfer") {
                val (label, color) = when (o.paymentStatus) {
                    "paid" -> "Оплачено переводом ✓" to Yeti.Led
                    "claimed" -> "Перевод: покупатель оплатил — проверьте банк" to Yeti.Warn
                    else -> "Перевод: ещё не оплачен" to Yeti.Muted
                }
                Row(Modifier.fillMaxWidth().background(color.copy(alpha = .12f), MaterialTheme.shapes.medium).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (o.paymentStatus != "paid") TextButton(onClick = { confirmPay = true }, colors = ButtonDefaults.textButtonColors(contentColor = Yeti.Cream)) { Text("Оплата пришла") }
                }
            } else {
                Text("Оплата при получении", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (o.comment.isNotBlank()) {
                Text("💬 ${o.comment}", color = Yeti.Warn, modifier = Modifier.fillMaxWidth().background(Yeti.Warn.copy(alpha = .12f), MaterialTheme.shapes.medium).padding(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rub(o.total), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (history) {
                    Box {
                        OutlinedButton(onClick = { statusMenu = true }) { Text("Статус") }
                        DropdownMenu(statusMenu, { statusMenu = false }) {
                            STATUS_STAFF.forEach { (status, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    statusMenu = false
                                    if (status == "cancelled") confirmCancel = true else actions.setStatus(o, status)
                                })
                            }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { confirmCancel = true }, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f))) {
                        Text("Отменить", color = MaterialTheme.colorScheme.error)
                    }
                    NEXT[o.status]?.let { (status, label) -> Button(onClick = { actions.setStatus(o, status) }) { Text(label) } }
                }
            }
        }
    }
    if (confirmPay) {
        AlertDialog(
            onDismissRequest = { confirmPay = false },
            title = { Text("Перевод пришёл?") },
            text = { Text("${rub(o.total)} по заказу № ${o.id} поступили на счёт?") },
            confirmButton = { TextButton(onClick = { confirmPay = false; actions.confirmPayment(o) }) { Text("Да, оплачено") } },
            dismissButton = { TextButton(onClick = { confirmPay = false }) { Text("Ещё нет") } },
        )
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Отменить заказ № ${o.id}?") },
            text = { Text("${o.name} увидит, что заказ отменён. Лучше сначала позвонить: ${phoneText(o.phone)}") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; actions.setStatus(o, "cancelled") }) { Text("Отменить заказ") } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Не отменять") } },
        )
    }
}

/* ---------- Menu and stop-list ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuTab(state: AdminState, actions: AdminActions) {
    LaunchedEffect(Unit) { actions.loadMenu() }
    val menu = state.menu ?: return Loading()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MenuItem?>(null) }
    val items = menu.items.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            OutlinedTextField(query, { query = it }, label = { Text("Найти позицию") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "В стоп-листе: ${menu.items.count { !it.available }}. Выключите позицию, когда она закончилась — покупатели увидят «Закончилось».",
                color = Yeti.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        menu.categories.forEach { c ->
            val list = items.filter { it.category == c.id }
            if (list.isEmpty()) return@forEach
            stickyHeader(key = "h-${c.id}") {
                Text(c.name.uppercase(), color = Yeti.Wood, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().background(Yeti.Charcoal).padding(vertical = 10.dp))
            }
            items(list, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(Yeti.Panel, MaterialTheme.shapes.medium).padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Bold, color = if (item.available) Yeti.Cream else Yeti.Muted)
                        if (item.unit.isNotBlank()) Text(item.unit, color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { editing = item }, colors = ButtonDefaults.textButtonColors(contentColor = Yeti.Cream)) {
                        Text(rub(item.price), textDecoration = TextDecoration.Underline)
                    }
                    Switch(
                        checked = item.available,
                        onCheckedChange = { actions.updateItem(item, available = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Yeti.Led, checkedThumbColor = Color(0xFF10200D)),
                    )
                }
            }
        }
    }
    editing?.let { item ->
        var price by remember(item.id) { mutableStateOf(item.price.toString()) }
        val value = price.toIntOrNull()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(item.name) },
            text = {
                OutlinedTextField(
                    price, { price = it.filter(Char::isDigit).take(6) }, label = { Text("Цена, ₽") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(enabled = value != null && value > 0, onClick = { actions.updateItem(item, price = value); editing = null }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/* ---------- Reports ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportsTab(state: AdminState, actions: AdminActions) {
    val today = LocalDate.now(Moscow)
    val presets = listOf(
        "Сегодня" to (today to today),
        "Вчера" to (today.minusDays(1) to today.minusDays(1)),
        "7 дней" to (today.minusDays(6) to today),
        "30 дней" to (today.minusDays(29) to today),
    )
    LaunchedEffect(Unit) { if (state.report == null) actions.loadReport(today, today) }
    var byQty by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val report = state.report

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets) { (label, range) ->
                    FilterChip(
                        selected = state.reportFrom == range.first && state.reportTo == range.second,
                        onClick = { actions.loadReport(range.first, range.second) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Panel2, selectedLabelColor = Yeti.Cream),
                    )
                }
                item {
                    FilterChip(
                        selected = presets.none { (_, r) -> state.reportFrom == r.first && state.reportTo == r.second },
                        onClick = { picking = true }, label = { Text("Период…") },
                    )
                }
            }
            Text(
                if (state.reportFrom == state.reportTo) state.reportFrom.format(dayFormat) else "${state.reportFrom.format(shortDay)} — ${state.reportTo.format(shortDay)}",
                color = Yeti.Muted, modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (report == null) {
            item { Loading() }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Выручка", rub(report.revenue), Modifier.weight(1f))
                    Tile("Заказов", "${report.orders}", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Средний чек", rub(report.averageCheck), Modifier.weight(1f))
                    Tile("Отменено", "${report.cancelled}", Modifier.weight(1f))
                }
                Text("Учитываются все заказы, кроме отменённых.", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                SectionTitle("По позициям", Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(!byQty, { byQty = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("₽") }
                    SegmentedButton(byQty, { byQty = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("шт.") }
                }
            }
        }
        val rows = if (byQty) report.items.sortedByDescending { it.qty } else report.items.sortedByDescending { it.revenue }
        if (rows.isEmpty()) item { Text("За этот период продаж нет.", color = Yeti.Muted) }
        val top = rows.maxOfOrNull { if (byQty) it.qty.toDouble() else it.share } ?: 1.0
        items(rows, key = { it.name + it.unit }) { r ->
            Column(Modifier.fillMaxWidth().background(Yeti.Panel, MaterialTheme.shapes.medium).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name + if (r.unit.isNotBlank()) " · ${r.unit}" else "", fontWeight = FontWeight.Bold)
                        Text(state.menu?.categories?.find { it.id == r.category }?.name ?: "", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rub(r.revenue), fontWeight = FontWeight.ExtraBold)
                        Text("${r.qty} шт. · ${"%.1f".format(r.share * 100)}%", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val fraction = ((if (byQty) r.qty.toDouble() else r.share) / top).toFloat().coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(Yeti.Sign, MaterialTheme.shapes.small))
            }
        }
    }

    if (picking) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val from = picker.selectedStartDateMillis!!.toUtcDate()
                        actions.loadReport(from, picker.selectedEndDateMillis?.toUtcDate() ?: from)
                        picking = false
                    },
                ) { Text("Показать") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Отмена") } },
        ) {
            DateRangePicker(picker, Modifier.weight(1f))
        }
    }
}

// The picker returns UTC midnight of the chosen day.
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(Yeti.Panel, MaterialTheme.shapes.large).padding(14.dp)) {
        Text(label, color = Yeti.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
    }
}

/* ---------- Settings ---------- */

@Composable
private fun SettingsTab(state: AdminState, actions: AdminActions) {
    LaunchedEffect(Unit) { actions.loadSettings() }
    val s = state.settings ?: return Loading()
    var accepting by remember(s) { mutableStateOf(s.acceptingOrders) }
    var cooldown by remember(s) { mutableStateOf(s.cooldownMinutes.toString()) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            SwitchRow("Принимать онлайн-заказы", "Выключите, если кухня перегружена — сайт и приложение покажут, что заказы на паузе.", accepting) { accepting = it }
            OutlinedTextField(
                cooldown, { cooldown = it.filter(Char::isDigit).take(4) },
                label = { Text("Охлаждение между заказами, минут") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("С одного телефона или одной почты нельзя оформить новый заказ раньше этого времени. 0 — без ограничения.") },
            )
            Button(
                onClick = { actions.saveSettings(Settings(accepting, cooldown.toIntOrNull() ?: 0)) },
                enabled = (cooldown.toIntOrNull() ?: -1) in 0..1440,
            ) { Text("Сохранить") }
        }
        Card {
            SwitchRow(
                "Звонок о новых заказах на этом устройстве",
                "Телефон или планшет на кассе проверяет заказы каждые 15 секунд, даже с выключенным экраном.",
                state.watching, actions::setWatching,
            )
        }
        Card {
            Text("Пароль кабинета", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(current, { current = it }, label = { Text("Текущий пароль") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(next, { next = it }, label = { Text("Новый пароль, от 8 символов") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { actions.changePassword(current, next) }, enabled = current.isNotBlank() && next.length >= 8) { Text("Сменить пароль") }
            Text("После смены пароля все устройства выйдут из кабинета.", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = actions::logout, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f))) {
            Text("Выйти из кабинета", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Yeti.Panel, MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(hint, color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Yeti.Led, checkedThumbColor = Color(0xFF10200D)))
    }
}
