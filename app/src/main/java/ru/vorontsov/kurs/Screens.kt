package ru.vorontsov.kurs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, fab: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }) },
        floatingActionButton = fab, content = content)
}

@Composable
private fun Choice(title: String, options: List<String>, selected: Int, onSel: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(options[selected]) }, modifier = Modifier.clickable { open = true })
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, confirmButton = {},
        text = { Column { options.forEachIndexed { i, o ->
            Row(Modifier.fillMaxWidth().clickable { onSel(i); open = false }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(i == selected, { onSel(i); open = false }); Text(o) } } } })
}

@Composable
private fun Switch1(title: String, sub: String?, v: Boolean, on: (Boolean) -> Unit) =
    ListItem(headlineContent = { Text(title) }, supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(v, on) }, modifier = Modifier.clickable { on(!v) })

@Composable
fun SettingsScreen(st: Store, onBack: () -> Unit, onOverrides: () -> Unit) {
    val ctx = LocalContext.current
    Page("Настройки", onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Sect("Оформление")
            Choice("Тема", listOf("Как в системе", "Светлая", "Тёмная"), st.theme) { st.theme = it; st.save() }
            Switch1("Цвета обоев (Material You)", "Android 12+", st.dynamicColor) { st.dynamicColor = it; st.save() }
            Switch1("Строка курса под суммой", "«1 USD = 81,5 RUB»", st.showRateLine) { st.showRateLine = it; st.save() }
            Switch1("Вибрация клавиш", null, st.haptics) { st.haptics = it; st.save() }
            Sect("Числа")
            val decOpts = listOf("Авто") + (0..6).map { "$it знаков" }
            Choice("Знаков после запятой", decOpts, st.decimals + 1) { st.decimals = it - 1; st.save() }
            Switch1("Разделять разряды", "1 000 000 вместо 1000000", st.grouping) { st.grouping = it; st.save() }
            Sect("Комиссия банка / обменника")
            var mk by remember { mutableStateOf(if (st.markup == 0.0) "" else Fmt.num(st.markup, -1, false)) }
            OutlinedTextField(mk, { t -> mk = t; st.markup = (t.replace(',', '.').toDoubleOrNull() ?: 0.0).coerceIn(-50.0, 50.0); st.save() },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Комиссия, %") }, singleLine = true,
                supportingText = { Text("Вычитается из результата конвертации. 0 — чистый курс") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0, 1.0, 2.0, 3.0, 5.0).forEach { v -> AssistChip({ st.markup = v; mk = if (v == 0.0) "" else Fmt.num(v, -1, false); st.save() }, { Text(if (v == 0.0) "нет" else "${v.toInt()}%") }) }
            }
            Sect("Курсы")
            Switch1("Официальный курс ЦБ РФ для рубля", "Иначе рыночный (биржевой) курс", st.officialRub) { st.officialRub = it; st.save() }
            Switch1("Официальный курс НБРБ для бел. рубля", "Иначе рыночный курс", st.officialByn) { st.officialByn = it; st.save() }
            ListItem(headlineContent = { Text("Свои курсы") }, supportingContent = { Text(if (st.overrides.isEmpty()) "Нет — используются загруженные" else "Задано: ${st.overrides.joinToString { it.code }}") },
                trailingContent = { Icon(Icons.Filled.ChevronRight, null) }, modifier = Modifier.clickable(onClick = onOverrides))
            val hrs = listOf(0, 1, 3, 6, 12, 24)
            Choice("Фоновое обновление", hrs.map { if (it == 0) "Выключено" else "Каждые $it ч" }, hrs.indexOf(st.bgHours).coerceAtLeast(0)) {
                st.bgHours = hrs[it]; st.save(); RatesWorker.schedule(ctx) }
            Sect("Источники")
            val s = Rates.snap
            Text(buildString {
                append("• ЦБ РФ (cbr-xml-daily.ru) — официальный курс рубля\n")
                append("• НБРБ (api.nbrb.by) — официальный курс белорусского рубля\n")
                append("• currency-api (fawazahmed0) — 200+ валют, крипта, металлы, история\n")
                append("• open.er-api.com — запасной источник\n")
                if (s != null) append("\nРыночные курсы на ${s.marketDate} (${s.source})")
            }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            Text("Курсы 1.0 · ключи API не нужны · работает офлайн", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun Sect(t: String) = Text(t, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun CodeField(label: String, v: String, on: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(v, { on(it.uppercase().filter { c -> c.isLetter() }.take(5)) }, modifier, label = { Text(label) }, singleLine = true,
        supportingText = { Text(CUR_MAP[v]?.let { "${it.flag} ${it.name}" } ?: "неизвестный код") }, isError = v !in CUR_MAP)

@Composable
fun OverrideDialog(st: Store, code: String, defIn: String, onDone: () -> Unit) {
    val ex = st.overrides.firstOrNull { it.code == code }
    var inCode by remember { mutableStateOf(ex?.inCode ?: defIn) }
    val cur = Rates.rate(Rates.effective(st), code, inCode)
    var v by remember { mutableStateOf(ex?.value?.let { Fmt.num(it, -1, false) } ?: cur?.let { Fmt.num(it, 4, false) } ?: "") }
    AlertDialog(onDismissRequest = onDone, title = { Text("Свой курс ${cur(code).flag} $code") },
        text = { Column {
            Text("Например, курс вашего банка или обменника. Будет использоваться вместо загруженного.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("1 $code =") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            CodeField("в валюте", inCode, { inCode = it })
        } },
        confirmButton = { TextButton({
            val x = v.replace(',', '.').replace(" ", "").toDoubleOrNull()
            if (x != null && x > 0 && inCode in CUR_MAP && inCode != code) { st.overrides = st.overrides.filter { it.code != code } + Override(code, x, inCode); st.save() }
            onDone() }) { Text("Сохранить") } },
        dismissButton = { Row {
            if (ex != null) TextButton({ st.overrides = st.overrides.filter { it.code != code }; st.save(); onDone() }) { Text("Сбросить") }
            TextButton(onDone) { Text("Отмена") } } })
}

@Composable
fun OverridesScreen(st: Store, onBack: () -> Unit) {
    var edit by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf(false) }
    var newCode by remember { mutableStateOf("USD") }
    Page("Свои курсы", onBack, fab = { FloatingActionButton({ add = true }) { Icon(Icons.Filled.Add, "Добавить") } }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (st.overrides.isEmpty()) item { Text("Пока нет своих курсов. Задать курс можно здесь или в меню ⋮ у валюты.", Modifier.padding(16.dp)) }
            items(st.overrides, key = { it.code }) { o ->
                ListItem(headlineContent = { Text("${cur(o.code).flag} 1 ${o.code} = ${Fmt.num(o.value, -1, true)} ${o.inCode}") },
                    trailingContent = { IconButton({ st.overrides = st.overrides - o; st.save() }) { Icon(Icons.Filled.Delete, "Удалить") } },
                    modifier = Modifier.clickable { edit = o.code })
            }
        }
    }
    if (add) AlertDialog(onDismissRequest = { add = false }, title = { Text("Какая валюта?") }, text = { CodeField("Код валюты", newCode, { newCode = it }) },
        confirmButton = { TextButton({ if (newCode in CUR_MAP) { add = false; edit = newCode } }) { Text("Далее") } })
    edit?.let { OverrideDialog(st, it, if (it == "RUB") "USD" else "RUB") { edit = null } }
}

@Composable
fun AlertsScreen(st: Store, onBack: () -> Unit) {
    var add by remember { mutableStateOf(false) }
    val r = Rates.effective(st)
    Page("Уведомления о курсе", onBack, fab = { FloatingActionButton({ add = true }) { Icon(Icons.Filled.AddAlert, "Добавить") } }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { Text("Приложение проверяет курсы при фоновом обновлении (настраивается в Настройках) и пришлёт уведомление, когда курс пересечёт порог.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            items(st.alerts, key = { it.id }) { a ->
                val now = Rates.rate(r, a.from, a.to)
                ListItem(headlineContent = { Text("1 ${a.from} ${if (a.above) "≥" else "≤"} ${Fmt.num(a.value, -1, true)} ${a.to}") },
                    supportingContent = { Text("Сейчас: ${now?.let { Fmt.num(it, -1, true) } ?: "—"}" + if (a.fired) " · сработало" else "") },
                    leadingContent = { Icon(if (a.above) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown, null) },
                    trailingContent = { IconButton({ st.alerts = st.alerts - a; st.save() }) { Icon(Icons.Filled.Delete, "Удалить") } })
            }
        }
    }
    if (add) {
        val q = st.queue
        var from by remember { mutableStateOf(q.getOrElse(1) { "USD" }) }
        var to by remember { mutableStateOf(q.getOrElse(0) { "RUB" }) }
        var above by remember { mutableStateOf(true) }
        val cur = Rates.rate(r, from, to)
        var v by remember(from, to) { mutableStateOf(cur?.let { Fmt.num(it, 2, false) } ?: "") }
        AlertDialog(onDismissRequest = { add = false }, title = { Text("Новое уведомление") },
            text = { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CodeField("Валюта", from, { from = it }, Modifier.weight(1f)); CodeField("в", to, { to = it }, Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(above, { above = true }, { Text("выше") }); FilterChip(!above, { above = false }, { Text("ниже") }) }
                OutlinedTextField(v, { v = it }, singleLine = true, label = { Text("Порог") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Сейчас: ${cur?.let { Fmt.num(it, -1, true) } ?: "—"}") })
            } },
            confirmButton = { TextButton({
                val x = v.replace(',', '.').replace(" ", "").toDoubleOrNull()
                if (x != null && from in CUR_MAP && to in CUR_MAP && from != to) {
                    st.alerts = st.alerts + Alert(System.currentTimeMillis(), from, to, above, x, fired = cur?.let { if (above) it >= x else it <= x } ?: false); st.save(); add = false }
            }) { Text("Добавить") } },
            dismissButton = { TextButton({ add = false }) { Text("Отмена") } })
    }
}
