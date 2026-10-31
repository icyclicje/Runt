package com.mivimoose.core.catalog

import com.mivimoose.core.net.TcgPlayer

/**
 * Brings TCGplayer's catalog into the site's card data: daily prices per printing,
 * Poké Ball / Master Ball pattern printings, cards the site's lists are missing, and whole
 * sets the site doesn't have yet (new releases, Japanese sets).
 */
object Supplements {
    private val NUM_RE = Regex("^([A-Za-z]*\\d+[a-zA-Z]?)(?:/.*)?$")

    /** "199/165" -> "199", "TG05/TG30" -> "TG05", "SWSH050" -> "SWSH050". */
    fun cardNumber(n: String?): String? {
        val s = n?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return NUM_RE.matchEntire(s)?.groupValues?.get(1) ?: s.substringBefore('/')
    }

    private fun kindOf(cardType: String?) = when {
        cardType == null -> "P"
        cardType.contains("Trainer", true) || cardType.contains("Supporter", true) || cardType.contains("Item", true) || cardType.contains("Stadium", true) -> "T"
        cardType.contains("Energy", true) -> "E"
        else -> "P"
    }

    /** Applies one set's daily TCGplayer prices. Returns how many cards changed. */
    fun applyPrices(catalog: Catalog, products: List<TcgPlayer.Product>?, prices: List<TcgPlayer.Price>): Int {
        val byProduct = prices.groupBy { it.productId }
        var n = 0
        for ((pid, rows) in byProduct) {
            val cards = catalog.byTcgId[pid] ?: continue
            for (c in cards) {
                val normal = rows.firstOrNull { it.subType == "Normal" }?.market
                val holo = rows.firstOrNull { it.subType == "Holofoil" || it.subType.endsWith("Holofoil") && !it.subType.startsWith("Reverse") }?.market
                val rev = rows.firstOrNull { it.subType == "Reverse Holofoil" }?.market
                val main = if (c.firstEdition) rows.firstOrNull { it.subType.startsWith("1st Edition") }?.market else (holo ?: normal).let {
                    // Commons/uncommons list both "Normal" and maybe "Holofoil": the main printing is the non-foil one.
                    if (normal != null && holo != null && !c.rarity.contains("holo", true) && !c.isHit && c.rarity.lowercase() in setOf("common", "uncommon", "rare")) normal else it
                }
                if (main != null && main > 0) { c.price = main; c.priceKnown = true; n++ }
                if (rev != null && rev > 0) { c.reversePrice = rev; c.reverseKnown = true }
            }
        }
        if (products != null) attachPatterns(catalog, products, byProduct)
        return n
    }

    /** Pattern printings (Poké Ball, Master Ball...) are separate TCGplayer products: attach
     *  them to the matching card (same set, number and name) as extra variants. */
    private fun attachPatterns(catalog: Catalog, products: List<TcgPlayer.Product>, prices: Map<Long, List<TcgPlayer.Price>>) {
        val known = products.filter { catalog.byTcgId.containsKey(it.id) }
        val setId = known.firstNotNullOfOrNull { catalog.byTcgId[it.id]?.firstOrNull { c -> !c.firstEdition }?.setId } ?: return
        val set = catalog.sets[setId] ?: return
        val byNum = set.cards.groupBy { Catalog.normNumber(it.number) }
        val extras = HashMap<Card, MutableList<Variant>>()
        for (p in products) {
            val (id, label) = TcgPlayer.patternOf(p.name) ?: continue
            val num = cardNumber(p.number) ?: continue
            val base = TcgPlayer.baseName(p.name)
            val card = byNum[Catalog.normNumber(num)]?.firstOrNull { Catalog.fold(it.name) == Catalog.fold(base) }
                ?: byNum[Catalog.normNumber(num)]?.firstOrNull() ?: continue
            val price = prices[p.id]?.let { r -> r.firstOrNull { it.subType != "Normal" }?.market ?: r.firstOrNull()?.market }
            extras.getOrPut(card) { ArrayList() } += Variant(id, label, price, p.id, prices[p.id]?.firstOrNull()?.subType)
        }
        for ((c, v) in extras) c.extraVariants = v.distinctBy { it.id }
    }

    /** Cards TCGplayer lists for a set that the site's list is missing. */
    fun missingCards(set: CardSet, products: List<TcgPlayer.Product>, prices: List<TcgPlayer.Price>): List<Card> {
        val have = set.cards.mapNotNull { it.tcgId }.toHashSet()
        val haveNum = set.cards.map { Catalog.normNumber(it.number) + "|" + Catalog.fold(it.name) }.toHashSet()
        val priceOf = prices.groupBy { it.productId }
        val out = ArrayList<Card>()
        for (p in products) {
            if (!p.isCard || p.id in have || TcgPlayer.patternOf(p.name) != null) continue
            val num = cardNumber(p.number) ?: continue
            val name = BundleParser.tidyName(TcgPlayer.baseName(p.name))
            if (Catalog.normNumber(num) + "|" + Catalog.fold(name) in haveNum) continue
            out += cardFrom(set, p, priceOf[p.id], set.cards.size + out.size, num, name)
        }
        return out
    }

    private fun cardFrom(set: CardSet, p: TcgPlayer.Product, prices: List<TcgPlayer.Price>?, order: Int, num: String, name: String): Card {
        val rarity = BundleParser.normRarity(p.rarity ?: "")
        val main = prices?.let { r -> (r.firstOrNull { it.subType == "Holofoil" } ?: r.firstOrNull { it.subType == "Normal" } ?: r.firstOrNull())?.market }
        val rev = prices?.firstOrNull { it.subType == "Reverse Holofoil" }?.market
        val base = main ?: BundleParser.defaultPrice(rarity)
        val n = num.toIntOrNull()
        return Card(
            key = "${set.id}|$num|$name", setId = set.id, setName = set.name, lang = set.lang, number = num, name = name,
            nameLocal = null, rarity = rarity, kind = kindOf(p.cardType), subtypes = listOfNotNull(p.stage),
            price = base, priceKnown = main != null, reversePrice = rev ?: BundleParser.round2(base * 1.4),
            firstEdPrice = null, tcgId = p.id, img = BundleParser.tcgImage(p.id), imgLarge = BundleParser.tcgImage(p.id, true),
            noImage = false, firstEdition = false, mainSet = n != null && (set.printed == 0 || n <= set.printed), order = order,
            gallery = Regex("^(TG|GG|SV|RC|SH)\\d", RegexOption.IGNORE_CASE).containsMatchIn(num),
        ).also { it.reverseKnown = rev != null }
    }

    /** A whole set from TCGplayer's catalog (one the site doesn't have yet). */
    fun importGroup(g: TcgPlayer.Group, products: List<TcgPlayer.Product>, prices: List<TcgPlayer.Price>): CardSet? {
        val cards = products.filter { it.isCard && TcgPlayer.patternOf(it.name) == null }
        if (cards.size < 5) return null
        val lang = if (g.category == TcgPlayer.JP) "ja" else "en"
        val id = (if (lang == "ja") "ja-tcg-" else "tcg-") + g.id
        val name = g.name.substringAfter(": ", g.name).trim()
        val printed = cards.mapNotNull { it.number?.substringAfter('/', "")?.toIntOrNull() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val series = when {
            g.name.startsWith("ME") -> "Mega Evolution"
            g.name.startsWith("SV") -> "Scarlet & Violet"
            g.name.startsWith("SWSH") -> "Sword & Shield"
            lang == "ja" -> "Japanese - New releases"
            else -> "New releases"
        }
        val set = CardSet(id, name, null, series, g.published, printed, null, null, lang, "tcgcsv", if (lang == "ja") 5 else 10, false)
        set.tcgGroupId = g.id
        set.code = g.abbreviation.ifEmpty { null }
        val priceOf = prices.groupBy { it.productId }
        val seen = HashSet<String>()
        cards.sortedWith(compareBy({ cardNumber(it.number)?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 9999 }, { it.name })).forEach { p ->
            val num = cardNumber(p.number) ?: return@forEach
            val name0 = BundleParser.tidyName(TcgPlayer.baseName(p.name))
            if (!seen.add("$num|$name0")) return@forEach
            set.cards += cardFrom(set, p, priceOf[p.id], set.cards.size, num, name0)
        }
        set.estimated = set.cards.count { it.priceKnown } < set.cards.size * 0.25
        val withPatterns = Catalog(listOf(set), "")
        attachPatterns(withPatterns, products, priceOf)
        return set
    }
}
