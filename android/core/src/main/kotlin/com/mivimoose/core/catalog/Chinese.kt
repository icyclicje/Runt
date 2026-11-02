package com.mivimoose.core.catalog

import com.mivimoose.core.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chinese card lists, which the site's data doesn't keep up with:
 *  - Traditional: the official Trainers Website (asia.pokemon-card.com/tw), one page per card.
 *  - Simplified: zsjy947/PTCG-card-data on GitHub (CC0), a weekly copy of the community's card
 *    database tcg.mik.moe, which also serves the card images.
 * No shop sells these prints in dollars, so the name in English, the rarity and an estimated
 * price come from the same card in Japanese or English, as with the site's Asian sets.
 */
object Chinese {
    class Raw(val number: String, val local: String, val rarity: String?, val img: String, val nameEn: String?, val kind: String?,
              val twinCode: String?, val twinNumber: String?)

    /** dateKnown: false when `release` is only about right, worked out from the set's place in the list. */
    class RawSet(val id: String, val name: String, val lang: String, val series: String, val release: String, val printed: Int,
                 val code: String?, val rev: String?, val cards: List<Raw>, val dateKnown: Boolean = true) {
        fun dated(date: String) = RawSet(id, name, lang, series, date, printed, code, rev, cards, false)
    }

    fun toJson(sets: Collection<RawSet>): String = JSONArray().also { a ->
        for (s in sets) a.put(JSONObject().put("id", s.id).put("name", s.name).put("lang", s.lang).put("series", s.series).put("release", s.release)
            .put("printed", s.printed).put("code", s.code ?: "").put("rev", s.rev ?: "").put("known", s.dateKnown).put("cards", JSONArray().also { cs ->
                for (c in s.cards) cs.put(JSONArray().put(c.number).put(c.local).put(c.rarity ?: "").put(c.img).put(c.nameEn ?: "").put(c.kind ?: "")
                    .put(c.twinCode ?: "").put(c.twinNumber ?: ""))
            }))
    }.toString()

    fun fromJson(text: String): List<RawSet> {
        val a = JSONArray(text)
        return (0 until a.length()).map { i ->
            val s = a.getJSONObject(i)
            val cs = s.getJSONArray("cards")
            RawSet(s.getString("id"), s.getString("name"), s.getString("lang"), s.getString("series"), s.getString("release"), s.getInt("printed"),
                s.optString("code").ifEmpty { null }, s.optString("rev").ifEmpty { null },
                (0 until cs.length()).map { j ->
                    val c = cs.getJSONArray(j)
                    Raw(c.getString(0), c.getString(1), c.getString(2).ifEmpty { null }, c.getString(3), c.getString(4).ifEmpty { null },
                        c.getString(5).ifEmpty { null }, c.getString(6).ifEmpty { null }, c.getString(7).ifEmpty { null })
                }, s.optBoolean("known", true))
        }
    }

    /** The catalog with these Chinese sets in it. The site's "Simplified" sets are its Traditional
     *  lists under another name, so those go, unless `keep` (a card of it is in the collection). */
    fun merge(base: Catalog, raws: Collection<RawSet>, keep: Set<String> = emptySet()): Catalog {
        val twins = Twins(base)
        val fresh = raws.filter { it.cards.isNotEmpty() }.map { build(it, twins) }
        val ids = fresh.map { it.id }.toSet()
        val kept = base.sets.values.filter { it.id !in ids && (it.lang != "zh-cn" || it.id in keep) }
        return Catalog(kept + fresh, base.updated)
    }

    private class Twins(val c: Catalog) {
        private val ids = c.sets.keys.associateBy { it.lowercase() }
        private val cache = HashMap<String, Map<String, Card>>()
        private fun cards(s: CardSet) = cache.getOrPut(s.id) { s.cards.reversed().associateBy { Catalog.normNumber(it.number) } }
        /** Traditional sets share their code and numbers with Japanese ones. */
        fun japanese(code: String, number: String): Card? = ids["ja-" + code.lowercase()]?.let { c.sets[it] }?.let { cards(it)[Catalog.normNumber(number)] }
        /** Simplified cards list the English printing they come from. */
        fun english(code: String, number: String): Card? = c.byCode[code.uppercase()]?.filter { it.lang == "en" }?.firstNotNullOfOrNull { cards(it)[Catalog.normNumber(number)] }
    }

    private fun build(raw: RawSet, twins: Twins): CardSet {
        val set = CardSet(raw.id, raw.name, null, raw.series, raw.release, raw.printed, null, null, raw.lang, "chinese", null, true)
        set.code = raw.code
        set.dateApprox = !raw.dateKnown
        val seen = HashMap<String, Int>()
        // the official site lists a card once per foil pattern
        raw.cards.distinctBy { it.number to it.local }.forEachIndexed { i, r ->
            val twin = when {
                r.twinCode == null || r.twinNumber == null -> null
                raw.lang == "zh-tw" -> twins.japanese(r.twinCode, r.twinNumber)
                else -> twins.english(r.twinCode, r.twinNumber)
            }
            val name = BundleParser.tidyName(r.nameEn ?: twin?.name ?: r.local)
            val rarity = twin?.rarity ?: rarityName(r.rarity)
            val baseKey = "${raw.id}|${r.number}|$name"
            val k = (seen[baseKey] ?: 0) + 1
            seen[baseKey] = k
            val n = r.number.toIntOrNull()
            set.cards += Card(
                key = if (k == 1) baseKey else "$baseKey#$k", setId = raw.id, setName = raw.name, lang = raw.lang, number = r.number, name = name,
                nameLocal = r.local.takeIf { it != name }, rarity = rarity, kind = r.kind ?: twin?.kind ?: "", subtypes = emptyList(),
                price = twin?.price ?: BundleParser.defaultPrice(rarity), priceKnown = twin?.priceKnown ?: false, reversePrice = 0.0, firstEdPrice = null,
                tcgId = null, img = r.img, imgLarge = r.img, noImage = false, firstEdition = false, mainSet = n != null && (raw.printed == 0 || n <= raw.printed), order = i,
            )
        }
        return set
    }

    /** The marks printed on Chinese cards, as the rest of the catalog names them. */
    fun rarityName(r: String?): String = when (r?.uppercase()) {
        null, "" -> "Unknown"
        "无标记" -> "No Rarity"
        "C", "●" -> "Common"
        "U", "◆" -> "Uncommon"
        "R", "★" -> "Rare"
        "★★" -> "Rare Holo"
        "★★★" -> "Ultra Rare"
        "RR" -> "Double Rare"
        "RRR" -> "Triple Rare"
        "AR" -> "Illustration Rare"
        "SR" -> "Ultra Rare"
        "SAR" -> "Special Illustration Rare"
        "UR", "HR" -> "Hyper Rare"
        "S" -> "Shiny Rare"
        "SSR" -> "Shiny Ultra Rare"
        "CHR" -> "Character Rare"
        "CSR" -> "Character Super Rare"
        "ACE", "A" -> "ACE SPEC Rare"
        "PR", "PROMO" -> "Promo"
        "RGB" -> "RGB Rare"
        "FUR" -> "Futuristic Rare"
        else -> r
    }
}

/** Fetches Chinese card lists. Each card of a Traditional set is one page, so sets are only fetched when new. */
class ChineseCards(private val http: Http) {
    class Expansion(val code: String, val title: String, val release: String, val series: String)
    class CnSet(val id: String, val code: String, val name: String, val series: String, val count: Int)

    /** Traditional sets, newest first (`pages` of 20). */
    fun twExpansions(pages: Int = 1): List<Expansion> {
        val out = LinkedHashMap<String, Expansion>()
        for (p in 1..pages) {
            val items = EXPANSION.findAll(http.get("$TW/card-search/?pageNo=$p", UA)).toList()
            if (items.isEmpty()) break
            for (m in items) {
                val b = m.value
                val code = CODE.find(b)?.groupValues?.get(1) ?: continue
                val date = DATE.find(b)?.let { d -> "${d.groupValues[3]}-${d.groupValues[1]}-${d.groupValues[2]}" } ?: continue
                out.getOrPut(code) { Expansion(code, unescape(TITLE.find(b)?.groupValues?.get(1)?.trim() ?: code), date, unescape(SERIES.find(b)?.groupValues?.get(1)?.trim() ?: "")) }
            }
        }
        return out.values.toList()
    }

    /** All cards of a Traditional set. */
    fun twSet(e: Expansion): Chinese.RawSet {
        val first = http.get("$TW/card-search/list/?expansionCodes=${e.code}", UA)
        val pages = PAGES.find(first)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ids = LinkedHashSet<String>()
        DETAIL.findAll(first).forEach { ids += it.groupValues[1] }
        for (p in 2..pages) DETAIL.findAll(http.get("$TW/card-search/list/?expansionCodes=${e.code}&pageNo=$p", UA)).forEach { ids += it.groupValues[1] }
        val cards = ids.mapNotNull { id -> runCatching { twCard(id, e.code) }.getOrNull() }
        val printed = cards.mapNotNull { it.second }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val series = TW_SERIES[e.series] ?: "Other"
        return Chinese.RawSet("zh-tw-${e.code}", e.title, "zh-tw", "Chinese (Traditional) - $series", e.release, printed, e.code, null,
            cards.map { it.first }.sortedBy { it.number.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE })
    }

    private fun twCard(id: String, code: String): Pair<Chinese.Raw, Int?> {
        val h = http.get("$TW/card-search/detail/$id/", UA)
        val title = HEADING.find(h)?.groupValues?.get(1) ?: error("no name")
        val name = unescape(title.replace(TAGGED_MARKER, "").trim().replace(WS, " "))
        val num = NUMBER.find(h)?.groupValues?.get(1)?.trim() ?: error("no number")
        val img = CARD_IMG.find(h)?.groupValues?.get(1) ?: "$TW/card-img/tw%08d.png".format(id.toInt())
        val number = num.substringBefore('/').trim()
        return Chinese.Raw(number, name, null, img, null, null, code, number) to num.substringAfter('/', "").trim().toIntOrNull()
    }

    /** What the Simplified list has now: set id to the version of its card list. */
    fun cnManifest(): Map<String, String> {
        val sets = JSONObject(http.get("$CN/manifest.json", UA)).getJSONObject("sets")
        return sets.keys().asSequence().associateWith { sets.getJSONObject(it).optString("md5") }
    }

    fun cnIndex(): List<CnSet> {
        val a = JSONArray(http.get("$CN/sets_index.json", UA))
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("count") > 0 }.map {
            CnSet(it.getString("id"), it.getString("code"), it.getString("name"), it.optString("series"), it.getInt("count"))
        }
    }

    /** A release date for the sets TCGdex has none for: the list runs newest first, so each goes
     *  between the known dates around it. `sets` in the list's order. */
    fun fillDates(sets: List<Chinese.RawSet>, today: String): List<Chinese.RawSet> {
        val day = sets.map { s -> if (s.dateKnown) java.time.LocalDate.parse(s.release).toEpochDay() else null }
        var lastAt = -1
        var lastDay = java.time.LocalDate.parse(today).toEpochDay()
        return sets.indices.map { i ->
            if (day[i] != null) { lastAt = i; lastDay = day[i]!!; return@map sets[i] }
            val nextAt = (i + 1 until sets.size).firstOrNull { day[it] != null }
            val d = if (nextAt == null) lastDay - 21 * (i - lastAt) else lastDay + (day[nextAt]!! - lastDay) * (i - lastAt) / (nextAt - lastAt)
            sets[i].dated(java.time.LocalDate.ofEpochDay(d).toString())
        }
    }

    /** All cards of a Simplified set. */
    fun cnSet(s: CnSet, rev: String?, today: String): Chinese.RawSet {
        val a = JSONArray(http.get("$CN/cards/${s.id}.json", UA))
        val cards = (0 until a.length()).map { a.getJSONObject(it) }.map { c ->
            val index = c.getString("cardIndex")
            Chinese.Raw(index, c.getString("cardName"), Chinese.rarityName(c.optString("rarity")), "$CN_IMG/${c.getString("setCode")}/$index.png",
                c.optString("nameEn").ifEmpty { null }, when (c.optString("cardType")) { "Pokemon" -> "P"; "Energy" -> "E"; "" -> null; else -> "T" },
                c.optString("setCodeEn").ifEmpty { null }, c.optString("cardIndexEn").ifEmpty { null })
        }
        // TCGdex has the release date and official size of most of them
        val info = runCatching { JSONObject(http.get("https://api.tcgdex.net/v2/zh-cn/sets/${s.code}", UA)) }.getOrNull()
        val date = info?.optString("releaseDate")?.ifEmpty { null }
        val release = date ?: today
        val printed = info?.optJSONObject("cardCount")?.optInt("official")?.takeIf { it > 0 } ?: cards.size
        val series = when (s.series) { "Other" -> "Specials"; "Mega" -> "Mega Evolution"; "" -> "Other"; else -> s.series }
        return Chinese.RawSet("zh-cn-${s.id}", s.name, "zh-cn", "Chinese (Simplified) - $series", release, printed, s.code, rev, cards, date != null)
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    companion object {
        private const val TW = "https://asia.pokemon-card.com/tw"
        private const val CN = "https://raw.githubusercontent.com/zsjy947/PTCG-card-data/master/data"
        private const val CN_IMG = "https://tcg.mik.moe/static/img"
        private val UA = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
        private val TW_SERIES = mapOf("超級進化" to "Mega Evolution", "朱＆紫" to "Scarlet & Violet", "劍＆盾" to "Sword & Shield", "太陽＆月亮" to "Sun & Moon")
        private val EXPANSION = Regex("<li class=\"expansion\">.*?</li>", RegexOption.DOT_MATCHES_ALL)
        private val CODE = Regex("expansionCodes=([^\"&]+)\"")
        private val TITLE = Regex("class=\"expansionTitle\">(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
        private val SERIES = Regex("class=\"series\">(.*?)<", RegexOption.DOT_MATCHES_ALL)
        private val DATE = Regex("datetime=\"(\\d\\d)-(\\d\\d)-(\\d{4})\"")
        private val PAGES = Regex("resultTotalPages\">\\s*/\\s*\\S*\\s*(\\d+)")
        private val DETAIL = Regex("/tw/card-search/detail/(\\d+)/")
        private val HEADING = Regex("<h1 class=\"pageHeader cardDetail\">(.*?)</h1>", RegexOption.DOT_MATCHES_ALL)
        private val TAGGED_MARKER = Regex("<span[^>]*>.*?</span>", RegexOption.DOT_MATCHES_ALL)
        private val WS = Regex("\\s+")
        private val NUMBER = Regex("class=\"collectorNumber\">(.*?)<", RegexOption.DOT_MATCHES_ALL)
        private val CARD_IMG = Regex("class=\"cardImage\">\\s*<img src=\"([^\"]+)\"")
    }
}
