package com.mivimoose.core.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * TCGplayer data:
 *  - tcgcsv.com: TCGplayer's whole catalog and market prices, refreshed daily (one file
 *    per set), for prices across a collection and for sets/cards the site doesn't have yet.
 *  - TCGplayer's own product endpoints (what tcgplayer.com's product pages load): the
 *    current market price per printing, the latest sales, the cheapest listings, and sales
 *    history for market graphs.
 */
class TcgPlayer(private val http: Http) {
    class Group(val id: Long, val name: String, val abbreviation: String, val supplemental: Boolean, val published: String, val category: Int)
    class Product(
        val id: Long, val name: String, val cleanName: String, val image: String, val groupId: Long,
        val number: String?, val rarity: String?, val cardType: String?, val hp: String?, val stage: String?,
    ) {
        val isCard get() = number != null
    }
    class Price(val productId: Long, val subType: String, val market: Double?, val low: Double?, val mid: Double?, val high: Double?)
    class PricePoint(val printing: String, val market: Double?, val listedMedian: Double?)
    /** One sale: day (yyyy-mm-dd) and exact time, variant ("Normal", "Holofoil", "Reverse Holofoil"...), condition. */
    class Sale(val day: String, val variant: String, val condition: String, val price: Double, val shipping: Double, val quantity: Int, val time: String = day)
    class Bucket(val date: String, val market: Double, val sold: Int, val low: Double?, val high: Double?)
    class Series(val variant: String, val condition: String, val language: String, val buckets: List<Bucket>,
                 val avgDailySold: Double, val totalSold: Int)

    fun groups(category: Int): List<Group> {
        val a = JSONObject(http.get("$CSV/$category/groups")).getJSONArray("results")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Group(it.getLong("groupId"), it.getString("name"), it.optString("abbreviation", ""),
                it.optBoolean("isSupplemental"), it.optString("publishedOn", "").take(10), category)
        }
    }

    fun products(category: Int, groupId: Long): List<Product> {
        val a = JSONObject(http.get("$CSV/$category/$groupId/products")).getJSONArray("results")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            val ext = HashMap<String, String>()
            p.optJSONArray("extendedData")?.let { e -> for (i in 0 until e.length()) e.getJSONObject(i).let { ext[it.getString("name")] = it.optString("value") } }
            Product(p.getLong("productId"), p.getString("name"), p.optString("cleanName"), p.optString("imageUrl"), groupId,
                ext["Number"], ext["Rarity"], ext["CardType"], ext["HP"], ext["Stage"])
        }
    }

    fun prices(category: Int, groupId: Long): List<Price> {
        val a = JSONObject(http.get("$CSV/$category/$groupId/prices")).getJSONArray("results")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Price(it.getLong("productId"), it.optString("subTypeName", "Normal"), it.dbl("marketPrice"), it.dbl("lowPrice"),
                it.dbl("midPrice"), it.dbl("highPrice"))
        }
    }

    fun lastUpdated(): String = http.get("https://tcgcsv.com/last-updated.txt").trim()

    /** Current market price per printing ("Normal", "Foil"), straight from TCGplayer. */
    fun pricePoints(productId: Long): List<PricePoint> {
        val a = JSONArray(http.get("https://mpapi.tcgplayer.com/v2/product/$productId/pricepoints", BROWSER))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            PricePoint(it.optString("printingType"), it.dbl("marketPrice"), it.dbl("listedMedianPrice"))
        }
    }

    /** The latest sales on TCGplayer (the newest five; `variants` [77] = reverse holo only). */
    fun latestSales(productId: Long, variants: List<Int> = emptyList(), japanese: Boolean = false): List<Sale> {
        val body = JSONObject().put("conditions", JSONArray()).put("languages", JSONArray(listOf(if (japanese) 7 else 1))).put("variants", JSONArray(variants))
            .put("listingType", "All").put("limit", 25).put("offset", 0).toString()
        val o = JSONObject(http.post("https://mpapi.tcgplayer.com/v2/product/$productId/latestsales", body, BROWSER))
        val a = o.optJSONArray("data") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { s ->
            val p = s.dbl("purchasePrice") ?: return@mapNotNull null
            val at = s.optString("orderDate")
            Sale(at.take(10), s.optString("variant"), s.optString("condition"), p, s.dbl("shippingPrice") ?: 0.0, s.optInt("quantity", 1), at)
        }
    }

    /** Cheapest listing right now per printing ("Normal", "Holofoil", "Reverse Holofoil"), price plus
     *  shipping, in one condition ("Near Mint"; sealed products are "Unopened"). */
    fun lowestListings(productId: Long, condition: String = "Near Mint", japanese: Boolean = false): Map<String, Double> {
        val language = if (japanese) "Japanese" else "English"
        val body = """{"filters":{"term":{"sellerStatus":"Live","channelId":0,"language":["$language"],"condition":["$condition"]},""" +
            """"range":{"quantity":{"gte":1}},"exclude":{"channelExclusion":0}},"from":0,"size":40,""" +
            """"sort":{"field":"price+shipping","order":"asc"},"context":{"shippingCountry":"US","cart":{}},"aggregations":["listingType"]}"""
        val o = JSONObject(http.post("https://mp-search-api.tcgplayer.com/v1/product/$productId/listings", body, BROWSER))
        val a = o.optJSONArray("results")?.optJSONObject(0)?.optJSONArray("results") ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (i in 0 until a.length()) {
            val l = a.getJSONObject(i)
            val total = (l.dbl("price") ?: continue) + (l.dbl("shippingPrice") ?: 0.0)
            val k = l.optString("printing")
            if (total < (out[k] ?: Double.MAX_VALUE)) out[k] = total
        }
        return out
    }

    /** Sales history per variant/condition. range: month, quarter, semi-annual, annual. */
    fun history(productId: Long, range: String = "quarter"): List<Series> {
        val o = JSONObject(http.get("https://infinite-api.tcgplayer.com/price/history/$productId/detailed?range=$range", BROWSER))
        val a = o.optJSONArray("result") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            val b = r.optJSONArray("buckets") ?: JSONArray()
            val buckets = (0 until b.length()).map { b.getJSONObject(it) }.mapNotNull { x ->
                val m = x.optString("marketPrice").toDoubleOrNull() ?: return@mapNotNull null
                Bucket(x.optString("bucketStartDate"), m, x.optString("quantitySold").toIntOrNull() ?: 0,
                    x.optString("lowSalePrice").toDoubleOrNull(), x.optString("highSalePrice").toDoubleOrNull())
            }.sortedBy { it.date }
            Series(r.optString("variant"), r.optString("condition"), r.optString("language"), buckets,
                r.optString("averageDailyQuantitySold").toDoubleOrNull() ?: 0.0, r.optString("totalQuantitySold").toIntOrNull() ?: 0)
        }
    }

    companion object {
        const val CSV = "https://tcgcsv.com/tcgplayer"
        const val EN = 3
        const val JP = 85
        val BROWSER = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
            "Accept" to "application/json",
        )

        fun productUrl(id: Long) = "https://www.tcgplayer.com/product/$id"

        private fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

        /** "Pikachu (Poke Ball Pattern)" -> ("pokeball", "Poké Ball") etc. */
        val PATTERNS = listOf(
            Regex("\\(Poke ?Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("pokeball" to "Poké Ball"),
            Regex("\\(Master ?Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("masterball" to "Master Ball"),
            Regex("\\(Love Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("loveball" to "Love Ball"),
            Regex("\\(Friend Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("friendball" to "Friend Ball"),
            Regex("\\(Quick Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("quickball" to "Quick Ball"),
            Regex("\\(Dusk Ball Pattern\\)", RegexOption.IGNORE_CASE) to ("duskball" to "Dusk Ball"),
            Regex("\\(Energy Symbol Pattern\\)", RegexOption.IGNORE_CASE) to ("energy" to "Energy Pattern"),
            Regex("\\(Rocket Pattern\\)|\\(Team Rocket Pattern\\)", RegexOption.IGNORE_CASE) to ("rocket" to "Rocket Pattern"),
            Regex("\\(Cosmos Holo\\)", RegexOption.IGNORE_CASE) to ("cosmos" to "Cosmos Holo"),
            Regex("\\(Stamped\\)|\\(Staff\\)|\\(Prerelease\\)", RegexOption.IGNORE_CASE) to ("stamped" to "Stamped"),
        )

        fun patternOf(name: String): Pair<String, String>? = PATTERNS.firstOrNull { it.first.containsMatchIn(name) }?.second

        /** Product name without " - 123/456" and pattern suffixes. */
        fun baseName(name: String): String {
            var n = name.substringBefore(" - ")
            for ((re, _) in PATTERNS) n = re.replace(n, "")
            return n.replace(Regex("\\s+"), " ").trim()
        }
    }
}
