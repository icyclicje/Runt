package com.mivimoose.core.net

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Graded data for a card from its PriceCharting page: PSA and CGC population per grade
 * (1-10, updated monthly), graded prices (ungraded, 7, 8, 9, 9.5, PSA 10) and monthly
 * price history per grade. Personal use only (PriceCharting's terms); results are cached
 * and requests kept to at most one a second by the caller.
 */
class PriceCharting(private val http: Http) {
    class Graded(
        val url: String,
        val title: String?,
        /** Count per grade, index 0 = grade 1 ... index 9 = grade 10. */
        val psa: IntArray?,
        val cgc: IntArray?,
        /** "Ungraded", "Grade 7", "Grade 8", "Grade 9", "Grade 9.5", "PSA 10" -> USD. */
        val prices: Map<String, Double>,
        /** Same keys -> monthly (epoch millis, USD) points. */
        val history: Map<String, List<Pair<Long, Double>>>,
        val tcgId: Long?,
    ) {
        val psaTotal get() = psa?.sum() ?: 0
        val gemRate: Double? get() = psa?.let { if (it.sum() > 0) it[9].toDouble() / it.sum() else null }
    }

    /** The card page for a search query (PriceCharting redirects straight to a single match),
     *  or the candidate card URLs when it shows a results list. */
    fun search(query: String): Pair<String?, List<String>> {
        val q = URLEncoder.encode(query, "UTF-8")
        val (final, body) = http.getFollow("$BASE/search-products?q=$q&type=prices", HEADERS)
        if (final.contains("/game/")) return final.substringBefore('?') to emptyList()
        val links = Regex("href=\"(/game/pokemon-[^\"?#]+)\"").findAll(body).map { BASE + it.groupValues[1] }.distinct().take(20).toList()
        return null to links
    }

    fun card(url: String): Graded = parse(url, http.get(url, HEADERS))

    fun parse(url: String, h: String): Graded {
        fun ints(name: String, json: JSONObject) = json.optJSONArray(name)?.let { a -> IntArray(a.length()) { a.optInt(it) } }
        val pop = Regex("VGPC\\.pop_data\\s*=\\s*(\\{[^;]*\\})").find(h)?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }
        val prices = LinkedHashMap<String, Double>()
        for ((id, label) in CELLS) {
            val m = Regex("id=\"$id\"[^>]*>\\s*<span class=\"price js-price\">\\s*\\$?([0-9,.]+)").find(h) ?: continue
            m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { prices[label] = it }
        }
        val history = LinkedHashMap<String, List<Pair<Long, Double>>>()
        Regex("VGPC\\.chart_data\\s*=\\s*(\\{.*?\\});").find(h)?.let { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1])
                for ((key, label) in CHART_KEYS) {
                    val a = o.optJSONArray(key) ?: continue
                    history[label] = (0 until a.length()).mapNotNull { i ->
                        val p = a.optJSONArray(i) ?: return@mapNotNull null
                        val cents = p.optDouble(1)
                        if (cents.isNaN() || cents <= 0) null else p.optLong(0) to cents / 100.0
                    }
                }
            }
        }
        val title = Regex("<h1[^>]*id=\"product_name\"[^>]*>\\s*([^<]+)").find(h)?.groupValues?.get(1)?.trim()
        val tcg = Regex("tcgplayer\\.com%2Fproduct%2F(\\d+)").find(h)?.groupValues?.get(1)?.toLongOrNull()
        return Graded(url, title, pop?.let { ints("psa", it) }, pop?.let { ints("cgc", it) }, prices, history, tcg)
    }

    companion object {
        const val BASE = "https://www.pricecharting.com"
        private val HEADERS = mapOf("User-Agent" to "okhttp/5.1.0")
        private val CELLS = listOf("used_price" to "Ungraded", "complete_price" to "Grade 7", "new_price" to "Grade 8",
            "graded_price" to "Grade 9", "box_only_price" to "Grade 9.5", "manual_only_price" to "PSA 10")
        private val CHART_KEYS = listOf("used" to "Ungraded", "cib" to "Grade 7", "new" to "Grade 8", "graded" to "Grade 9",
            "boxonly" to "Grade 9.5", "manualonly" to "PSA 10")

        /** Search words for a card: "pokemon <set> <name> <variant> <number>". Japanese sets get "japanese". */
        fun query(setName: String, lang: String, name: String, number: String, variant: String?): String {
            val v = when (variant) {
                "reverse" -> "reverse holo"; "first" -> "1st edition"; "pokeball" -> "poke ball"; "masterball" -> "master ball"
                else -> ""
            }
            val l = if (lang == "ja") "japanese" else ""
            val n = number.trimStart('0').ifEmpty { number }
            return listOf("pokemon", l, setName, name, v, n).filter { it.isNotBlank() }.joinToString(" ")
                .replace(Regex("[^\\p{L}\\p{N} &'-]"), " ").replace(Regex("\\s+"), " ").trim()
        }
    }
}
