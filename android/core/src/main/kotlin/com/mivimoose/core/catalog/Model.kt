package com.mivimoose.core.catalog

/** A printing of a card with its own price (normal, holo, reverse holo, Poké Ball pattern...). */
class Variant(
    val id: String,
    val label: String,
    @Volatile var price: Double?,
    val productId: Long? = null,
    /** TCGplayer sub-type name ("Normal", "Holofoil", "Reverse Holofoil"...). */
    val subType: String? = null,
)

class Card(
    val key: String,
    val setId: String,
    val setName: String,
    val lang: String,
    val number: String,
    val name: String,
    val nameLocal: String?,
    val rarity: String,
    val kind: String,              // P pokemon, T trainer, E energy
    val subtypes: List<String>,
    @Volatile var price: Double,
    @Volatile var priceKnown: Boolean,
    @Volatile var reversePrice: Double,
    val firstEdPrice: Double?,
    val tcgId: Long?,
    val img: String,
    val imgLarge: String,
    val noImage: Boolean,
    val firstEdition: Boolean,
    val mainSet: Boolean,
    val order: Int,
    /** Subset card (Trainer Gallery, Shiny Vault, Galarian Gallery, Classic Collection...). */
    val gallery: Boolean = false,
) {
    /** Extra printings found in TCGplayer's catalog (Poké Ball / Master Ball patterns...). */
    @Volatile var extraVariants: List<Variant> = emptyList()

    val isHit: Boolean get() = HIT_RE.containsMatchIn(rarity)

    /** Every printing with a price: the main one, reverse holo where it exists, plus extras. */
    fun variants(): List<Variant> {
        val out = ArrayList<Variant>(3)
        val holoMain = rarity.contains("holo", true) || isHit || rarity.contains("rare", true) && !rarity.equals("rare", true)
        out += Variant(if (firstEdition) "first" else if (holoMain) "holo" else "normal",
            if (firstEdition) "1st Edition" else if (holoMain) "Holo" else "Normal", price, tcgId)
        if (hasReverse) out += Variant("reverse", "Reverse Holo", reversePrice, tcgId, "Reverse Holofoil")
        out += extraVariants
        return out
    }

    /** Reverse holos exist for commons through rares (and holo rares) of modern sets. */
    val hasReverse: Boolean
        get() = !firstEdition && (reverseKnown || (lang == "en" && REVERSE_RARITY.matches(rarity) && mainSet))

    @Volatile var reverseKnown: Boolean = false

    fun priceFor(variant: String): Double = when (variant) {
        "reverse" -> reversePrice
        "holo", "normal", "first", "" -> price
        else -> extraVariants.firstOrNull { it.id == variant }?.price ?: price
    }

    fun variantLabel(variant: String): String = variants().firstOrNull { it.id == variant }?.label ?: when (variant) {
        "reverse" -> "Reverse Holo"; "first" -> "1st Edition"; "holo" -> "Holo"; else -> "Normal"
    }

    /** Name in the card's own language when it has one (Japanese, Korean...). */
    val displayName: String get() = name

    companion object {
        val HIT_RE = Regex("illustration|ultra|hyper|secret|special|character|shiny|triple|gold|rainbow|full art|radiant|amazing|\\bsar\\b|\\bsir\\b|\\bur\\b", RegexOption.IGNORE_CASE)
        private val REVERSE_RARITY = Regex("(?i)^(common|uncommon|rare|rare holo|holo rare|ace spec rare)$")
    }
}

class CardSet(
    val id: String,
    val name: String,
    val nameLocal: String?,
    val series: String,
    val release: String,          // yyyy-mm-dd
    val printed: Int,
    val logo: String?,
    val symbol: String?,
    val lang: String,
    val source: String?,
    val packSizeData: Int?,
    var estimated: Boolean,
) {
    /** Copy-on-write: cards can arrive from TCGplayer while the scanner is reading the set. */
    val cards: MutableList<Card> = java.util.concurrent.CopyOnWriteArrayList()
    var sealedPrice: Double? = null
    /** TCGplayer product of the set's booster pack, for its live price. */
    var packProductId: Long? = null
    var firstEdition = false
    var baseId: String? = null
    /** TCGplayer group id (tcgcsv), when known. */
    var tcgGroupId: Long? = null
    /** Set code printed on cards ("MEW", "SV2a"...), when known. */
    var code: String? = null
    /** TCGplayer's name for the set ("SV: Scarlet & Violet 151"), when known. */
    var tcgName: String? = null
    /** Asian-language card lists from TCGdex that are missing their secret rares. */
    var incomplete = false

    val year: Int get() = release.take(4).toIntOrNull() ?: 2020

    /** Cards in one booster pack (scannable cards, not code cards). */
    val packSize: Int
        get() = packSizeData ?: when {
            lang != "en" -> 5
            year <= 2002 -> 11
            year <= 2007 -> 9
            else -> 10
        }

    val mainCount: Int get() = cards.count { it.mainSet }

    /** Cards in collector-number order: the main set by number, then subsets (TG, GG, SV...). */
    private var sorted: List<Card>? = null

    /** Cards in collector-number order (again whenever cards were added). */
    val sortedCards: List<Card>
        get() = sorted?.takeIf { it.size == cards.size } ?: run {
        cards.sortedWith(compareBy<Card>({ it.number.takeWhile { c -> !c.isDigit() }.isNotEmpty() }, { it.number.takeWhile { c -> !c.isDigit() } },
            { it.number.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }, { it.order }))
        }.also { sorted = it }
    val isInternational get() = lang != "en"
    val displayName get() = name

    /** Value of one of every card (main printing). */
    val masterValue: Double get() = cards.sumOf { it.price }
}

/** Display names of card languages. */
val LANGUAGES = linkedMapOf(
    "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh-tw" to "Chinese (Trad.)",
    "zh-cn" to "Chinese (Simpl.)", "th" to "Thai", "id" to "Indonesian",
    "fr" to "French", "de" to "German", "it" to "Italian", "es" to "Spanish", "pt" to "Portuguese",
)

val LANGUAGE_FLAGS = mapOf(
    "en" to "EN", "ja" to "JP", "ko" to "KR", "zh-tw" to "TW", "zh-cn" to "CN", "th" to "TH", "id" to "ID",
    "fr" to "FR", "de" to "DE", "it" to "IT", "es" to "ES", "pt" to "PT",
)
