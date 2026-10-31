package com.mivimoose.core.packs

/**
 * Documented pull rates: "1 in N packs" for any card of a rarity. Keys are rarity names
 * ("a|b" = rarities sharing one rate); "G:" keys are subset cards (Trainer / Galarian
 * Gallery, Classic Collection), "G:*" = every subset card.
 */
class PullRates(
    val sets: Map<String, Map<String, Double>>,
    val series: Map<String, Map<String, Double>>,
    val jpRegular: Map<String, Double>,
    val jpHighClass: Map<String, Double>,
    /** Where each set's numbers come from, for the pull-rate tables. */
    val info: Map<String, Info> = emptyMap(),
    /** Japanese Mega-era regular boxes (an Item SR guaranteed per box). */
    val jpRegularMega: Map<String, Double> = jpRegular,
    /** God pack odds, 1 in N packs. */
    val godPacks: Map<String, Double> = emptyMap(),
) {
    class Info(val source: String, val confidence: String, val note: String, val packsSampled: Int?)

    companion object {
        /** The built-in table updated with researched rates (assets/data/pull_rates.json):
         *  each set entry {rates, godPack, packsSampled, source, confidence, note}. */
        fun load(json: String?): PullRates {
            if (json == null) return DEFAULT
            val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return DEFAULT
            fun rates(e: org.json.JSONObject?): Map<String, Double>? = e?.optJSONObject("rates")?.let { r ->
                r.keys().asSequence().associateWith { r.getDouble(it) }.filterValues { it > 0 }
            }
            val sets = HashMap(DEFAULT.sets)
            val info = HashMap<String, Info>()
            val gods = HashMap<String, Double>()
            for (k in o.keys()) {
                if (k.startsWith("_")) continue
                val e = o.optJSONObject(k) ?: continue
                rates(e)?.takeIf { it.isNotEmpty() }?.let { sets[k] = it }
                if (e.has("godPack") && !e.isNull("godPack")) gods[k] = e.getDouble("godPack")
                info[k] = Info(e.optString("source"), e.optString("confidence"), e.optString("note"),
                    if (e.has("packsSampled") && !e.isNull("packsSampled")) e.optInt("packsSampled") else null)
            }
            return PullRates(
                sets, DEFAULT.series,
                rates(o.optJSONObject("_ja_generic_regular")) ?: DEFAULT.jpRegular,
                DEFAULT.jpHighClass + (rates(o.optJSONObject("_ja_generic_high_class")) ?: emptyMap()),
                info,
                rates(o.optJSONObject("_ja_generic_regular_mega")) ?: DEFAULT.jpRegular,
                gods,
            )
        }

        private const val TG = "Trainer Gallery Rare Holo"
        private const val TG_V = "Rare Holo V|Rare Holo VMAX|Rare Holo VSTAR|Rare Ultra"

        private fun r(vararg p: Pair<String, Number>) = p.associate { it.first to it.second.toDouble() }

        /** English sets from Evolving Skies on: TCGplayer Authentication Center openings
         *  (thousands of packs per set, collected at pullrates.com). */
        val DEFAULT = PullRates(
            sets = mapOf(
                "swsh7" to r("Rare Holo" to 3, "Rare Holo V" to 9, "Rare Holo VMAX" to 18, "Rare Ultra" to 26, "Rare Rainbow" to 88, "Rare Secret" to 109),
                "cel25" to r("G:*" to 3, "Rare Holo V" to 7, "Rare Holo VMAX" to 13, "Rare Ultra" to 25, "Rare Secret" to 167),
                "swsh8" to r("Rare Holo" to 3, "Rare Holo V" to 9, "Rare Ultra" to 24, "Rare Holo VMAX" to 30, "Rare Secret" to 120, "Rare Rainbow" to 127),
                "swsh9" to r("Rare Holo" to 3, "Rare Holo V" to 10, "Rare Holo VSTAR" to 30, "Rare Rainbow" to 51, "Rare Ultra" to 54, "Rare Secret" to 92, "Rare Holo VMAX" to 200, "G:$TG" to 8, "G:$TG_V" to 18, "G:Rare Secret" to 309),
                "swsh10" to r("Rare Holo" to 3, "Rare Holo V" to 8, "Radiant Rare" to 20, "Rare Ultra" to 25, "Rare Holo VSTAR" to 37, "Rare Rainbow" to 78, "Rare Secret" to 130, "Rare Holo VMAX" to 130, "G:$TG" to 12, "G:$TG_V" to 24),
                "pgo" to r("Rare Holo" to 2, "Rare Holo V" to 7, "Rare Ultra" to 20, "Radiant Rare" to 20, "Rare Holo VSTAR" to 28, "Rare Rainbow" to 33, "Rare Holo VMAX" to 50, "Rare Secret" to 196),
                "swsh11" to r("Rare Holo" to 3, "Rare Holo V" to 9, "Radiant Rare" to 20, "Rare Ultra" to 26, "Rare Holo VSTAR" to 27, "Rare Rainbow" to 78, "Rare Secret" to 131, "Rare Holo VMAX" to 158, "G:$TG" to 12, "G:$TG_V" to 32, "G:Rare Secret" to 117),
                "swsh12" to r("Rare Holo" to 3, "Rare Holo V" to 9, "Radiant Rare" to 22, "Rare Ultra" to 27, "Rare Holo VSTAR" to 31, "Rare Rainbow" to 80, "Rare Secret" to 106, "Rare Holo VMAX" to 189, "G:$TG" to 12, "G:$TG_V" to 32, "G:Rare Secret" to 111),
                "swsh12pt5" to r("Rare Holo" to 13, "Rare Holo V" to 8, "Rare Holo VMAX" to 19, "Rare Holo VSTAR" to 19, "Radiant Rare" to 22, "Rare Ultra" to 35, "Rare Secret" to 133, "G:$TG" to 4, "G:$TG_V" to 8, "G:Rare Secret" to 125),
                "sv1" to r("Double Rare" to 7, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 32, "Hyper Rare" to 54),
                "sv2" to r("Double Rare" to 7, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 32, "Hyper Rare" to 57),
                "sv3" to r("Double Rare" to 7, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 32, "Hyper Rare" to 52),
                "sv3pt5" to r("Double Rare" to 8, "Illustration Rare" to 12, "Ultra Rare" to 16, "Special Illustration Rare" to 32, "Hyper Rare" to 51),
                "sv4" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 47, "Hyper Rare" to 82),
                "sv4pt5" to r("Shiny Rare" to 4, "Double Rare" to 6, "Shiny Ultra Rare" to 13, "Illustration Rare" to 14, "Ultra Rare" to 15, "Special Illustration Rare" to 58, "Hyper Rare" to 62),
                "sv5" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "ACE SPEC Rare" to 20, "Special Illustration Rare" to 86, "Hyper Rare" to 188),
                "sv6" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "ACE SPEC Rare" to 20, "Special Illustration Rare" to 86, "Hyper Rare" to 146),
                "sv6pt5" to r("Double Rare" to 6, "Illustration Rare" to 12, "Ultra Rare" to 15, "ACE SPEC Rare" to 20, "Special Illustration Rare" to 67, "Hyper Rare" to 144),
                "sv7" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "ACE SPEC Rare" to 20, "Special Illustration Rare" to 90, "Hyper Rare" to 137),
                "sv8" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "ACE SPEC Rare" to 20, "Special Illustration Rare" to 87, "Hyper Rare" to 188),
                "sv8pt5" to r("Double Rare" to 6, "Ultra Rare" to 13, "ACE SPEC Rare" to 21, "Special Illustration Rare" to 45, "Hyper Rare" to 180),
                "sv9" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 90, "Hyper Rare" to 150),
                "sv10" to r("Double Rare" to 6, "Illustration Rare" to 13, "Ultra Rare" to 15, "Special Illustration Rare" to 86, "Hyper Rare" to 188),
                "zsv10pt5" to r("Double Rare" to 5, "Illustration Rare" to 6, "Ultra Rare" to 17, "Special Illustration Rare" to 80, "Black White Rare" to 496),
                "rsv10pt5" to r("Double Rare" to 5, "Illustration Rare" to 6, "Ultra Rare" to 17, "Special Illustration Rare" to 80, "Black White Rare" to 496),
                "me1" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 12, "Special Illustration Rare" to 101, "Mega Hyper Rare" to 1260),
                "me2" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 12, "Special Illustration Rare" to 80, "Mega Hyper Rare" to 1260),
                "me2pt5" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 21, "Special Illustration Rare" to 70, "Mega Attack Rare" to 300, "Mega Hyper Rare" to 540),
                "me3" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 12, "Special Illustration Rare" to 81, "Mega Hyper Rare" to 1786),
                "me4" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 12, "Special Illustration Rare" to 83, "Mega Hyper Rare" to 956),
                "me5" to r("Double Rare" to 5, "Illustration Rare" to 9, "Ultra Rare" to 12, "Special Illustration Rare" to 80, "Mega Hyper Rare" to 1081),
                "me55" to r("Rare" to 2, "Double Rare" to 4, "Illustration Rare" to 5, "G:*" to 10, "Special Illustration Rare" to 21, "Futuristic Rare" to 120, "RGB Rare" to 4000),
                "ja-M6a" to r("Double Rare" to 4, "Illustration Rare" to 5, "Classic Collection" to 10, "Special Illustration Rare" to 21, "Futuristic Rare" to 120, "RGB Rare" to 4000),
            ),
            // Sword & Shield sets before Evolving Skies use the averages of the verified sets.
            series = mapOf("Sword & Shield" to r("Rare Holo" to 3, "Rare Holo V" to 9, "Rare Holo VMAX" to 18, "Rare Ultra" to 25, "Rare Rainbow" to 80, "Rare Secret" to 115)),
            // Regular 30-pack Japanese boxes: ~4 Double Rares, 3 Art Rares and one SR-or-better
            // (SR ~60%, SAR ~30%, gold UR ~10%).
            jpRegular = r("Double Rare" to 7.5, "Illustration Rare" to 10, "Ultra Rare" to 50, "Special Illustration Rare" to 90, "Hyper Rare" to 300, "Mega Hyper Rare" to 500, "Secret Rare" to 300),
            // High-class 10-pack boxes: ~9 Double Rares, 3 Art Rares and 1 SR per box.
            jpHighClass = r("Double Rare" to 1.1, "Illustration Rare" to 3.3, "Ultra Rare" to 10, "Special Illustration Rare" to 30, "Hyper Rare" to 100, "Character Rare" to 4, "Character Super Rare" to 12),
        )
    }
}
