package com.mivimoose.scanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Exchange rates (card prices are in US dollars) and price formatting. */
class Money(ctx: Context, private val net: Net) {
    private val file = File(ctx.filesDir, "rates.json")
    private val _rates = MutableStateFlow(FALLBACK)
    val rates: StateFlow<Map<String, Double>> = _rates
    @Volatile var currency = "USD"

    init {
        runCatching {
            val o = JSONObject(file.readText()).getJSONObject("rates")
            _rates.value = FALLBACK + o.keys().asSequence().filter { it in FALLBACK }.associateWith { o.getDouble(it) }
        }
    }

    fun refresh() {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 12 * 3600_000L) return
        runCatching {
            val o = JSONObject(net.get("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates")
            val got = o.keys().asSequence().filter { it in FALLBACK }.associateWith { o.getDouble(it) }
            if (got.isNotEmpty()) {
                _rates.value = FALLBACK + got
                file.writeText(JSONObject().put("rates", JSONObject(got)).toString())
            }
        }
    }

    fun convert(usd: Double, cur: String = currency) = usd * (_rates.value[cur] ?: 1.0)

    fun fmt(usd: Double?, cur: String = currency, cents: Boolean = true): String {
        if (usd == null || usd.isNaN()) return "—"
        val v = convert(usd, cur)
        val sym = SYMBOLS[cur] ?: "$"
        val noCents = cur == "JPY" || !cents || abs(v) >= 10_000
        val nf = NumberFormat.getNumberInstance(Locale.US).apply {
            maximumFractionDigits = if (noCents) 0 else 2; minimumFractionDigits = if (noCents) 0 else 2
        }
        return (if (v < 0) "-" else "") + sym + nf.format(abs(v))
    }

    /** Short form for charts and chips: $1.2k, $35. */
    fun short(usd: Double, cur: String = currency): String {
        val v = convert(usd, cur)
        val sym = SYMBOLS[cur] ?: "$"
        val a = abs(v)
        val s = when {
            a >= 1_000_000 -> "%.1fM".format(a / 1_000_000)
            a >= 10_000 -> "%.0fk".format(a / 1000)
            a >= 1000 -> "%.1fk".format(a / 1000)
            a >= 100 -> "%.0f".format(a)
            else -> "%.2f".format(a)
        }
        return (if (v < 0) "-" else "") + sym + s
    }

    fun pct(p: Double): String = (if (p >= 0) "+" else "") + "%.1f%%".format(p * 100)

    companion object {
        val SYMBOLS = mapOf("USD" to "$", "CAD" to "CA$", "EUR" to "€", "GBP" to "£", "AUD" to "A$", "JPY" to "¥",
            "MXN" to "MX$", "NZD" to "NZ$", "KRW" to "₩", "TWD" to "NT$", "SGD" to "S$", "PHP" to "₱", "BRL" to "R$", "THB" to "฿", "IDR" to "Rp", "CNY" to "CN¥")
        val FALLBACK = mapOf("USD" to 1.0, "CAD" to 1.38, "EUR" to 0.92, "GBP" to 0.78, "AUD" to 1.52, "JPY" to 150.0, "MXN" to 18.5,
            "NZD" to 1.66, "KRW" to 1380.0, "TWD" to 32.0, "SGD" to 1.34, "PHP" to 57.0, "BRL" to 5.4, "THB" to 35.0, "IDR" to 16000.0, "CNY" to 7.2)
    }
}
