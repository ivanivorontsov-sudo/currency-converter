package ru.vorontsov.kurs

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferMaxRefreshRate()
        Rates.load(this)
        RatesWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { val st = remember { Store.get(this) }; KursTheme(st) { App(st) } }
    }

    /** realme 15 и др.: просим максимальную частоту экрана (120/144 Гц). */
    @Suppress("DEPRECATION")
    private fun preferMaxRefreshRate() {
        val d = (if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
        val cur = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }
}

@Composable
fun KursTheme(st: Store, content: @Composable () -> Unit) {
    val dark = when (st.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val scheme = when {
        st.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9FB4FF), secondary = Color(0xFFFFD54F))
        else -> lightColorScheme(primary = Color(0xFF2E4BB5), secondary = Color(0xFF8A6D00))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

enum class Scr { Main, Settings, Alerts, Overrides }

@Composable
fun App(st: Store) {
    var scr by remember { mutableStateOf(Scr.Main) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val s = Rates.snap
        if (s == null || System.currentTimeMillis() - s.fetchedAt > 30 * 60_000) { Rates.refresh(ctx); checkAlerts(ctx); RatesWidget.updateAll(ctx) }
    }
    BackHandler(scr != Scr.Main) { scr = if (scr == Scr.Overrides) Scr.Settings else Scr.Main }
    AnimatedContent(scr, label = "scr", transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
        when (s) {
            Scr.Main -> MainScreen(st, onSettings = { scr = Scr.Settings }, onAlerts = { scr = Scr.Alerts },
                onRefresh = { scope.launch { Rates.refresh(ctx); checkAlerts(ctx); RatesWidget.updateAll(ctx) } })
            Scr.Settings -> SettingsScreen(st, onBack = { scr = Scr.Main }, onOverrides = { scr = Scr.Overrides })
            Scr.Alerts -> AlertsScreen(st, onBack = { scr = Scr.Main })
            Scr.Overrides -> OverridesScreen(st, onBack = { scr = Scr.Settings })
        }
    }
}

// ======================= Главный экран =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(st: Store, onSettings: () -> Unit, onAlerts: () -> Unit, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val q = st.queue
    var input by rememberSaveable { mutableStateOf(q.firstOrNull() ?: "RUB") }
    if (input !in q && q.isNotEmpty()) input = q.first()
    var expr by rememberSaveable { mutableStateOf("100") }
    var picker by remember { mutableStateOf<String?>(null) }   // "" = добавить, код = заменить
    var chart by remember { mutableStateOf<String?>(null) }
    var ovrFor by remember { mutableStateOf<String?>(null) }
    var profileMenu by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf<Int?>(null) }  // -1 = новый
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val rates = Rates.effective(st)
    val amount = Calc.eval(expr)

    fun valueOf(c: String): Double? {
        if (amount == null) return null
        if (c == input) return amount
        val r = Rates.rate(rates, input, c) ?: return null
        return amount * r * (1 - st.markup / 100)
    }
    fun fmt(v: Double?) = v?.let { Fmt.num(it, st.decimals, st.grouping) } ?: "—"
    fun shareText() = buildString {
        q.forEach { c -> append("${cur(c).flag} ${fmt(valueOf(c))} $c\n") }
        Rates.snap?.let { append("Курсы на ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(it.fetchedAt))}") }
        if (st.markup != 0.0) append(", комиссия ${Fmt.num(st.markup, -1, false)}%")
    }

    val listState = rememberLazyListState()
    val reo = rememberReorderableLazyListState(listState) { from, to ->
        if (from.index < q.size && to.index < q.size) {
            st.setQueue(q.toMutableList().apply { add(to.index, removeAt(from.index)) })
            if (st.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { profileMenu = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(st.profiles[st.active].name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(profileMenu, { profileMenu = false }) {
                            st.profiles.forEachIndexed { i, p ->
                                DropdownMenuItem(text = { Text("${p.name}  (${p.codes.size})") }, onClick = { st.active = i; st.save(); profileMenu = false; RatesWidget.updateAll(ctx) },
                                    leadingIcon = { if (i == st.active) Icon(Icons.Filled.Check, null) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Новый список") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { profileMenu = false; profileDialog = -1 })
                            DropdownMenuItem(text = { Text("Переименовать") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { profileMenu = false; profileDialog = st.active })
                            if (st.profiles.size > 1) DropdownMenuItem(text = { Text("Удалить список") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                                profileMenu = false; st.profiles = st.profiles.filterIndexed { i, _ -> i != st.active }; st.active = 0; st.save() })
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (q.size >= 2) { st.setQueue(listOf(q[1], q[0]) + q.drop(2)); input = q[1] } }) { Icon(Icons.Filled.SwapVert, "Поменять местами") }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Обновить") }
                    IconButton(onClick = onAlerts) { Icon(Icons.Outlined.NotificationsActive, "Уведомления") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (Rates.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            StatusLine(st)
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(q, key = { it }) { c ->
                    ReorderableItem(reo, key = c) { dragging ->
                        val elev by animateDpAsStateCompat(if (dragging) 8.dp else 0.dp)
                        CurrencyRow(st, c, selected = c == input, dragging = dragging, elevation = elev,
                            value = if (c == input) expr else fmt(valueOf(c)),
                            sub = if (c == input && Calc.hasOp(expr)) "= " + fmt(amount) else
                                if (st.showRateLine && c != input) Rates.rate(rates, c, input)?.let { "1 $c = ${Fmt.num(it, -1, true)} $input" } else null,
                            override = st.overrides.any { it.code == c },
                            handle = Modifier.draggableHandle(),
                            onClick = {
                                if (c != input) { val v = valueOf(c); input = c; expr = v?.let { Fmt.num(it, maxOf(st.decimals, 2).coerceAtMost(8), false).replace("\u00A0", "") } ?: "0" }
                            },
                            onCopy = { clip.setText(AnnotatedString(fmt(valueOf(c)).replace("\u00A0", ""))); scope.launch { snack.showSnackbar("Скопировано: ${fmt(valueOf(c))} $c") } },
                            onChart = { chart = c },
                            onTop = { st.setQueue(listOf(c) + q.filter { it != c }) },
                            onReplace = { picker = c },
                            onOverride = { ovrFor = c },
                            onRemove = { if (q.size > 1) st.setQueue(q - c) },
                        )
                    }
                }
                item(key = "__add") {
                    OutlinedButton(onClick = { picker = "" }, enabled = q.size < MAX_ROWS, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить валюту  ${q.size}/$MAX_ROWS")
                    }
                }
            }
            if (st.markup != 0.0) Text("С учётом комиссии банка ${Fmt.num(st.markup, -1, false)}%", Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Keypad(st, onKey = { k ->
                if (st.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expr = applyKey(expr, k)
            }, onShare = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText()), "Поделиться"))
            }, onCopyAll = { clip.setText(AnnotatedString(shareText())); scope.launch { snack.showSnackbar("Все значения скопированы") } })
        }
    }

    picker?.let { rep ->
        CurrencyPicker(st, exclude = q.toSet() - rep, onDismiss = { picker = null }) { code ->
            if (rep.isEmpty()) st.setQueue(q + code) else st.setQueue(q.map { if (it == rep) code else it })
            if (rep == input) input = code
            st.recent = (listOf(code) + st.recent.filter { it != code }).take(8); st.save()
            picker = null
        }
    }
    chart?.let { c -> ChartSheet(c, if (c == input) (q.firstOrNull { it != c } ?: "USD") else input) { chart = null } }
    ovrFor?.let { c -> OverrideDialog(st, c, q.firstOrNull { it != c } ?: "RUB") { ovrFor = null } }
    profileDialog?.let { idx ->
        var name by remember { mutableStateOf(if (idx >= 0) st.profiles[idx].name else "Список ${st.profiles.size + 1}") }
        AlertDialog(onDismissRequest = { profileDialog = null }, title = { Text(if (idx >= 0) "Переименовать" else "Новый список валют") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Название") }) },
            confirmButton = { TextButton(onClick = {
                if (name.isNotBlank()) {
                    if (idx >= 0) st.profiles = st.profiles.toMutableList().also { it[idx] = it[idx].copy(name = name.trim()) }
                    else { st.profiles = st.profiles + Profile(name.trim(), listOf("RUB", "USD", "EUR")); st.active = st.profiles.lastIndex }
                    st.save()
                }
                profileDialog = null }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { profileDialog = null }) { Text("Отмена") } })
    }
}

@Composable
private fun animateDpAsStateCompat(target: androidx.compose.ui.unit.Dp) = androidx.compose.animation.core.animateDpAsState(target, label = "e")

fun applyKey(e: String, k: String): String {
    val ops = "+−×÷"
    return when (k) {
        "C" -> "0"
        "⌫" -> e.dropLast(1).ifEmpty { "0" }
        "=" -> Calc.eval(e)?.let { Fmt.num(it, 8, false).trimEnd('0').trimEnd(',') } ?: e
        in ops -> if (e.isNotEmpty() && e.last() in ops) e.dropLast(1) + k else e + k
        "," -> { val last = e.takeLastWhile { it !in ops }; if (',' in last) e else if (last.isEmpty()) e + "0," else e + "," }
        else -> if (e == "0") k.trimStart('0').ifEmpty { "0" } else if (e.length > 40) e else e + k
    }
}

@Composable
fun StatusLine(st: Store) {
    val s = Rates.snap
    val df = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
    val txt = when {
        s == null && Rates.loading -> "Загружаю курсы…"
        s == null -> "Нет курсов. Проверьте интернет и нажмите «Обновить»"
        else -> buildString {
            append("Обновлено ${df.format(Date(s.fetchedAt))}")
            if (st.officialRub && s.cbrDate != null) append(" · ЦБ РФ на ${s.cbrDate.substring(8, 10)}.${s.cbrDate.substring(5, 7)}")
            if (st.officialByn && s.nbrbDate != null) append(" · НБРБ на ${s.nbrbDate.substring(8, 10)}.${s.nbrbDate.substring(5, 7)}")
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(txt, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Rates.lastError?.let { Text("Офлайн: показаны сохранённые курсы ($it)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CurrencyRow(
    st: Store, c: String, selected: Boolean, dragging: Boolean, elevation: androidx.compose.ui.unit.Dp, value: String, sub: String?, override: Boolean,
    handle: Modifier, onClick: () -> Unit, onCopy: () -> Unit, onChart: () -> Unit, onTop: () -> Unit,
    onReplace: () -> Unit, onOverride: () -> Unit, onRemove: () -> Unit,
) {
    val cu = cur(c)
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(shape = RoundedCornerShape(18.dp), color = bg, tonalElevation = elevation, shadowElevation = elevation,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onCopy)) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onChart), contentAlignment = Alignment.Center) {
                Text(cu.flag, fontSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.width(92.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (override) Icon(Icons.Filled.Tune, "свой курс", Modifier.size(14.dp).padding(start = 3.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                Text(cu.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(value, fontSize = if (value.length > 14) 18.sp else 23.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }, Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "Меню") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("График курса") }, leadingIcon = { Icon(Icons.Filled.ShowChart, null) }, onClick = { menu = false; onChart() })
                    DropdownMenuItem(text = { Text("Скопировать") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onCopy() })
                    DropdownMenuItem(text = { Text("Сделать первой") }, leadingIcon = { Icon(Icons.Filled.VerticalAlignTop, null) }, onClick = { menu = false; onTop() })
                    DropdownMenuItem(text = { Text("Заменить валюту") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { menu = false; onReplace() })
                    DropdownMenuItem(text = { Text("Свой курс") }, leadingIcon = { Icon(Icons.Filled.Tune, null) }, onClick = { menu = false; onOverride() })
                    DropdownMenuItem(text = { Text(if (c in st.favorites) "Убрать из избранного" else "В избранное") },
                        leadingIcon = { Icon(if (c in st.favorites) Icons.Filled.Star else Icons.Filled.StarBorder, null) },
                        onClick = { menu = false; st.favorites = if (c in st.favorites) st.favorites - c else st.favorites + c; st.save() })
                    DropdownMenuItem(text = { Text("Удалить из списка") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onRemove() })
                }
            }
            Icon(Icons.Filled.DragIndicator, "Перетащить", handle.size(36.dp).padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Keypad(st: Store, onKey: (String) -> Unit, onShare: () -> Unit, onCopyAll: () -> Unit) {
    val rows = listOf(
        listOf("C", "⌫", "÷", "×"),
        listOf("7", "8", "9", "−"),
        listOf("4", "5", "6", "+"),
        listOf("1", "2", "3", "="),
        listOf("share", "0", ",", "copy"),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.forEach { k ->
                        val op = k in listOf("C", "⌫", "÷", "×", "−", "+", "=")
                        val colors = when {
                            k == "=" -> ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                            op -> ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            else -> ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                        }
                        FilledTonalButton(onClick = { when (k) { "share" -> onShare(); "copy" -> onCopyAll(); else -> onKey(k) } },
                            modifier = Modifier.weight(1f).height(54.dp), colors = colors, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(0.dp)) {
                            when (k) {
                                "share" -> Icon(Icons.Filled.Share, "Поделиться")
                                "copy" -> Icon(Icons.Filled.ContentCopy, "Копировать всё")
                                "⌫" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Стереть")
                                else -> Text(k, fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================= Выбор валюты =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CurrencyPicker(st: Store, exclude: Set<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var cryptoOnly by remember { mutableStateOf(false) }
    val rates = Rates.effective(st)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Поиск: доллар, USD, Турция…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, null) } })
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!cryptoOnly, { cryptoOnly = false }, { Text("Все") })
                FilterChip(cryptoOnly, { cryptoOnly = true }, { Text("Крипто и металлы") })
            }
        }
        val ql = query.trim().lowercase()
        val list = ALL_CURRENCIES.filter { it.code !in exclude && (it.code in rates || rates.isEmpty()) }
            .filter { !cryptoOnly || it.crypto || it.code.startsWith("XA") }
            .filter { ql.isEmpty() || it.code.lowercase().contains(ql) || it.name.lowercase().contains(ql) || countryHint(it.code).contains(ql) }
        val fav = list.filter { it.code in st.favorites }
        val rec = list.filter { it.code in st.recent && it.code !in st.favorites }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            if (fav.isNotEmpty()) { item { Header("Избранное") }; items(fav, key = { "f" + it.code }) { PickRow(st, it, onPick) } }
            if (rec.isNotEmpty() && ql.isEmpty()) { item { Header("Недавние") }; items(rec, key = { "r" + it.code }) { PickRow(st, it, onPick) } }
            item { Header("Все валюты") }
            items(list, key = { it.code }) { PickRow(st, it, onPick) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun countryHint(code: String) = when (code) {
    "USD" -> "сша америка"; "EUR" -> "европа германия франция италия испания"; "TRY" -> "турция"; "AED" -> "оаэ эмираты дубай"
    "THB" -> "таиланд"; "GEL" -> "грузия"; "AMD" -> "армения"; "KZT" -> "казахстан"; "CNY" -> "китай"; "BYN" -> "беларусь белоруссия"
    "GBP" -> "британия англия"; "EGP" -> "египет"; "VND" -> "вьетнам"; "IDR" -> "бали индонезия"; "UZS" -> "узбекистан"; "KGS" -> "киргизия кыргызстан"
    else -> ""
}

@Composable private fun Header(t: String) = Text(t, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun PickRow(st: Store, c: Cur, onPick: (String) -> Unit) {
    ListItem(modifier = Modifier.clickable { onPick(c.code) },
        leadingContent = { Text(c.flag, fontSize = 26.sp) },
        headlineContent = { Text(c.code, fontWeight = FontWeight.Bold) }, supportingContent = { Text(c.name) },
        trailingContent = {
            IconButton({ st.favorites = if (c.code in st.favorites) st.favorites - c.code else st.favorites + c.code; st.save() }) {
                Icon(if (c.code in st.favorites) Icons.Filled.Star else Icons.Filled.StarBorder, "Избранное",
                    tint = if (c.code in st.favorites) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}

// ======================= График =======================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChartSheet(code: String, other: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var from by remember { mutableStateOf(code) }
    var to by remember { mutableStateOf(other) }
    var days by remember { mutableIntStateOf(30) }
    var data by remember { mutableStateOf<List<Pair<java.time.LocalDate, Double>>?>(null) }
    var sel by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(from, to, days) { data = null; sel = null; data = Rates.history(ctx, from, to, days) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${cur(from).flag} $from → ${cur(to).flag} $to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton({ val t = from; from = to; to = t }) { Icon(Icons.Filled.SwapHoriz, "Развернуть") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7 дней", 30 to "30 дней", 90 to "3 мес", 365 to "1 год").forEach { (d, l) -> FilterChip(days == d, { days = d }, { Text(l) }) }
            }
            Spacer(Modifier.height(12.dp))
            val d = data
            when {
                d == null -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                d.size < 2 -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { Text("Нет данных истории (нужен интернет)") }
                else -> {
                    val first = d.first().second; val last = d.last().second
                    val ch = (last - first) / first * 100
                    val shown = sel?.let { d[it] } ?: d.last()
                    Text("1 $from = ${Fmt.num(shown.second, -1, true)} $to", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${shown.first.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru")))}   ·   за период ${if (ch >= 0) "+" else ""}${Fmt.num(ch, 2, false)}%",
                        color = if (ch >= 0) Color(0xFF2E9E5B) else Color(0xFFD9443A))
                    Spacer(Modifier.height(8.dp))
                    LineChart(d.map { it.second }, sel, { sel = it }, Modifier.fillMaxWidth().height(220.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("мин ${Fmt.num(d.minOf { it.second }, -1, true)}", style = MaterialTheme.typography.labelMedium)
                        Text("макс ${Fmt.num(d.maxOf { it.second }, -1, true)}", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("Рыночный курс по данным currency-api (fawazahmed0); может немного отличаться от официального ЦБ/НБРБ.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun LineChart(v: List<Double>, sel: Int?, onSel: (Int?) -> Unit, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val mn = v.min(); val mx = v.max(); val span = (mx - mn).takeIf { it > 0 } ?: 1.0
    Canvas(modifier.pointerInputCompat(v.size, onSel)) {
        val w = size.width; val h = size.height
        fun pt(i: Int) = Offset(w * i / (v.size - 1), (h * (1 - (v[i] - mn) / span) * 0.9 + h * 0.05).toFloat())
        for (g in 0..3) drawLine(grid, Offset(0f, h * g / 3), Offset(w, h * g / 3), 1f)
        val p = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until v.size) lineTo(pt(i).x, pt(i).y) }
        val fill = Path().apply { addPath(p); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(p, line, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        sel?.let { val o = pt(it); drawLine(grid, Offset(o.x, 0f), Offset(o.x, h), 2f); drawCircle(line, 10f, o) }
    }
}

private fun Modifier.pointerInputCompat(n: Int, onSel: (Int?) -> Unit) = this.then(
    Modifier.pointerInput(n) {
        detectDragGestures(onDragEnd = {}, onDragStart = { o -> onSel(((o.x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1)) }) { ch, _ ->
            onSel(((ch.position.x / size.width) * (n - 1)).toInt().coerceIn(0, n - 1))
        }
    }.pointerInput("t$n") { detectTapGestures { o -> onSel(((o.x / size.width) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)) } })

