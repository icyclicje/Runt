package com.mivimoose.scanner.data

import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.CardSet
import com.mivimoose.core.catalog.Catalog

/** One owned row valued at today's prices. */
data class Holding(val owned: Owned, val card: Card, val unit: Double) {
    val value get() = unit * owned.qty
}

/** Completion of one set: main set (numbered up to the printed count) and master set. */
data class SetProgress(
    val set: CardSet,
    val ownedMain: Int,
    val totalMain: Int,
    val ownedAll: Int,
    val totalAll: Int,
    val value: Double,
    val missingValue: Double,
) {
    val pct get() = if (totalMain > 0) ownedMain.toFloat() / totalMain else if (totalAll > 0) ownedAll.toFloat() / totalAll else 0f
    val masterPct get() = if (totalAll > 0) ownedAll.toFloat() / totalAll else 0f
}

data class PortfolioState(
    val holdings: List<Holding>,
    val total: Double,
    val cost: Double,
    val copies: Int,
    val unique: Int,
    val sets: List<SetProgress>,
) {
    val ownedKeys: Set<String> by lazy { holdings.map { it.card.key }.toSet() }
    fun countOf(key: String) = holdings.filter { it.card.key == key }.sumOf { it.owned.qty }
}

object Portfolio {
    /** Typical price of a graded copy relative to raw near-mint, PSA 1..10 (the site's model). */
    private val GRADE_MULT = doubleArrayOf(0.25, 0.3, 0.35, 0.42, 0.5, 0.6, 0.72, 0.9, 1.2, 2.6)
    private val CONDITION_MULT = mapOf("NM" to 1.0, "LP" to 0.85, "MP" to 0.7, "HP" to 0.5, "DMG" to 0.35)

    fun unitValue(card: Card, o: Owned, graded: GradedInfo? = null): Double {
        val raw = card.priceFor(o.variant)
        val g = o.grade
        if (g != null) {
            val n = Regex("(\\d+(\\.5)?)").find(g)?.value?.toDoubleOrNull()
            if (n != null) {
                graded?.let { gi ->
                    val key = when { n >= 10 && g.startsWith("PSA") -> "PSA 10"; n >= 9.5 -> "Grade 9.5"; n >= 9 -> "Grade 9"; n >= 8 -> "Grade 8"; n >= 7 -> "Grade 7"; else -> null }
                    key?.let { gi.prices[it] }?.let { return it }
                }
                return raw * GRADE_MULT[(n.toInt() - 1).coerceIn(0, 9)]
            }
        }
        return raw * (CONDITION_MULT[o.condition] ?: 1.0)
    }

    fun compute(c: Catalog, owned: List<Owned>, graded: (Card, String) -> GradedInfo? = { _, _ -> null }): PortfolioState {
        val holdings = owned.mapNotNull { o -> c.card(o.cardKey)?.let { Holding(o, it, unitValue(it, o, graded(it, o.variant))) } }
        val bySet = holdings.groupBy { it.card.setId }
        val sets = bySet.mapNotNull { (sid, hs) ->
            val s = c.set(sid) ?: return@mapNotNull null
            val have = hs.map { it.card.key }.toSet()
            val main = s.cards.filter { it.mainSet }
            SetProgress(
                s, main.count { it.key in have }, main.size, s.cards.count { it.key in have }, s.cards.size,
                hs.sumOf { it.value }, s.cards.filter { it.key !in have }.sumOf { it.price },
            )
        }.sortedWith(compareByDescending<SetProgress> { it.pct }.thenByDescending { it.value })
        return PortfolioState(
            holdings.sortedByDescending { it.value }, holdings.sumOf { it.value }, owned.sumOf { (it.cost ?: 0.0) * it.qty },
            owned.sumOf { it.qty }, holdings.map { it.card.key }.distinct().size, sets,
        )
    }

    /** Today's value into the daily history. */
    fun snapshot(store: Store, c: Catalog) {
        val p = compute(c, store.owned())
        if (p.copies > 0) store.putSnapshot(p.total, p.cost, p.copies)
    }
}
