package com.glamsolo.yetipizza.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.yetipizza.MenuItem
import com.glamsolo.yetipizza.Order
import com.glamsolo.yetipizza.ShopState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Stepper(qty: Int, label: String, onQty: (Int) -> Unit) {
    Row(
        Modifier.border(1.dp, Yeti.LineStrong, MaterialTheme.shapes.medium).semantics { contentDescription = "$label: $qty шт." },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cream = ButtonDefaults.textButtonColors(contentColor = Yeti.Cream)
        TextButton(onClick = { onQty(qty - 1) }, Modifier.semantics { contentDescription = "Убрать одну" }, colors = cream) { Text("−", fontSize = 20.sp) }
        Text("$qty", fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { onQty(qty + 1) }, enabled = qty < 20, modifier = Modifier.semantics { contentDescription = "Добавить ещё" }, colors = cream) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.titleLarge, modifier = modifier)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    state: ShopState,
    onQty: (String, Int) -> Unit,
    onOpenCart: () -> Unit,
    onOpenOrder: () -> Unit,
    onStaff: () -> Unit,
    onRetry: () -> Unit,
) {
    val menu = state.menu
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val categories = menu?.categories?.filter { c -> menu.items.any { it.category == c.id } }.orEmpty()
    // Index of each category header inside the LazyColumn: hero (0), chips (1), then header + items per category.
    val headerIndex = remember(menu) {
        var i = 2
        categories.associate { c -> c.id to i.also { i += 1 + menu!!.items.count { it.category == c.id } } }
    }

    // Recomputed only when the highlighted category changes, not on every scrolled pixel.
    val currentCategory by remember(headerIndex) {
        derivedStateOf { headerIndex.entries.lastOrNull { it.value <= list.firstVisibleItemIndex + 1 }?.key }
    }

    Scaffold(
        containerColor = Yeti.Charcoal,
        bottomBar = {
            if (state.cartCount > 0) {
                Button(
                    onClick = onOpenCart,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(58.dp).testTag("cart-bar"),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Outlined.ShoppingCart, null)
                    Spacer(Modifier.width(10.dp))
                    Text("${state.cartCount} шт. в корзине", Modifier.weight(1f))
                    Text(rub(state.cartTotal), style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp))
                }
            }
        },
    ) { padding ->
        if (menu == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.menuError == null) CircularProgressIndicator()
                else Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.menuError, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("Повторить") }
                }
            }
            return@Scaffold
        }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp), modifier = Modifier.statusBarsPadding()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (menu.info.isOpen) Yeti.Led else Yeti.Muted))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${if (menu.info.isOpen) "Открыто" else "Закрыто"} · ${menu.info.open}–${menu.info.close}",
                            color = Yeti.Muted, modifier = Modifier.weight(1f),
                        )
                        if (state.hasActiveOrder) OutlinedButton(onClick = onOpenOrder) { Text("Мой заказ") }
                        IconButton(onClick = onStaff) { Icon(Icons.Outlined.Badge, "Вход для персонала", tint = Yeti.Muted) }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "ЙЕТИ\nПИЦЦА",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 52.sp, lineHeight = 56.sp,
                            shadow = Shadow(Yeti.Sign, Offset(0f, 10f), 0f),
                        ),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Самовывоз · ${menu.info.address}", style = MaterialTheme.typography.titleMedium)
                    Text("Готовим ~${menu.info.prepMinutes} минут, оплата при получении", color = Yeti.Muted)
                    notice(state)?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(it, color = Yeti.Warn, modifier = Modifier.background(Yeti.Warn.copy(alpha = .12f), MaterialTheme.shapes.medium).padding(12.dp))
                    }
                }
            }
            stickyHeader {
                LazyRow(
                    Modifier.fillMaxWidth().background(Yeti.Charcoal).padding(vertical = 10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(categories, key = { it.id }) { c ->
                        FilterChip(
                            selected = currentCategory == c.id,
                            onClick = { scope.launch { list.animateScrollToItem(headerIndex.getValue(c.id)) } },
                            label = { Text(c.name) },
                            leadingIcon = { Icon(categoryIcon(c.id), null, Modifier.size(18.dp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Panel2, selectedLabelColor = Yeti.Cream),
                        )
                    }
                }
            }
            categories.forEach { c ->
                item(key = "h-${c.id}") {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(categoryIcon(c.id), null, tint = Yeti.Wood, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        SectionTitle(c.name)
                    }
                }
                items(menu.items.filter { it.category == c.id }, key = { it.id }) { item ->
                    ItemCard(item, state.cart[item.id] ?: 0) { onQty(item.id, it) }
                }
            }
        }
    }
}

private fun notice(state: ShopState): String? {
    val info = state.menu?.info ?: return null
    return when {
        !info.acceptingOrders -> "Онлайн-заказы сейчас на паузе — позвоните нам: ${info.phone}"
        info.slots.isEmpty() && !info.asapAllowed -> "На сегодня заказы закончились. Ждём вас завтра с ${info.open}."
        else -> null
    }
}

@Composable
private fun ItemCard(item: MenuItem, qty: Int, onQty: (Int) -> Unit) {
    Surface(
        color = Yeti.Panel,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (qty > 0) 2.dp else 1.dp, if (qty > 0) Yeti.Sign else Yeti.Line),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, color = if (item.available) Yeti.Cream else Yeti.Muted)
            if (item.description.isNotBlank()) {
                Text(item.description, style = MaterialTheme.typography.bodySmall, color = Yeti.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rub(item.price), style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp))
                if (item.unit.isNotBlank()) Text(" · ${item.unit}", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                when {
                    !item.available -> Text("Закончилось", color = Yeti.Muted, style = MaterialTheme.typography.labelLarge)
                    qty > 0 -> Stepper(qty, item.name, onQty)
                    else -> OutlinedButton(
                        onClick = { onQty(1) },
                        border = BorderStroke(1.dp, Yeti.Sign),
                        modifier = Modifier.semantics { contentDescription = "В корзину: ${item.name}" },
                    ) { Text("+ В корзину") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    state: ShopState,
    onBack: () -> Unit,
    onQty: (String, Int) -> Unit,
    onEdit: ((ShopState) -> ShopState) -> Unit,
    onSubmit: () -> Unit,
) {
    val menu = state.menu ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.cooldownUntil) {
        while (state.cooldownUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }
    val cooldownLeft = ((state.cooldownUntil - now) / 1000).coerceAtLeast(0)
    val canOrder = menu.info.acceptingOrders && (menu.info.asapAllowed || menu.info.slots.isNotEmpty())
    val lines = state.cart.mapNotNull { (id, qty) -> menu.items.find { it.id == id }?.let { it to qty } }
    val error = when {
        cooldownLeft > 0 -> "С этого телефона или почты недавно уже был заказ. Новый можно оформить через ${cooldownLeft / 60}:${"%02d".format(cooldownLeft % 60)}."
        !canOrder -> notice(state)
        else -> state.formError
    }

    Scaffold(
        containerColor = Yeti.Charcoal,
        topBar = {
            TopAppBar(
                title = { Text("ВАШ ЗАКАЗ", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Yeti.Charcoal),
            )
        },
        bottomBar = {
            if (lines.isNotEmpty()) {
                Column(Modifier.background(Yeti.Panel).navigationBarsPadding().imePadding().padding(16.dp)) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium).padding(12.dp).testTag("form-error"))
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Итого, оплата при получении", color = Yeti.Muted, modifier = Modifier.weight(1f))
                        Text(rub(state.cartTotal), style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onSubmit,
                        enabled = canOrder && cooldownLeft == 0L && !state.submitting && lines.all { it.first.available },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.large,
                    ) { Text(if (state.submitting) "Отправляем…" else "Оформить самовывоз") }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            if (lines.isEmpty()) item { Text("Корзина пуста. Добавьте что-нибудь вкусное из меню.", color = Yeti.Muted) }
            items(lines, key = { it.first.id }) { (item, qty) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (item.available) listOf(rub(item.price), item.unit).filter { it.isNotBlank() }.joinToString(" · ") else "Закончилось — уберите из заказа",
                            color = if (item.available) Yeti.Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Stepper(qty, item.name) { onQty(item.id, it) }
                    Text(rub(item.price * qty), fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(78.dp), textAlign = TextAlign.End)
                }
                HorizontalDivider(color = Yeti.Line)
            }
            if (lines.isEmpty()) return@LazyColumn
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
                    Text("САМОВЫВОЗ · ${menu.info.address.uppercase()}", color = Yeti.Wood, style = MaterialTheme.typography.labelMedium)
                    Field("Имя", state.name, state.errorField == "name") { v -> onEdit { it.copy(name = v) } }
                    Field("Телефон", state.phone, state.errorField == "phone", KeyboardType.Phone, "+7 900 000-00-00") { v -> onEdit { it.copy(phone = v) } }
                    Field("Почта", state.email, state.errorField == "email", KeyboardType.Email, "you@mail.ru") { v -> onEdit { it.copy(email = v) } }
                    Text("Когда заберёте", color = Yeti.Muted, style = MaterialTheme.typography.labelLarge)
                    val options = (if (menu.info.asapAllowed) listOf("asap") else emptyList()) + menu.info.slots
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options) { o ->
                            FilterChip(
                                selected = state.pickupTime == o,
                                onClick = { onEdit { it.copy(pickupTime = o) } },
                                label = { Text(if (o == "asap") "Как можно скорее" else "к $o") },
                                leadingIcon = if (state.pickupTime == o) ({ Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }) else null,
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Sign, selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.comment, onValueChange = { v -> onEdit { it.copy(comment = v.take(300)) } },
                        label = { Text("Комментарий") }, placeholder = { Text("Например: порезать на 8 кусочков") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, isError: Boolean, type: KeyboardType = KeyboardType.Text, hint: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, isError = isError, singleLine = true,
        placeholder = hint?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(order: Order?, error: String?, address: String, phone: String, onBack: () -> Unit, onCall: () -> Unit) {
    Scaffold(
        containerColor = Yeti.Charcoal,
        topBar = {
            TopAppBar(
                title = { Text(order?.let { "ЗАКАЗ № ${it.id}" } ?: "ЗАКАЗ", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Yeti.Charcoal),
            )
        },
    ) { padding ->
        if (order == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (error != null) Text(error, Modifier.padding(32.dp)) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (order.status == "cancelled") {
                item {
                    Text(
                        "Заказ отменён. Если это ошибка — позвоните нам: $phone",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium).padding(14.dp),
                    )
                }
            } else {
                val steps = listOf("new", "cooking", "ready", "done")
                val at = steps.indexOf(order.status)
                items(steps.size) { i ->
                    val now = i == at
                    Row(
                        Modifier.fillMaxWidth()
                            .border(1.dp, if (now) Yeti.Sign else Yeti.Line, MaterialTheme.shapes.medium)
                            .background(if (now) Yeti.Sign.copy(alpha = .18f) else Color.Transparent, MaterialTheme.shapes.medium)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (i < at) Yeti.Led else if (now) Yeti.Sign else Yeti.Panel2),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i < at) Icon(Icons.Outlined.Check, null, tint = Color(0xFF10200D), modifier = Modifier.size(18.dp))
                            else Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(STATUS_CUSTOMER.getValue(steps[i]), color = if (i <= at) Yeti.Cream else Yeti.Muted, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item {
                Text(
                    if (order.status == "done") "Приятного аппетита!" else "Заберите ${if (order.asap) "примерно " else ""}к ${timeText(order.pickupAt)} · $address",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp), modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(order.items) { line ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${line.name} × ${line.qty}", Modifier.weight(1f))
                    Text(rub(line.price * line.qty))
                }
                HorizontalDivider(color = Yeti.Line, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Итого при получении", Modifier.weight(1f))
                    Text(rub(order.total), style = MaterialTheme.typography.headlineSmall)
                }
                Text("Статус обновляется сам.", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onCall, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Call, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Позвонить в пиццерию")
                }
            }
        }
    }
}
