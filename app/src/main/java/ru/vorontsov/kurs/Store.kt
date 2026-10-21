package ru.vorontsov.kurs

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

data class Profile(val name: String, val codes: List<String>)
data class Alert(val id: Long, val from: String, val to: String, val above: Boolean, val value: Double, val fired: Boolean = false)
/** Свой курс: 1 [code] = [value] [inCode] */
data class Override(val code: String, val value: Double, val inCode: String)

const val MAX_ROWS = 10

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kurs", Context.MODE_PRIVATE)

    var profiles by mutableStateOf(loadProfiles())
    var active by mutableIntStateOf(sp.getInt("active", 0).coerceIn(0, profiles.lastIndex))
    var favorites by mutableStateOf(sp.getStringSet("fav", setOf("RUB", "USD", "EUR", "BYN"))!!.toSet())
    var recent by mutableStateOf(sp.getString("recent", "")!!.split(',').filter { it.isNotEmpty() })
    var theme by mutableIntStateOf(sp.getInt("theme", 0))          // 0 системная, 1 светлая, 2 тёмная
    var dynamicColor by mutableStateOf(sp.getBoolean("dyn", true))
    var decimals by mutableIntStateOf(sp.getInt("dec", -1))       // -1 = авто
    var grouping by mutableStateOf(sp.getBoolean("grp", true))
    var markup by mutableDoubleStateOf(sp.getFloat("markup", 0f).toDouble())
    var officialRub by mutableStateOf(sp.getBoolean("cbr", true))
    var officialByn by mutableStateOf(sp.getBoolean("nbrb", true))
    var bgHours by mutableIntStateOf(sp.getInt("bgh", 3))          // 0 = выкл
    var haptics by mutableStateOf(sp.getBoolean("hapt", true))
    var showRateLine by mutableStateOf(sp.getBoolean("rline", true))
    var overrides by mutableStateOf(loadOverrides())
    var alerts by mutableStateOf(loadAlerts())

    val queue: List<String> get() = profiles[active].codes

    fun setQueue(codes: List<String>) {
        profiles = profiles.toMutableList().also { it[active] = it[active].copy(codes = codes.distinct().take(MAX_ROWS)) }
        save()
    }

    private fun loadProfiles(): List<Profile> {
        val s = sp.getString("profiles", null) ?: return listOf(
            Profile("Основная", listOf("RUB", "USD", "EUR", "BYN")),
            Profile("Путешествие", listOf("RUB", "USD", "TRY", "AED", "THB")),
            Profile("Крипто", listOf("USD", "RUB", "BTC", "ETH", "USDT")))
        val a = JSONArray(s)
        return (0 until a.length()).map { val o = a.getJSONObject(it); val c = o.getJSONArray("c")
            Profile(o.getString("n"), (0 until c.length()).map { i -> c.getString(i) }) }.ifEmpty { listOf(Profile("Основная", listOf("RUB","USD","EUR","BYN"))) }
    }
    private fun loadOverrides(): List<Override> {
        val a = JSONArray(sp.getString("ovr", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Override(o.getString("c"), o.getDouble("v"), o.getString("i")) }
    }
    private fun loadAlerts(): List<Alert> {
        val a = JSONArray(sp.getString("alerts", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it)
            Alert(o.getLong("id"), o.getString("f"), o.getString("t"), o.getBoolean("a"), o.getDouble("v"), o.optBoolean("x")) }
    }

    fun save() {
        val pa = JSONArray(); profiles.forEach { p -> pa.put(JSONObject().put("n", p.name).put("c", JSONArray(p.codes))) }
        val oa = JSONArray(); overrides.forEach { oa.put(JSONObject().put("c", it.code).put("v", it.value).put("i", it.inCode)) }
        val aa = JSONArray(); alerts.forEach { aa.put(JSONObject().put("id", it.id).put("f", it.from).put("t", it.to).put("a", it.above).put("v", it.value).put("x", it.fired)) }
        sp.edit().putString("profiles", pa.toString()).putInt("active", active).putStringSet("fav", favorites)
            .putString("recent", recent.joinToString(",")).putInt("theme", theme).putBoolean("dyn", dynamicColor)
            .putInt("dec", decimals).putBoolean("grp", grouping).putFloat("markup", markup.toFloat())
            .putBoolean("cbr", officialRub).putBoolean("nbrb", officialByn).putInt("bgh", bgHours)
            .putBoolean("hapt", haptics).putBoolean("rline", showRateLine)
            .putString("ovr", oa.toString()).putString("alerts", aa.toString()).apply()
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context) = inst ?: synchronized(this) { inst ?: Store(ctx.applicationContext).also { inst = it } }
    }
}
