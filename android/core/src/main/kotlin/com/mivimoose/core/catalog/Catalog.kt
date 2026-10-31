package com.mivimoose.core.catalog

import java.text.Normalizer

/** Every set and card the app knows, with lookups by key, TCGplayer id and collector number. */
class Catalog(setList: List<CardSet>, val updated: String) {
    val sets: Map<String, CardSet> = LinkedHashMap<String, CardSet>().apply { setList.forEach { put(it.id, it) } }

    /** English first, then by release date, newest first. */
    val order: List<String> = setList.sortedWith(compareBy<CardSet> { it.lang != "en" }.thenByDescending { it.release }.thenBy { it.name }).map { it.id }

    val byKey: Map<String, Card> = HashMap<String, Card>().apply { setList.forEach { s -> s.cards.forEach { put(it.key, it) } } }

    val byTcgId: Map<Long, List<Card>> = HashMap<Long, MutableList<Card>>().apply {
        setList.forEach { s -> if (!s.firstEdition) s.cards.forEach { c -> c.tcgId?.let { getOrPut(it) { ArrayList() } += c } } }
    }

    /** Normalised collector number -> cards. */
    val byNumber: Map<String, List<Card>> = HashMap<String, MutableList<Card>>().apply {
        setList.forEach { s -> if (!s.firstEdition) s.cards.forEach { getOrPut(normNumber(it.number)) { ArrayList() } += it } }
    }

    /** Printed set code (upper case) -> sets. */
    val byCode: Map<String, List<CardSet>> = HashMap<String, MutableList<CardSet>>().apply {
        setList.forEach { s -> s.code?.let { getOrPut(it.uppercase()) { ArrayList() } += s } }
    }

    val cardCount get() = byKey.size

    fun card(key: String) = byKey[key]
    fun set(id: String) = sets[id]

    fun setsFor(lang: String?): List<CardSet> = order.mapNotNull { sets[it] }.filter { lang == null || it.lang == lang }

    fun languages(): List<String> = sets.values.map { it.lang }.distinct().sortedBy { LANGUAGES.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }

    fun search(q: String, setId: String? = null, lang: String? = null, limit: Int = 60): List<Card> {
        val terms = fold(q).split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val pool = if (setId != null) sets[setId]?.cards ?: emptyList() else byKey.values
        val out = ArrayList<Card>()
        for (c in pool) {
            if (lang != null && c.lang != lang) continue
            if (c.setId.endsWith("-1st") && setId == null) continue
            val hay = fold("${c.name} ${c.nameLocal ?: ""} ${c.number} ${c.setName} ${c.rarity}")
            if (terms.all { hay.contains(it) }) out += c
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

        /** Lower case, accents removed, punctuation to spaces. */
        fun fold(s: String): String {
            val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            return n.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }
    }
}
