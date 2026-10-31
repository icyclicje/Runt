package com.mivimoose.core.catalog

import java.text.Normalizer

/** Every set and card the app knows, with lookups by key, TCGplayer id and collector number. */
class Catalog(setList: List<CardSet>, val updated: String) {
    val sets: Map<String, CardSet> = LinkedHashMap<String, CardSet>().apply { setList.forEach { put(it.id, it) } }

    /** By language (English first), then series (newest series first, promo packs last), then newest first. */
    val order: List<String> = run {
        val promo = "Rare & Promo Packs"
        val seriesRank = setList.filter { it.series != promo }.groupBy { it.lang to it.series }.mapValues { (_, v) -> v.maxOf { it.release } }
        val langs = LANGUAGES.keys.toList()
        setList.sortedWith(
            compareBy<CardSet> { langs.indexOf(it.lang).let { i -> if (i < 0) 99 else i } }
                .thenBy { it.series == promo }
                .thenByDescending { seriesRank[it.lang to it.series] ?: "" }
                .thenByDescending { it.release }.thenBy { it.name },
        ).map { it.id }
    }

    val byKey: Map<String, Card> = HashMap<String, Card>().apply { setList.forEach { s -> s.cards.forEach { put(it.key, it) } } }

    val byTcgId: Map<Long, List<Card>> = HashMap<Long, MutableList<Card>>().apply {
        setList.forEach { s -> if (!s.firstEdition) s.cards.forEach { c -> c.tcgId?.let { getOrPut(it) { ArrayList() } += c } } }
    }

    /** Normalised collector number -> cards. */
    val byNumber: Map<String, List<Card>> by lazy { HashMap<String, MutableList<Card>>().apply {
        setList.forEach { s -> if (!s.firstEdition) s.cards.forEach { getOrPut(normNumber(it.number)) { ArrayList() } += it } }
    } }

    /** Squashed name (lower case, letters and digits only) -> cards, for reading names off cards. */
    val byName: Map<String, List<Card>> by lazy { HashMap<String, MutableList<Card>>().apply {
        setList.forEach { s ->
            if (!s.firstEdition) s.cards.forEach { c ->
                for (n in listOfNotNull(c.name, c.nameLocal)) {
                    val k = squash(n)
                    if (k.length >= 2) getOrPut(k) { ArrayList() } += c
                }
            }
        }
    } }

    /** Printed set code (upper case) -> sets. */
    val byCode: Map<String, List<CardSet>> = HashMap<String, MutableList<CardSet>>().apply {
        setList.forEach { s -> s.code?.let { getOrPut(it.uppercase()) { ArrayList() } += s } }
    }

    val cardCount get() = byKey.size

    fun card(key: String) = byKey[key]
    fun set(id: String) = sets[id]

    fun setsFor(lang: String?): List<CardSet> = order.mapNotNull { sets[it] }.filter { lang == null || it.lang == lang }

    fun languages(): List<String> = sets.values.map { it.lang }.distinct().sortedBy { LANGUAGES.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }

    private val allCards: List<Card> by lazy { byKey.values.filter { !it.setId.endsWith("-1st") } }

    /** Folded "name local-name number set rarity" per card, built once (call off the main thread). */
    private val hay: Array<String> by lazy {
        Array(allCards.size) { i -> allCards[i].let { c -> fold("${c.name} ${c.nameLocal ?: ""} ${c.number} ${c.setName} ${c.rarity}") } }
    }

    /** Builds the search and text-reading indexes (slow: call off the main thread, after showing the catalog). */
    fun warmSearch() { hay.size; byName.size; byNumber.size }

    fun search(q: String, setId: String? = null, lang: String? = null, limit: Int = 60): List<Card> {
        val terms = fold(q).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val out = ArrayList<Card>()
        val h = hay
        for ((i, c) in allCards.withIndex()) {
            if (lang != null && c.lang != lang) continue
            if (setId != null && c.setId != setId) continue
            if (terms.all { h[i].contains(it) }) out += c
        }
        val first = terms[0]
        out.sortWith(compareBy<Card> { !fold(it.name).startsWith(first) }.thenByDescending { it.price })
        return out.take(limit)
    }

    companion object {
        private val NUM_RE = Regex("^([A-Z]*?)0*(\\d+)([A-Z]*)$")

        /** "001" -> "1", "TG05" -> "TG5", "sv013" -> "SV13". */
        fun normNumber(n: String): String {
            val s = n.uppercase().replace(" ", "")
            val m = NUM_RE.matchEntire(s) ?: return s
            return m.groupValues[1] + m.groupValues[2].trimStart('0').ifEmpty { "0" } + m.groupValues[3]
        }

        fun squash(s: String) = fold(s).replace(" ", "")

        /** Lower case, accents removed, punctuation to spaces. */
        private val MARKS = Regex("\\p{Mn}+")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun fold(s: String): String {
            val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            return n.replace(NON_WORD, " ").trim()
        }
    }
}
