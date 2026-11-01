package com.mivimoose.core.net

/**
 * A price that follows the market as it trades. TCGplayer's "market price" is recalculated
 * only now and then and lags behind a moving market (a new set's chase card can sit 15%
 * above what it actually sells for). This uses what's happening now instead: the latest
 * sales plus the cheapest copy for sale, with odd ones out (mislisted, damaged, fakes) dropped.
 */
object LivePrice {
    /** basis: "sales" (recent sales and the cheapest listing), "listing" (market price capped by
     *  the cheapest listing) or "market" (TCGplayer's market price only). */
    class Quote(val price: Double, val basis: String, val sales: List<Double>, val lowest: Double?, val market: Double?)

    /** Below this share of the typical sale, a sale or listing is an odd one out. */
    private const val LOW = 0.6
    private const val HIGH = 1.67

    fun compute(market: Double?, sales: List<Double>, lowest: Double?): Quote? {
        val raw = sales.filter { it > 0 }
        if (raw.isNotEmpty()) {
            val m0 = median(raw)
            val clean = raw.filter { it in LOW * m0..HIGH * m0 }
            if (clean.size >= 3) {
                val m = median(clean)
                val low = lowest?.takeIf { it >= LOW * m }
                return Quote(round2(median(clean + listOfNotNull(low))), "sales", clean, low, market)
            }
        }
        if (market != null && market > 0) {
            val low = lowest?.takeIf { it >= LOW * market }
            return if (low != null && low < market) Quote(round2(low), "listing", raw, low, market) else Quote(market, "market", raw, low, market)
        }
        if (raw.isNotEmpty()) return Quote(round2(median(raw)), "sales", raw, lowest, null)
        return lowest?.let { Quote(round2(it), "listing", raw, it, null) }
    }

    fun median(xs: List<Double>): Double {
        val v = xs.sorted()
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2
    }

    private fun round2(x: Double) = Math.round(x * 100) / 100.0
}
