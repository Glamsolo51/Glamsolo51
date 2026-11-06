package com.glamsolo.yetipizza.ui

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glamsolo.yetipizza.Category
import com.glamsolo.yetipizza.Menu
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
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = modifier)

/** The yeti's footprints from the bottom of the menu screens. */
@Composable
fun Footprints(modifier: Modifier = Modifier, count: Int = 5) {
    Canvas(modifier.size(width = (count * 26).dp, height = 34.dp)) {
        val step = size.width / count
        repeat(count) { i ->
            val x = step * i + step / 2
            val y = if (i % 2 == 0) size.height * .38f else size.height * .62f
            val c = Yeti.Cream.copy(alpha = .45f)
            drawOval(c, topLeft = Offset(x - 6.dp.toPx(), y - 4.dp.toPx()), size = Size(12.dp.toPx(), 16.dp.toPx()))
            listOf(-6f to -9f, -2.5f to -12f, 2.5f to -12f, 6f to -9f).forEach { (dx, dy) ->
                drawCircle(c, radius = 2.dp.toPx(), center = Offset(x + dx.dp.toPx(), y + dy.dp.toPx()))
            }
        }
    }
}

private val SORTS = listOf("" to "как в меню", "asc" to "сначала дешевле", "desc" to "сначала дороже")

/** What the menu shows: no boxes or cups (the cart adds them), and only items with the chosen composition. */
private fun visibleItems(menu: Menu, tag: String) = menu.items.filter { it.id !in menu.packIds && (tag.isEmpty() || tag in it.tags) }
private fun shownCategories(menu: Menu, tag: String = "") = visibleItems(menu, tag).map { it.category }.toSet().let { used -> menu.categories.filter { it.id in used } }

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, label = { Text(label) }, shape = RoundedCornerShape(50),
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Sign, selectedLabelColor = Yeti.OnSign),
)

/** Composition filters, and (in a section) the price order. */
@Composable
private fun FilterRow(menu: Menu, state: ShopState, onTag: (String) -> Unit, onSort: ((String) -> Unit)?, edge: Dp = 16.dp) {
    val shownTags = menu.tags.filter { t -> menu.items.any { it.id !in menu.packIds && t.id in it.tags } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = edge)) {
        if (onSort != null) item {
            val next = SORTS[(SORTS.indexOfFirst { it.first == state.sort } + 1) % SORTS.size].first
            AssistChip(
                onClick = { onSort(next) },
                label = { Text("Цена: ${SORTS.first { it.first == state.sort }.second}") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag("sort"),
            )
        }
        item { TagChip("Всё меню", state.tag.isEmpty()) { onTag("") } }
        items(shownTags, key = { it.id }) { t -> TagChip(t.name, state.tag == t.id) { onTag(t.id) } }
    }
}

/** 1 позиция, 2 позиции, 5 позиций */
fun plural(n: Int, one: String, few: String, many: String): String {
    val d = n % 10
    val t = n % 100
    return if (d == 1 && t != 11) one else if (d in 2..4 && (t < 12 || t > 14)) few else many
}

/**
 * The shop: menu sections as cards, a card opens one section. Staff get into their cabinet with a long press
 * on the pizzeria's name; customers see no staff button.
 */
@Composable
fun MenuScreen(
    state: ShopState,
    onQty: (String, Int) -> Unit,
    onOpenCart: () -> Unit,
    onOpenOrder: () -> Unit,
    onStaff: () -> Unit,
    onRetry: (server: String?) -> Unit,
    server: String? = null,
    onTag: (String) -> Unit = {},
    onSort: (String) -> Unit = {},
    imageUrl: (String) -> String? = { null },
) {
    val menu = state.menu
    var openCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState() // the cards keep their scroll position while a section is open
    BackHandler(enabled = openCategory != null) { openCategory = null }

    Scaffold(
        containerColor = Yeti.Charcoal,
        floatingActionButton = { if (state.cartCount > 0) CartButton(state.cartCount, state.cartTotal, onOpenCart) },
    ) { padding ->
        if (menu == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.menuError == null) CircularProgressIndicator()
                else Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.menuError, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(16.dp))
                    var address by rememberSaveable(server) { mutableStateOf(server.orEmpty()) }
                    if (server != null) {
                        OutlinedTextField(
                            address, { address = it }, label = { Text("Адрес сервера") }, singleLine = true,
                            supportingText = { Text("Его показывает сервер при запуске, например 192.168.1.5:8080") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().testTag("server"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = { onRetry(if (server != null) address else null) }) { Text(if (server != null) "Подключиться" else "Повторить") }
                }
            }
            return@Scaffold
        }
        // Room under the last row for the round cart button.
        val bottom = padding.calculateBottomPadding() + 88.dp
        val category = shownCategories(menu, state.tag).find { it.id == openCategory }
        if (category == null) {
            SectionCards(menu, state, grid, bottom, onOpenOrder, onStaff, onTag) { openCategory = it }
        } else {
            SectionItems(menu, state, category, bottom, onQty, onTag, onSort, imageUrl, onBack = { openCategory = null }) { openCategory = it }
        }
    }
}

@Composable
private fun CartButton(count: Int, total: Int, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = CircleShape,
        containerColor = Yeti.Sign,
        contentColor = Yeti.OnSign,
        modifier = Modifier.testTag("cart-fab").semantics { contentDescription = "Корзина: $count шт. на ${rub(total)}" },
    ) {
        BadgedBox(badge = { Badge(containerColor = Color.White, contentColor = Yeti.Charcoal) { Text("$count", fontWeight = FontWeight.ExtraBold) } }) {
            Icon(Icons.Outlined.ShoppingCart, null)
        }
    }
}

@Composable
private fun SectionCards(
    menu: Menu,
    state: ShopState,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    bottom: Dp,
    onOpenOrder: () -> Unit,
    onStaff: () -> Unit,
    onTag: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val staff by rememberUpdatedState(onStaff)
    // The fire glow from the bottom of the menu screens.
    val glow = Modifier.drawBehind {
        drawRect(Brush.radialGradient(listOf(Yeti.Sign.copy(alpha = .5f), Yeti.Flame.copy(alpha = .22f), Color.Transparent), center = Offset(size.width / 2, size.height * 1.1f), radius = size.width * .85f))
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = grid,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(glow.padding(horizontal = 4.dp)) {
                if (state.hasActiveOrder) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = onOpenOrder) { Text("Мой заказ") }
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
                // The logo: two words, an orange bar behind the second. A long press opens the staff cabinet.
                Column(Modifier.testTag("brand").pointerInput(Unit) { detectTapGestures(onLongPress = { staff() }) }) {
                    Text("Йети", style = MaterialTheme.typography.displayMedium.copy(fontSize = 70.sp, lineHeight = 64.sp))
                    Text(
                        "Пицца",
                        style = MaterialTheme.typography.displayMedium.copy(fontSize = 70.sp, lineHeight = 64.sp),
                        modifier = Modifier.drawBehind {
                            rotate(-2f) { drawRoundRect(Yeti.Sign, topLeft = Offset(-12f, size.height * .46f), size = Size(size.width + 24f, size.height * .3f), cornerRadius = CornerRadius(6f)) }
                        }.padding(horizontal = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(menu.tagline, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp), color = Yeti.Wood)
                Text("Самовывоз · ${menu.info.address}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Готовим ~${menu.info.prepMinutes} минут · оплата ${if (menu.info.transfer) "по СБП или при получении" else "при получении"}",
                    color = Yeti.Muted,
                )
                Footprints(Modifier.align(Alignment.End).padding(top = 4.dp))
                notice(state)?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Yeti.Warn, modifier = Modifier.fillMaxWidth().background(Yeti.Warn.copy(alpha = .12f), MaterialTheme.shapes.medium).padding(12.dp))
                }
                SectionTitle("Меню", Modifier.padding(top = 16.dp, bottom = 6.dp))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { FilterRow(menu, state, onTag, null, edge = 0.dp) }
        gridItems(shownCategories(menu, state.tag), key = { it.id }) { c ->
            val items = visibleItems(menu, state.tag).filter { it.category == c.id }
            SectionCard(c, items.size, items.sumOf { state.cart[it.id] ?: 0 }) { onOpen(c.id) }
        }
    }
}

@Composable
private fun SectionCard(c: Category, count: Int, inCart: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Yeti.Panel,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, if (inCart > 0) Yeti.Sign else Yeti.Line),
        modifier = Modifier.fillMaxWidth().testTag("category-${c.id}"),
    ) {
        Box {
            Column(Modifier.padding(14.dp)) {
                Icon(categoryIcon(c.id), null, tint = Yeti.Sign, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    c.name,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 26.sp, lineHeight = 26.sp, hyphens = Hyphens.Auto, lineBreak = LineBreak.Paragraph,
                    ),
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("$count ${plural(count, "позиция", "позиции", "позиций")}", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (inCart > 0) {
                Badge(Modifier.align(Alignment.TopEnd).padding(10.dp), containerColor = Yeti.Sign, contentColor = Yeti.OnSign) {
                    Text("$inCart", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun SectionItems(
    menu: Menu,
    state: ShopState,
    category: Category,
    bottom: Dp,
    onQty: (String, Int) -> Unit,
    onTag: (String) -> Unit,
    onSort: (String) -> Unit,
    imageUrl: (String) -> String?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val cart = state.cart
    val sections = shownCategories(menu, state.tag)
    val next = sections[(sections.indexOf(category) + 1) % sections.size]
    val list = key(category.id) { rememberLazyListState() } // each section starts at its top
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Все разделы") }
            Icon(categoryIcon(category.id), null, tint = Yeti.Sign, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            SectionTitle(category.name, Modifier.weight(1f))
        }
        FilterRow(menu, state, onTag, onSort)
        val items = visibleItems(menu, state.tag).filter { it.category == category.id }.let { list ->
            when (state.sort) { "asc" -> list.sortedBy { it.price }; "desc" -> list.sortedByDescending { it.price }; else -> list }
        }
        LazyColumn(state = list, contentPadding = PaddingValues(top = 8.dp, bottom = bottom), modifier = Modifier.testTag("menu-list")) {
            items(items, key = { it.id }) { item ->
                ItemCard(item, cart[item.id] ?: 0, menu.tags.filter { it.id in item.tags }.map { it.name }, item.photo.ifBlank { null }?.let(imageUrl)) { onQty(item.id, it) }
            }
            if (next != category) {
                item(key = "next") {
                    OutlinedButton(onClick = { onOpen(next.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Дальше: ${next.name}")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

private fun notice(state: ShopState): String? {
    val info = state.menu?.info ?: return null
    return when {
        !info.acceptingOrders -> "Онлайн-заказы сейчас на паузе — позвоните нам: ${info.phone}"
        !info.orderingOpen || (info.slots.isEmpty() && !info.asapAllowed) -> "Онлайн-заказы принимаем с ${info.open} до ${info.lastOrder}. Ждём вас!"
        else -> null
    }
}

@Composable
private fun ItemCard(item: MenuItem, qty: Int, labels: List<String>, photoUrl: String?, onQty: (Int) -> Unit) {
    Surface(
        color = Yeti.Panel,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(if (qty > 0) 2.dp else 1.dp, if (qty > 0) Yeti.Sign else Yeti.Line),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            if (photoUrl != null) {
                RemoteImage(
                    photoUrl, item.name,
                    Modifier.fillMaxWidth().height(190.dp).drawBehind {
                        drawRect(Brush.radialGradient(listOf(Yeti.Sign.copy(alpha = .35f), Color.Transparent), center = Offset(size.width / 2, size.height * 1.1f), radius = size.width * .7f))
                    }.padding(10.dp),
                )
            }
            Column(Modifier.padding(16.dp)) {
                Text(item.name, style = MaterialTheme.typography.headlineSmall, color = if (item.available) Yeti.Cream else Yeti.Muted)
                if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, color = Yeti.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (labels.isNotEmpty()) {
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Yeti.Muted, modifier = Modifier.border(1.dp, Yeti.LineStrong, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rub(item.price), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                    if (item.unit.isNotBlank()) Text(" · ${item.unit}", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    when {
                        !item.available -> Text("Закончилось", color = Yeti.Muted, style = MaterialTheme.typography.labelLarge)
                        qty > 0 -> Stepper(qty, item.name, onQty)
                        else -> OutlinedButton(
                            onClick = { onQty(1) },
                            border = BorderStroke(1.dp, Yeti.Sign),
                            modifier = Modifier.semantics { contentDescription = "В корзину: ${item.name}" },
                        ) { Text("+ В корзину", color = Yeti.Cream) }
                    }
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
                title = { Text("Ваш заказ", style = MaterialTheme.typography.headlineMedium) },
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
                        Text(if (state.payment == "transfer") "Итого, переведёте после оформления" else "Итого, оплата при получении", color = Yeti.Muted, modifier = Modifier.weight(1f))
                        Text(rub(state.cartTotal), style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onSubmit,
                        enabled = canOrder && cooldownLeft == 0L && !state.submitting && lines.all { it.first.available },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(50),
                    ) { Text(if (state.submitting) "Отправляем…" else if (state.payment == "transfer") "Оформить и перейти к оплате" else "Оформить самовывоз") }
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
            items(state.packLines, key = { "pack-" + it.first.id }) { (item, qty) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${item.name}${if (item.unit.isNotBlank()) " ${item.unit}" else ""} × $qty", style = MaterialTheme.typography.titleSmall, color = Yeti.Muted)
                        Text("для самовывоза, добавлено автоматически", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(rub(item.price * qty), color = Yeti.Muted, fontWeight = FontWeight.ExtraBold)
                }
                HorizontalDivider(color = Yeti.Line)
            }
            if (lines.isEmpty()) return@LazyColumn
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 20.dp)) {
                    Text("Самовывоз · пр. Ленина, 22", color = Yeti.Sign, style = MaterialTheme.typography.headlineSmall)
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
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Yeti.Sign, selectedLabelColor = Yeti.OnSign, selectedLeadingIconColor = Yeti.OnSign),
                                shape = RoundedCornerShape(50),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.comment, onValueChange = { v -> onEdit { it.copy(comment = v.take(300)) } },
                        label = { Text("Комментарий") }, placeholder = { Text("Например: порезать на 8 кусочков") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                    )
                    Text("Оплата", color = Yeti.Sign, style = MaterialTheme.typography.headlineSmall)
                    PayOption("При получении", "наличными или картой в пиццерии", state.payment == "cash") { onEdit { it.copy(payment = "cash") } }
                    if (menu.info.transfer) {
                        PayOption("Переводом по СБП сейчас", "из приложения любого банка по номеру телефона", state.payment == "transfer") { onEdit { it.copy(payment = "transfer") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PayOption(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .border(1.dp, if (selected) Yeti.Sign else Yeti.LineStrong, MaterialTheme.shapes.medium)
            .background(if (selected) Yeti.Sign.copy(alpha = .14f) else Color.Transparent)
            .selectable(selected, onClick = onClick, role = Role.RadioButton).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(hint, color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
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
fun StatusScreen(order: Order?, error: String?, address: String, phone: String, onBack: () -> Unit, onCall: () -> Unit, onPaid: () -> Unit = {}) {
    Scaffold(
        containerColor = Yeti.Charcoal,
        topBar = {
            TopAppBar(
                title = { Text(order?.let { "Заказ № ${it.id}" } ?: "Заказ", style = MaterialTheme.typography.headlineMedium) },
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
                            Modifier.size(28.dp).background(if (i < at) Yeti.Led else if (now) Yeti.Sign else Yeti.Panel2, CircleShape),
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
            if (order.payment == "transfer" && order.status != "cancelled") item { PayBlock(order, onPaid) }
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
                    Text(if (order.payment == "transfer") "Итого переводом" else "Итого при получении", Modifier.weight(1f))
                    Text(rub(order.total), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
                }
                Text("Статус обновляется сам, сразу как кухня его поменяет.", color = Yeti.Muted, style = MaterialTheme.typography.bodySmall)
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

/** How to pay by transfer; a phone can't scan its own screen, so the app shows the number to copy, not the QR. */
@Composable
private fun PayBlock(order: Order, onPaid: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().border(1.dp, Yeti.Sign, MaterialTheme.shapes.large).background(Yeti.Sign.copy(alpha = .12f), MaterialTheme.shapes.large).padding(16.dp).testTag("payblock"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (order.paymentStatus == "paid") {
            Text("Оплата ${rub(order.total)} получена ✓", color = Yeti.Led, fontWeight = FontWeight.ExtraBold)
            return@Column
        }
        Text("Оплата переводом", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Сумма", Modifier.weight(1f))
            Text(rub(order.total), style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp))
        }
        Text("По номеру телефона через СБП", color = Yeti.Muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(phoneText(order.payPhone), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Телефон", order.payPhone))) }; copied = true }) { Text(if (copied) "Скопировано" else "Скопировать") }
        }
        val who = listOf(order.payRecipient, order.payBank.takeIf { it.isNotBlank() }?.let { "($it)" }).filter { !it.isNullOrBlank() }.joinToString(" ")
        if (who.isNotBlank()) Text("Получатель: $who", color = Yeti.Muted)
        Text("В комментарии к переводу укажите «Заказ №${order.id}».", color = Yeti.Muted)
        if (order.paymentStatus == "claimed") Text("Спасибо! Проверяем поступление — статус обновится сам.", color = Yeti.Led, fontWeight = FontWeight.Bold)
        else Button(onClick = onPaid, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) { Text("Я оплатил") }
    }
}
