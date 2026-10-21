package ru.vorontsov.kurs

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Все курсы хранятся как «сколько единиц валюты за 1 USD». */
data class Snapshot(
    val market: Map<String, Double>, val cbrRubPerUsd: Double?, val nbrbBynPerUsd: Double?,
    val fetchedAt: Long, val marketDate: String, val cbrDate: String?, val nbrbDate: String?, val source: String,
)

object Rates {
    var snap by mutableStateOf<Snapshot?>(null)
    var loading by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    private val codes get() = ALL_CURRENCIES.map { it.code }.toSet()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "rates.json")

    fun load(ctx: Context) {
        if (snap != null) return
        runCatching { snap = parse(JSONObject(cacheFile(ctx).readText())) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "Kurs/1.0 Android")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun fawaz(date: String): Pair<Map<String, Double>, String> {
        val urls = listOf(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$date/v1/currencies/usd.min.json",
            "https://$date.currency-api.pages.dev/v1/currencies/usd.min.json")
        var err: Throwable? = null
        for (u in urls) {
            try {
                val o = JSONObject(get(u)); val r = o.getJSONObject("usd")
                val m = HashMap<String, Double>()
                for (k in r.keys()) { val K = k.uppercase(); if (K in codes) m[K] = r.getDouble(k) }
                m["USD"] = 1.0
                return m to o.getString("date")
            } catch (e: Throwable) { err = e }
        }
        throw err ?: IllegalStateException()
    }

    private fun ecb(): Pair<Map<String, Double>, String> {
        val o = JSONObject(get("https://api.frankfurter.app/latest?from=USD")); val r = o.getJSONObject("rates")
        val m = r.keys().asSequence().associateWith { r.getDouble(it) } + ("USD" to 1.0)
        return m to o.getString("date")
    }
    private fun erApi(): Pair<Map<String, Double>, String> {
        val o = JSONObject(get("https://open.er-api.com/v6/latest/USD")); val r = o.getJSONObject("rates")
        val m = HashMap<String, Double>(); for (k in r.keys()) if (k in codes) m[k] = r.getDouble(k)
        return m to o.getString("time_last_update_utc")
    }

    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { loading = true; lastError = null }
        try {
            val market = async { runCatching { fawaz("latest") to "fawazahmed0/currency-api" }
                .recoverCatching { ecb() to "ЕЦБ (frankfurter.app)" }
                .recoverCatching { erApi() to "open.er-api.com" }.getOrNull() }
            val cbr = async { runCatching {
                val o = JSONObject(get("https://www.cbr-xml-daily.ru/daily_json.js"))
                val u = o.getJSONObject("Valute").getJSONObject("USD")
                (u.getDouble("Value") / u.getDouble("Nominal")) to o.getString("Date").take(10) }.getOrNull() }
            val nbrb = async { runCatching {
                val o = JSONObject(get("https://api.nbrb.by/exrates/rates/USD?parammode=2"))
                (o.getDouble("Cur_OfficialRate") / o.getDouble("Cur_Scale")) to o.getString("Date").take(10) }.getOrNull() }
            val m = market.await(); val c = cbr.await(); val n = nbrb.await()
            if (m == null && c == null) error("нет связи с источниками курсов")
            val old = snap
            val s = Snapshot(
                market = m?.first?.first ?: old?.market ?: mapOf("USD" to 1.0),
                cbrRubPerUsd = c?.first ?: old?.cbrRubPerUsd, nbrbBynPerUsd = n?.first ?: old?.nbrbBynPerUsd,
                fetchedAt = System.currentTimeMillis(), marketDate = m?.first?.second ?: old?.marketDate ?: "",
                cbrDate = c?.second ?: old?.cbrDate, nbrbDate = n?.second ?: old?.nbrbDate,
                source = m?.second ?: old?.source ?: "")
            cacheFile(ctx).writeText(toJson(s).toString())
            withContext(Dispatchers.Main) { snap = s }
            true
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { lastError = e.message ?: e.toString() }
            false
        } finally { withContext(Dispatchers.Main) { loading = false } }
    }

    /** Итоговые курсы с учётом официальных курсов и своих курсов пользователя. */
    fun effective(st: Store): Map<String, Double> {
        val s = snap ?: return emptyMap()
        val m = HashMap(s.market)
        if (st.officialRub) s.cbrRubPerUsd?.let { m["RUB"] = it }
        if (st.officialByn) s.nbrbBynPerUsd?.let { m["BYN"] = it }
        for (o in st.overrides) { val inR = m[o.inCode] ?: continue; if (o.value > 0) m[o.code] = inR / o.value }
        return m
    }

    /** Сколько единиц [to] за 1 [from]. */
    fun rate(r: Map<String, Double>, from: String, to: String): Double? {
        val a = r[from] ?: return null; val b = r[to] ?: return null
        return b / a
    }

    private fun toJson(s: Snapshot) = JSONObject().put("m", JSONObject(s.market as Map<*, *>)).put("cbr", s.cbrRubPerUsd).put("nbrb", s.nbrbBynPerUsd)
        .put("t", s.fetchedAt).put("md", s.marketDate).put("cd", s.cbrDate).put("nd", s.nbrbDate).put("src", s.source)
    private fun parse(o: JSONObject): Snapshot {
        val mo = o.getJSONObject("m"); val m = HashMap<String, Double>(); for (k in mo.keys()) m[k] = mo.getDouble(k)
        return Snapshot(m, o.optDouble("cbr").takeIf { !it.isNaN() }, o.optDouble("nbrb").takeIf { !it.isNaN() },
            o.getLong("t"), o.optString("md"), o.optString("cd").ifEmpty { null }, o.optString("nd").ifEmpty { null }, o.optString("src"))
    }

    // ---------- История ----------
    private val histCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Double>>()

    suspend fun history(ctx: Context, from: String, to: String, days: Int): List<Pair<LocalDate, Double>> = withContext(Dispatchers.IO) {
        val points = when { days <= 7 -> 8; days <= 31 -> 16; else -> 27 }
        val step = days.toDouble() / (points - 1)
        val today = LocalDate.now()
        val dates = (0 until points).map { today.minusDays(((points - 1 - it) * step).toLong()) }.distinct()
        val dir = File(ctx.cacheDir, "hist").apply { mkdirs() }
        dates.map { d ->
            async {
                val key = if (d == today) "latest" else d.toString()
                val m = histCache[key] ?: runCatching {
                    val f = File(dir, "$key.json")
                    if (key != "latest" && f.exists()) JSONObject(f.readText()).let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }
                    else fawaz(key).first.also { if (key != "latest") f.writeText(JSONObject(it as Map<*, *>).toString()) }
                }.getOrNull()?.also { histCache[key] = it }
                m?.let { r -> rate(r, from, to)?.let { d to it } }
            }
        }.awaitAll().filterNotNull()
    }
}
