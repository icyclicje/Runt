package com.mivimoose.core.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the MivimooseRips site's data files: JavaScript that calls
 * PackData.addSet({...}) once per set, and PackData.setPackArt({...}) with sealed prices.
 * Port of the desktop scanner's carddb.py.
 */
object BundleParser {
    const val IMG_BASE = "https://images.pokemontcg.io/"
    const val TCGDEX_BASE = "https://assets.tcgdex.net/"
    const val TCGP_BASE = "https://tcgplayer-cdn.tcgplayer.com/product/"
    private val FIRST_EDITION = listOf("base2", "base3", "base5", "gym1", "gym2", "neo1", "neo2", "neo3", "neo4")
    private val SUFFIX_RE = Regex("(^|[\\s\\-&(])(ex|gx|v|vmax|vstar|v-union|lv\\.x)(?=$|[\\s,&)\\-])", RegexOption.IGNORE_CASE)
    private val ENUM_RE = Regex("^[A-Z]+(_[A-Z]+)+$")
    private val GALLERY_RE = Regex("^(TG|GG|SV|RC|SH)\\d", RegexOption.IGNORE_CASE)
    private val INCOMPLETE_RE = Regex("illustration|ultra|hyper|secret|special|character|shiny|triple", RegexOption.IGNORE_CASE)
    private val DEFAULT_PRICE = listOf(
        "^common$" to 0.08, "^uncommon$" to 0.12, "^rare$" to 0.3, "^(rare holo|holo rare)$" to 1.0, "promo" to 1.0,
        "special illustration|special art|character super|super rare|\\bsar\\b" to 30.0, "hyper|rainbow|secret|gold|\\bur\\b" to 18.0,
        "illustration|character|\\bar\\b|\\bchr\\b" to 5.0, "shiny|shining|star|legend|amazing|radiant" to 6.0,
        "ultra|full art|\\bsr\\b" to 6.0, "double|triple|ex|gx|vmax|vstar|\\bv\\b|\\brr\\b" to 1.5,
    ).map { Regex(it.first, RegexOption.IGNORE_CASE) to it.second }

    fun defaultPrice(rarity: String): Double = DEFAULT_PRICE.firstOrNull { it.first.containsMatchIn(rarity) }?.second ?: 2.0

    fun tidyName(n: String): String = SUFFIX_RE.replace(n) { it.groupValues[1] + it.groupValues[2].uppercase() }

    fun normRarity(r: String?): String {
        if (r == null) return ""
        return if (ENUM_RE.matches(r)) r.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } else r
    }

    /** (small, large) image URLs for the site's image codes. */
    fun imageUrls(img: String): Pair<String, String> = when {
        img.startsWith("sx:") -> {
            val (s, num) = img.substring(3).split('/', limit = 2)
            "https://images.scrydex.com/pokemon/$s-$num/small" to "https://images.scrydex.com/pokemon/$s-$num/large"
        }
        img.startsWith("tcgp:") -> img.substring(5).let { "$TCGP_BASE${it}_200w.jpg" to "$TCGP_BASE${it}_in_1000x1000.jpg" }
        img.startsWith("tcgdex:") -> (TCGDEX_BASE + img.substring(7)).let { "$it/low.webp" to "$it/high.webp" }
        else -> "$IMG_BASE$img.png" to "${IMG_BASE}${img}_hires.png"
    }

    fun tcgImage(id: Long, large: Boolean = false) = if (large) "$TCGP_BASE${id}_in_1000x1000.jpg" else "$TCGP_BASE${id}_200w.jpg"

    /** Every `PackData.<fn>(json)` call in a data file, as (fn, raw JSON text). */
    fun calls(text: String): Sequence<Pair<String, String>> = sequence {
        val marker = "PackData."
        var i = text.indexOf(marker)
        while (i >= 0) {
            val open = text.indexOf('(', i)
            if (open < 0) break
            val fn = text.substring(i + marker.length, open)
            val next = text.indexOf("\n$marker", open).let { if (it < 0) text.indexOf(marker, open) else it }
            var end = if (next < 0) text.length else next
            // trim "); " and whitespace back to the JSON value's end
            while (end > open && text[end - 1].let { it == ';' || it == ')' || it.isWhitespace() }) end--
            if (fn.all { it.isLetterOrDigit() || it == '_' }) yield(fn to text.substring(open + 1, end))
            i = next.let { if (it < 0) -1 else text.indexOf(marker, it) }
        }
    }

    fun parse(bundle: String, packArt: String?): Catalog {
        val sets = LinkedHashMap<String, CardSet>()
        var updated = ""
        for ((fn, raw) in calls(bundle)) {
            if (fn != "addSet") continue
            val o = try { JSONObject(raw) } catch (e: Exception) { continue }
            val s = buildSet(o)
            sets[s.id] = s
            val u = o.optString("updated", "")
            if (u > updated) updated = u
        }
        for (id in FIRST_EDITION) sets[id]?.let { val fe = firstEdition(it); sets[fe.id] = fe }
        if (packArt != null) {
            for ((fn, raw) in calls(packArt)) {
                if (fn != "setPackArt") continue
                val o = try { JSONObject(raw) } catch (e: Exception) { continue }
                for (s in sets.values) o.optJSONObject(s.id)?.let { s.sealedPrice = sealedPrice(it) }
            }
        }
        return Catalog(sets.values.toList(), updated)
    }

    private fun JSONArray.optDoubleOrNull(i: Int): Double? = if (i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }
    private fun JSONArray.optStr(i: Int): String? = if (i >= length() || isNull(i)) null else optString(i)
    private fun JSONObject.optStr(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)

    fun buildSet(raw: JSONObject): CardSet {
        val id = raw.getString("id")
        val name = raw.optStr("name") ?: id
        val lang = raw.optStr("lang") ?: "en"
        var series = raw.optStr("series") ?: ""
        if (Regex("^(pop|mcd)\\d").containsMatchIn(id)) series = "Rare & Promo Packs"
        val s = CardSet(
            id = id, name = name, nameLocal = raw.optStr("nameLocal")?.takeIf { it != name }, series = series,
            release = (raw.optStr("releaseDate") ?: "").replace('/', '-'), printed = raw.optInt("printed", 0),
            logo = raw.optStr("logo"), symbol = raw.optStr("symbol"), lang = lang, source = raw.optStr("source"),
            packSizeData = if (raw.has("packSize") && !raw.isNull("packSize")) raw.optInt("packSize") else null,
            estimated = raw.optBoolean("estimated", false),
        )
        val rows = raw.optJSONArray("cards") ?: JSONArray()
        val intl = lang != "en"
        val byRarity = HashMap<String, MutableList<Double>>()
        for (i in 0 until rows.length()) {
            val c = rows.optJSONArray(i) ?: continue
            c.optDoubleOrNull(3)?.let { byRarity.getOrPut(normRarity(c.optStr(2))) { ArrayList() } += it }
        }
        val median = byRarity.mapValues { (_, v) -> v.sorted()[v.size / 2] }
        val seen = HashMap<String, Int>()
        for (i in 0 until rows.length()) {
            val c = rows.optJSONArray(i) ?: continue
            val number = c.optStr(0) ?: ""
            val cname = tidyName(c.optStr(1) ?: "")
            val rarity = normRarity(c.optStr(2))
            val price = c.optDoubleOrNull(3)
            val rev = c.optDoubleOrNull(4)
            val img = c.optStr(5)
            val nameLocal = c.optStr(9)
            val tcgId = if (c.length() > 10 && !c.isNull(10)) c.optLong(10) else null
            val base = price ?: median[rarity] ?: defaultPrice(rarity)
            val (small, large) = if (img != null) imageUrls(img) else ("" to "")
            val n = number.toIntOrNull()
            val imgSet = if (img == null || img.startsWith("tcgdex:") || img.startsWith("tcgp:")) id else img.removePrefix("sx:").substringBefore('/')
            val baseKey = "$id|$number|$cname"
            val k = (seen[baseKey] ?: 0) + 1
            seen[baseKey] = k
            val card = Card(
                key = if (k == 1) baseKey else "$baseKey#$k", setId = id, setName = name, lang = lang,
                number = number, name = cname, nameLocal = nameLocal?.takeIf { it != cname }, rarity = rarity,
                kind = c.optStr(6) ?: "", subtypes = c.optStr(7)?.split('|')?.filter { it.isNotEmpty() } ?: emptyList(),
                price = base, priceKnown = price != null,
                reversePrice = if (!intl && rev != null) rev else maxOf(0.1, round2(base * 1.4)),
                firstEdPrice = c.optDoubleOrNull(8), tcgId = tcgId, img = small, imgLarge = large, noImage = img == null,
                firstEdition = false, mainSet = n != null && (s.printed == 0 || n <= s.printed), order = i,
                gallery = imgSet != id || GALLERY_RE.containsMatchIn(number),
            )
            card.reverseKnown = !intl && rev != null
            s.cards += card
        }
        if (!s.estimated) s.estimated = s.cards.count { it.priceKnown } < s.cards.size * 0.25
        s.incomplete = intl && s.source != "tcgcsv" && s.cards.none { INCOMPLETE_RE.containsMatchIn(it.rarity) }
        return s
    }

    private fun firstEdition(base: CardSet): CardSet {
        val s = CardSet(base.id + "-1st", base.name + " 1st Edition", null, "Rare & Promo Packs", base.release, base.printed,
            base.logo, base.symbol, base.lang, base.source, base.packSizeData, base.estimated)
        s.firstEdition = true
        s.baseId = base.id
        for (bc in base.cards) {
            s.cards += Card(
                key = bc.key.replaceFirst(base.id + "|", s.id + "|"), setId = s.id, setName = s.name, lang = bc.lang,
                number = bc.number, name = bc.name, nameLocal = bc.nameLocal, rarity = bc.rarity, kind = bc.kind,
                subtypes = bc.subtypes, price = bc.firstEdPrice ?: round2(bc.price * 2.5), priceKnown = bc.firstEdPrice != null,
                reversePrice = bc.reversePrice, firstEdPrice = bc.firstEdPrice, tcgId = bc.tcgId, img = bc.img,
                imgLarge = bc.imgLarge, noImage = bc.noImage, firstEdition = true, mainSet = bc.mainSet, order = bc.order,
                gallery = bc.gallery,
            )
        }
        return s
    }

    /** Median of the per-pack prices of single packs, bundles and boxes (ignoring outliers). */
    private fun sealedPrice(a: JSONObject): Double? {
        val s = a.optJSONObject("s")
        val m = if (a.has("m") && !a.isNull("m")) a.optDouble("m") else null
        if (s == null) return m
        var vals = s.keys().asSequence().mapNotNull { k -> s.optDouble(k).takeIf { !it.isNaN() && it > 0 } }.toList()
        val pack = s.optDouble("pack").takeIf { !it.isNaN() && it > 0 }
        if (pack != null) vals = vals.filter { it in pack / 2..pack * 2 }
        if (vals.isEmpty()) return m
        val v = vals.sorted()
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else round2((v[mid - 1] + v[mid]) / 2)
    }

    fun round2(x: Double) = Math.round(x * 100) / 100.0
}
