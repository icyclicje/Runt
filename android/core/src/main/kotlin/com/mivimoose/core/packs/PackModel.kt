package com.mivimoose.core.packs

import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.CardSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Booster pack model (port of the MivimooseRips site's packs.js): slot layouts per era,
 * documented pull rates per rarity, expected value, per-card odds and opening packs.
 *
 * Every pack is a list of slots. A slot has a base pool (e.g. commons) plus "upgrades":
 * rarities that replace the base card with probability p. Only rarities that exist in
 * the set are used, so one rarity table works across all eras.
 */
class PackModel(private val rates: PullRates = PullRates.DEFAULT) {

    class Info(val tier: Int, val slot: String = "", val p: Double = 0.0)
    class Pool(val cards: List<Card>, val reverse: Boolean, val weights: DoubleArray?) {
        val total: Double = weights?.sum() ?: cards.size.toDouble()
    }
    class Entry(val pool: Pool, val p: Double, val rarity: String)
    class God(val p: Double, val pool: Pool, val last: Pool, val size: Int)
    class Model(val slots: List<List<Entry>>, val layout: List<String>, val god: God?)
    class Pull(val card: Card, val reverse: Boolean, val slot: Int, val value: Double, val tier: Int)
    class RarityRow(val rarity: String, var cards: Int, var odds: Double, var value: Double, val tier: Int)
    class Product(val key: String, val label: String, val packs: Int, val price: Double)

    private val models = ConcurrentHashMap<String, Model>()
    private val evs = ConcurrentHashMap<String, Double>()

    fun invalidate() { models.clear(); evs.clear() }

    // ------------------------------------------------------------------ rarities

    fun rarityInfo(r: String): Info = RARITY[r] ?: RARITY_LC[r.lowercase()] ?: UNKNOWN

    private fun canonRarity(r: String) = if (RARITY.containsKey(r)) r else RARITY.keys.firstOrNull { it.equals(r, true) } ?: r

    private fun isIntl(s: CardSet) = s.lang != "en"
    private fun overridesFor(s: CardSet): Override =
        OVERRIDES[s.id] ?: (if (isIntl(s)) OVERRIDES["ja-" + s.id.substring(s.lang.length + 1)] else null) ?: Override()

    fun eraOf(s: CardSet): String = when {
        isIntl(s) -> "jp"
        s.firstEdition -> "wotc"
        else -> ERA_BY_SERIES[s.series] ?: "modern"
    }

    private fun isPromoPack(s: CardSet) = Regex("^(pop|mcd)\\d|^tcg-").containsMatchIn(s.id)
    fun isRarePack(s: CardSet) = s.firstEdition || isPromoPack(s)
    fun isSpecial(s: CardSet) = s.id in SPECIAL_SETS

    fun layoutFor(s: CardSet): List<String> {
        val ov = overridesFor(s)
        ov.layout?.let { return LAYOUTS[it] ?: it.split(',') }
        if (s.source == "tcgcsv") return autoLayout(s)
        if (Regex("^pop\\d").containsMatchIn(s.id)) return LAYOUTS.getValue("promo")
        if (Regex("^mcd\\d").containsMatchIn(s.id)) return LAYOUTS.getValue("mcd")
        return LAYOUTS.getValue(eraOf(s))
    }

    /** Sets imported from TCGplayer: size the pack from its description; sets without a
     *  normal rarity ladder pull any card. */
    private fun autoLayout(s: CardSet): List<String> {
        val n = (s.packSizeData ?: if (isIntl(s)) 5 else 4).coerceIn(1, 15)
        val tiers = s.cards.map { rarityInfo(it.rarity).tier }
        val hasCommons = s.cards.any { it.rarity == "Common" }
        if (!hasCommons || tiers.none { it >= 2 } || n < 4) return List(n) { "any" }
        val out = ArrayList<String>()
        if (tiers.none { it >= 3 }) {
            val base = n - 1
            for (i in 0 until base) out += if (i < Math.round(base * 0.7)) "C" else "U"
            return out + "rare"
        }
        val base = n - 3
        for (i in 0 until base) out += if (i < max(1, Math.round(base * 0.6).toInt())) "C" else "U"
        return out + listOf("rev", "hit", "rare")
    }

    class Rates(val main: Map<String, Double>, val gallery: List<Pair<List<String>, Double>>, val source: String)

    private val rateCache = ConcurrentHashMap<String, Rates>()

    /** Per-pack chance per rarity and where the numbers come from ('documented', 'box', 'series', 'era'). */
    fun ratesFor(s: CardSet): Rates = rateCache.getOrPut(s.id) {
        val jaTwin = if (isIntl(s) && s.lang != "ja") "ja-" + s.id.substring(s.lang.length + 1) else null
        var src = rates.sets[s.id] ?: jaTwin?.let { rates.sets[it] }
        var source = if (src != null) "documented" else null
        if (src == null && isIntl(s)) {
            val mega = Regex("^M\\d").containsMatchIn(s.id.substring(s.lang.length + 1))
            src = if (layoutFor(s).size >= 7) rates.jpHighClass else if (mega) rates.jpRegularMega else rates.jpRegular
            source = "box"
        }
        if (src == null) rates.series[s.series]?.let { src = it; source = "series" }
        val main = HashMap<String, Double>()
        val gallery = ArrayList<Pair<List<String>, Double>>()
        for ((k, n) in src ?: emptyMap()) {
            if (k.startsWith("G:")) gallery += k.substring(2).lowercase().split('|') to 1.0 / n
            else for (r in k.split('|')) main[canonRarity(r)] = 1.0 / n
        }
        Rates(main, gallery, source ?: "era")
    }

    fun rateSource(s: CardSet) = ratesFor(s).source

    /** Source, confidence and sample size behind a set's documented rates. */
    fun rateInfo(s: CardSet): PullRates.Info? = rates.info[s.id] ?: if (isIntl(s)) rates.info["ja-" + s.id.substring(s.lang.length + 1)] else null

    private val baseRarities = setOf("Common", "Uncommon", "Rare", "Rare Holo", "Promo")
    private fun baseMedian(s: CardSet, rarity: String): Double {
        val v = s.cards.filter { it.rarity == rarity && !it.gallery }.map { it.price }.sorted()
        return if (v.isEmpty()) 0.0 else v[v.size / 2]
    }

    /** Rarity used for pack slots (per-set remaps, mislabelled chase cards). */
    fun slotRarity(s: CardSet, c: Card): String {
        overridesFor(s).remap[c.rarity]?.let { return it }
        // 30th Celebration's R/G/B Mew cards are listed as "Common" but are the rarest pulls.
        if (Regex("^[RGB]$").matches(c.number) && c.rarity in baseRarities) return "RGB Rare"
        if (c.rarity in baseRarities && c.rarity != "Promo" && !isPromoPack(s) && c.price > max(5.0, 25 * baseMedian(s, c.rarity))) {
            return if (eraOf(s) == "sv" || eraOf(s) == "jp") "Mega Hyper Rare" else "Rare Secret"
        }
        // Cards numbered above the printed set size are secret rares, even when labelled "Rare".
        val n = c.number.toIntOrNull()
        if (c.rarity in baseRarities && s.printed > 0 && n != null && n > s.printed && !isIntl(s)) {
            return if (eraOf(s) == "sv") "Mega Hyper Rare" else "Rare Secret"
        }
        return c.rarity
    }

    // ------------------------------------------------------------------ model

    fun model(s: CardSet): Model = models.getOrPut(s.id) { build(s) }

    private fun build(s: CardSet): Model {
        val ov = overridesFor(s)
        val layout = layoutFor(s)
        val byRarity = LinkedHashMap<String, MutableList<Card>>()
        val gallery = ArrayList<Card>()
        val all = ArrayList<Card>()
        for (c in s.cards) {
            if (c.gallery) { gallery += c; continue }
            val r = slotRarity(s, c)
            val info = rarityInfo(r)
            if (info.slot == "none" && !isPromoPack(s) && ov.slotOf[r] == null) continue
            all += c
            byRarity.getOrPut(canonRarity(r)) { ArrayList() } += c
        }
        fun pool(cards: List<Card>?, reverse: Boolean = false, weights: DoubleArray? = null) =
            if (cards.isNullOrEmpty()) null else Pool(cards, reverse, weights)
        val rates = ratesFor(s)
        var galleryPool: Pool?
        var galleryP = ov.gallery ?: 0.2
        fun groupOf(c: Card) = rates.gallery.firstOrNull { (rs, _) -> "*" in rs || c.rarity.lowercase() in rs }
        if (rates.gallery.isNotEmpty() && gallery.isNotEmpty()) {
            val counts = HashMap<Pair<List<String>, Double>?, Int>()
            for (c in gallery) { val g = groupOf(c); counts[g] = (counts[g] ?: 0) + 1 }
            fun perCard(g: Pair<List<String>, Double>) = g.second / counts.getValue(g)
            val known = counts.keys.filterNotNull()
            val floor = known.minOfOrNull { perCard(it) } ?: 0.001
            galleryPool = pool(gallery, false, DoubleArray(gallery.size) { i -> groupOf(gallery[i])?.let { perCard(it) } ?: floor })
            galleryP = galleryPool?.total ?: galleryP
        } else {
            galleryPool = pool(gallery, false, DoubleArray(gallery.size) { GALLERY_WEIGHT[rarityInfo(gallery[it].rarity).tier] })
        }
        fun slotOf(r: String) = ov.slotOf[r] ?: rarityInfo(r).slot
        val slotKey = mapOf("common" to "C+")
        fun perSlot(p: Double, slot: String): Double {
            val k = layout.count { it == (slotKey[slot] ?: slot) }
            return if (k > 1) 1 - (1 - min(p, 0.999)).pow(1.0 / k) else p
        }
        fun upgrades(slot: String): List<Entry> = byRarity.entries.filter { slotOf(it.key) == slot }.mapNotNull { (r, cards) ->
            val documented = rates.main[r]
            pool(cards)?.let { Entry(it, if (documented != null) perSlot(documented, slot) else rarityInfo(r).p, r) }
        }
        fun firstPool(vararg names: String) = names.firstNotNullOfOrNull { byRarity[it] }

        fun dist(base: Pool?, upsIn: List<Entry>): List<Entry> {
            var ups = upsIn.filter { base == null || it.pool.cards !== base.cards }
            var sum = ups.sumOf { it.p }
            if (base == null || sum > 0.97) {
                val scale = (if (base != null) 0.97 else 1.0) / (if (sum > 0) sum else 1.0)
                ups = ups.map { Entry(it.pool, it.p * scale, it.rarity) }
                sum = ups.sumOf { it.p }
            }
            val out = ups.toMutableList()
            if (base != null && sum < 1) out += Entry(base, 1 - sum, "base")
            return out
        }

        val revCards = listOf("Common", "Uncommon", "Rare").flatMap { byRarity[it] ?: emptyList() }
        val rareBase = firstPool("Rare", "Rare Holo", "Double Rare", "Uncommon")
        val rareDist = dist(pool(rareBase), upgrades("rare"))
        val galleryUp = galleryPool?.let { listOf(Entry(it, galleryP, "gallery")) } ?: emptyList()
        val gallerySlot = ov.gallerySlot ?: "rev"
        val revBase = pool(revCards, true)
        val revDist = if (revBase != null) dist(revBase, upgrades("rev") + (if (gallerySlot == "rev") galleryUp else emptyList())) else rareDist
        val cPool = pool(byRarity["Common"]) ?: pool(byRarity["Uncommon"]) ?: revBase
        val uPool = pool(byRarity["Uncommon"]) ?: cPool
        val slotDists = HashMap<String, List<Entry>>()
        slotDists["C"] = listOfNotNull(cPool?.let { Entry(it, 1.0, "Common") })
        slotDists["C+"] = dist(cPool, upgrades("common"))
        slotDists["U"] = listOfNotNull(uPool?.let { Entry(it, 1.0, "Uncommon") })
        slotDists["rev"] = revDist
        slotDists["hit"] = dist(revBase, upgrades("hit") + (if (gallerySlot == "hit") galleryUp else emptyList()))
        slotDists["rare"] = rareDist
        slotDists["pika"] = dist(null, upgrades("pika"))
        if ((ov.gallery != null || rates.gallery.isNotEmpty()) && revBase == null) {
            slotDists["rev"] = if (galleryPool != null) listOf(Entry(galleryPool, galleryP, "gallery")) + rareDist.map { Entry(it.pool, it.p * (1 - galleryP), it.rarity) } else rareDist
        }
        // Promo sets have no reliable rarities, so pricier cards are treated as rarer.
        val anyWeights = DoubleArray(all.size) { ANY_WEIGHT[rarityInfo(slotRarity(s, all[it])).tier] * min(1.0, 15 / max(all[it].price, 0.01)) }
        slotDists["any"] = listOfNotNull(pool(all, false, anyWeights)?.let { Entry(it, 1.0, "any") })
        val slots = layout.map { k -> (slotDists[k] ?: emptyList()).ifEmpty { slotDists.getValue("any") } }.filter { it.isNotEmpty() }

        var god: God? = null
        val jaTwin = if (isIntl(s) && s.lang != "ja") "ja-" + s.id.substring(s.lang.length + 1) else null
        (this@PackModel.rates.godPacks[s.id]?.let { 1 / it } ?: jaTwin?.let { this@PackModel.rates.godPacks[it] }?.let { 1 / it } ?: ov.godPack)?.let { gp ->
            val t4 = all.filter { rarityInfo(slotRarity(s, it)).tier == 4 }
            val t5 = all.filter { rarityInfo(slotRarity(s, it)).tier == 5 }
            if (t4.isNotEmpty()) god = God(gp, Pool(t4, false, null), Pool(t5.ifEmpty { t4 }, false, null), min(layout.size, 10))
        }
        return Model(slots, layout, god)
    }

    // ------------------------------------------------------------------ opening and odds

    private fun pick(p: Pool, rng: Random): Card {
        val w = p.weights ?: return p.cards[rng.nextInt(p.cards.size)]
        var r = rng.nextDouble() * p.total
        for (i in p.cards.indices) { r -= w[i]; if (r <= 0) return p.cards[i] }
        return p.cards.last()
    }

    private fun roll(entries: List<Entry>, rng: Random): Entry {
        var r = rng.nextDouble()
        for (e in entries) { r -= e.p; if (r <= 0) return e }
        return entries.last()
    }

    private fun value(c: Card, reverse: Boolean) = if (reverse) c.reversePrice else c.price

    fun tierOf(s: CardSet, c: Card, reverse: Boolean = false): Int {
        val v = value(c, reverse)
        var tier = rarityInfo(slotRarity(s, c)).tier
        if (c.gallery) tier = max(3, tier)
        if (reverse) tier = max(2, tier)
        if (v >= 200) tier = 5 else if (v >= 50) tier = max(tier, 4) else if (v >= 10) tier = max(tier, 3)
        return tier
    }

    private fun pullOf(s: CardSet, c: Card, reverse: Boolean, slot: Int) = Pull(c, reverse, slot, value(c, reverse), tierOf(s, c, reverse))

    /** Open one pack: the pulled cards, best hits last. */
    fun open(s: CardSet, rng: Random = Random.Default): Pair<List<Pull>, Boolean> {
        val m = model(s)
        m.god?.let { g ->
            if (rng.nextDouble() < g.p) {
                val pulls = (0 until g.size - 1).map { pullOf(s, pick(g.pool, rng), false, it) } + pullOf(s, pick(g.last, rng), false, g.size - 1)
                return pulls to true
            }
        }
        val pulls = m.slots.mapIndexed { i, entries -> val e = roll(entries, rng); pullOf(s, pick(e.pool, rng), e.pool.reverse, i) }
        return pulls.withIndex().sortedBy { it.index + if (it.value.tier >= 3) 100 else 0 }.map { it.value } to false
    }

    private fun poolMean(p: Pool): Double {
        var sum = 0.0
        p.cards.forEachIndexed { i, c -> sum += value(c, p.reverse) * (p.weights?.get(i) ?: 1.0) }
        return sum / p.total
    }

    /** Expected market value of one pack. */
    fun expectedValue(s: CardSet): Double = evs.getOrPut(s.id) {
        val m = model(s)
        var ev = 0.0
        for (entries in m.slots) for (e in entries) ev += e.p * poolMean(e.pool)
        m.god?.let { g -> ev = ev * (1 - g.p) + g.p * (poolMean(g.pool) * (g.size - 1) + poolMean(g.last)) }
        ev
    }

    /** Per-pack chance of pulling this card (any slot). */
    fun cardOdds(s: CardSet, c: Card): Double {
        var expected = 0.0
        for (entries in model(s).slots) for (e in entries) {
            val i = e.pool.cards.indexOf(c)
            if (i >= 0 && !e.pool.reverse) expected += e.p * (e.pool.weights?.get(i) ?: 1.0) / e.pool.total
        }
        return expected
    }

    /** Chance that one pack holds at least one card worth >= threshold. */
    fun hitChance(s: CardSet, threshold: Double = 20.0): Double {
        var miss = 1.0
        for (entries in model(s).slots) {
            var q = 0.0
            for (e in entries) {
                var w = 0.0
                e.pool.cards.forEachIndexed { i, c -> if (value(c, e.pool.reverse) >= threshold) w += e.pool.weights?.get(i) ?: 1.0 }
                q += e.p * (w / e.pool.total)
            }
            miss *= 1 - q
        }
        return 1 - miss
    }

    fun godPackOdds(s: CardSet) = model(s).god?.p ?: 0.0
    fun packSize(s: CardSet) = model(s).slots.size

    /** Per-rarity summary: cards in set, chance per pack, average value. */
    fun rarityTable(s: CardSet): List<RarityRow> {
        val groups = LinkedHashMap<String, RarityRow>()
        for (c in s.cards) {
            val r = if (c.gallery) "Subset / Gallery" else slotRarity(s, c)
            val g = groups.getOrPut(r) { RarityRow(r, 0, 0.0, 0.0, if (c.gallery) 3 else rarityInfo(r).tier) }
            g.cards++; g.value += c.price; g.odds += cardOdds(s, c)
        }
        return groups.values.onEach { it.value /= it.cards }.sortedWith(compareBy<RarityRow> { it.tier }.thenByDescending { it.odds })
    }

    /** Pack, bundle and box prices: real sealed prices when known, never a sure profit. */
    fun products(s: CardSet): List<Product> {
        val ev = expectedValue(s)
        val sealed = s.sealedPrice
        val pack = if (sealed != null) max(0.99, round2(max(sealed, ev / 0.95))) else max(1.99, Math.ceil(ev / 0.85 * 2) / 2 - 0.01)
        val special = isSpecial(s) || isRarePack(s)
        var boxCount = if (special) 18 else 36
        var boxLabel = if (special) "Mega Bundle" else "Booster Box"
        if (isIntl(s)) { boxCount = if (layoutFor(s).size >= 7) 10 else 30; boxLabel = "Booster Box" }
        val discount = if (boxCount <= 10) 0.92 else if (special) 0.9 else 0.88
        fun floor(n: Int) = n * ev / 0.97
        return listOf(
            Product("pack", "Booster Pack", 1, pack),
            Product("bundle", "Booster Bundle", 6, round2(max(pack * 6 * 0.95, floor(6)))),
            Product("box", boxLabel, boxCount, round2(max(pack * boxCount * discount, floor(boxCount)))),
        )
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0

    class Override(
        val layout: String? = null,
        val gallery: Double? = null,
        val godPack: Double? = null,
        val slotOf: Map<String, String> = emptyMap(),
        val gallerySlot: String? = null,
        val remap: Map<String, String> = emptyMap(),
    )

    companion object {
        private val RARITY: Map<String, Info> = linkedMapOf(
            "Common" to Info(0), "Uncommon" to Info(0), "Rare" to Info(1), "Promo" to Info(1, "none"),
            "Rare Holo" to Info(2, "rare", 0.30), "Rare Holo EX" to Info(3, "rare", 0.14), "Rare Holo GX" to Info(3, "rare", 0.14),
            "Rare Holo V" to Info(3, "rare", 0.14), "Rare Holo VMAX" to Info(3, "rare", 0.07), "Rare Holo VSTAR" to Info(3, "rare", 0.07),
            "Rare Holo LV.X" to Info(3, "rare", 0.08), "Rare BREAK" to Info(3, "rare", 0.10), "Rare Prime" to Info(3, "rare", 0.12),
            "LEGEND" to Info(4, "rare", 0.05), "Rare ACE" to Info(3, "rare", 0.07), "Rare Prism Star" to Info(3, "rare", 0.07),
            "Rare Holo Star" to Info(5, "rare", 0.015), "Rare Ultra" to Info(4, "rare", 0.05), "Rare Rainbow" to Info(5, "rare", 0.025),
            "Rare Secret" to Info(5, "rare", 0.015), "Amazing Rare" to Info(4, "rare", 0.05), "Rare Shining" to Info(5, "rare", 0.03),
            "Rare Shiny" to Info(4, "rare", 0.04), "Rare Shiny GX" to Info(5, "rare", 0.02), "Double Rare" to Info(3, "rare", 0.17),
            "Ultra Rare" to Info(4, "rare", 0.065), "Radiant Rare" to Info(4, "common", 0.06), "ACE SPEC Rare" to Info(3, "rev", 0.05),
            "Illustration Rare" to Info(4, "hit", 0.08), "Special Illustration Rare" to Info(5, "hit", 0.02), "Hyper Rare" to Info(5, "hit", 0.012),
            "Shiny Rare" to Info(4, "hit", 0.2), "Shiny Ultra Rare" to Info(5, "hit", 0.025), "Black White Rare" to Info(5, "hit", 0.006),
            "Mega Hyper Rare" to Info(5, "hit", 0.004), "Mega Attack Rare" to Info(4, "hit", 0.02), "Classic Collection" to Info(4, "hit", 0.1),
            "Pikachu Rare" to Info(3, "pika", 1.0), "Futuristic Rare" to Info(4, "hit", 0.08), "RGB Rare" to Info(5, "hit", 0.0007),
            // Japanese rarity names (TCGdex)
            "Holo Rare" to Info(2, "rare", 0.30), "Triple Rare" to Info(3, "rare", 0.08), "Character Rare" to Info(4, "hit", 0.10),
            "Character Super Rare" to Info(5, "hit", 0.03), "Secret Rare" to Info(5, "hit", 0.02),
        )
        private val RARITY_LC = RARITY.mapKeys { it.key.lowercase() }
        private val UNKNOWN = Info(3, "rare", 0.03)
        private val ERA_BY_SERIES = mapOf("Base" to "wotc", "Gym" to "wotc", "Neo" to "wotc", "Other" to "wotc",
            "E-Card" to "ecard", "EX" to "ex", "Scarlet & Violet" to "sv", "Mega Evolution" to "sv")
        private val LAYOUTS = mapOf(
            "wotc" to "C,C,C,C,C,C,C,U,U,U,rare", "ecard" to "C,C,C,C,C,U,U,rev,rare", "ex" to "C,C,C,C,C,U,U,rev,rare",
            "modern" to "C,C,C,C,C+,U,U,U,rev,rare", "sv" to "C,C,C,C,U,U,U,rev,hit,rare", "jp" to "C,C,U,hit,rare",
            "jp7" to "C,C,C,U,U,hit,rare", "promo" to "any,any", "mcd" to "any,any,any,any",
        ).mapValues { it.value.split(',') }
        private val GALLERY_WEIGHT = doubleArrayOf(1.0, 1.0, 1.0, 0.5, 0.25, 0.12)
        private val ANY_WEIGHT = doubleArrayOf(1.0, 1.0, 0.35, 0.15, 0.07, 0.03)
        val SPECIAL_SETS = setOf("dv1", "dc1", "g1", "sm35", "sm75", "sm115", "swsh35", "swsh45", "cel25", "pgo", "swsh12pt5",
            "sv3pt5", "sv4pt5", "sv6pt5", "sv8pt5", "zsv10pt5", "rsv10pt5", "me2pt5", "me55")
        private val OVERRIDES: Map<String, Override> = mapOf(
            "base6" to Override(layout = "C,C,C,C,C,C,U,U,U,rev,rare"),
            "g1" to Override(gallery = 0.25), "sm115" to Override(gallery = 0.3), "swsh45" to Override(gallery = 0.3),
            "swsh9" to Override(gallery = 0.2), "swsh10" to Override(gallery = 0.2), "swsh11" to Override(gallery = 0.2), "swsh12" to Override(gallery = 0.2),
            "swsh12pt5" to Override(gallery = 0.3),
            "cel25" to Override(layout = "rare,rare,rare,rev", gallery = 0.5),
            // 30th Celebration: guaranteed Pikachu + 4 foils (one may be a Rare, one may be a hit) + energy.
            "me55" to Override(layout = "C,C,C+,hit,pika", slotOf = mapOf("Double Rare" to "hit", "Rare" to "common"), gallerySlot = "hit"),
            "ja-M6a" to Override(layout = "C,C,C+,hit,pika", slotOf = mapOf("Double Rare" to "hit", "Rare" to "common")),
            "sv8pt5" to Override(godPack = 1 / 2000.0),
            "zsv10pt5" to Override(godPack = 1 / 850.0), "rsv10pt5" to Override(godPack = 1 / 850.0),
            "ja-SV2a" to Override(layout = "jp7", godPack = 1 / 800.0),
            "ja-SV4a" to Override(layout = "sv", godPack = 1 / 500.0, remap = mapOf("Illustration rare" to "Shiny Rare")),
            "ja-SV8a" to Override(layout = "sv", godPack = 1 / 500.0),
            "ja-S12a" to Override(layout = "sv", godPack = 1 / 600.0), "ja-S4a" to Override(layout = "sv", godPack = 1 / 600.0),
            "ja-S8b" to Override(layout = "sv", godPack = 1 / 800.0), "ja-SM12a" to Override(layout = "sv", godPack = 1 / 800.0),
            "ja-SM8b" to Override(layout = "sv"),
            "ja-S6a" to Override(layout = "jp", godPack = 1 / 1000.0), "ja-S11a" to Override(layout = "jp", godPack = 1 / 1000.0),
            "ja-S10a" to Override(layout = "jp", godPack = 1 / 1000.0),
            "tcg-1663" to Override(layout = "wotc"), "tcg-3179" to Override(layout = "any,any,any"),
            "tcg-23266" to Override(layout = "any,any,any"), "tcg-23561" to Override(layout = "any,any,any"),
        )
    }
}
