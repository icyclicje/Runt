package com.mivimoose.core.text

import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.catalog.CardSet
import kotlin.math.max
import kotlin.math.min

/** A line of text read off a straightened card; box is x0,y0,x1,y1 as fractions of the card. */
class OcrLine(val text: String, val box: FloatArray) {
    val height get() = box[3] - box[1]
    val top get() = box[1]
    val bottom get() = box[3]
}

class CollectorNumber(val number: String, val total: String?, val bottom: Boolean)

/** What the card's printed text says, as far as it could be read. */
class TextClues(
    val numbers: List<CollectorNumber>,
    val codes: Set<String>,
    val names: List<String>,
    val hp: Int?,
    val script: String?,          // "ja", "ko", "zh", "th" or null for Latin
    val raw: String,
) {
    val isEmpty get() = numbers.isEmpty() && names.isEmpty() && codes.isEmpty()
}

class TextCandidate(val card: Card, val score: Float, val why: String)

object TextMatch {
    private val NUM_RE = Regex("(?<![A-Za-z0-9])([A-Z]{0,4}\\s?\\d{1,3}[a-z]?)\\s*[/|\\\\]\\s*([A-Z]{0,4}\\s?\\d{1,3})(?![0-9])")
    private val PROMO_RE = Regex("(?<![A-Za-z0-9])(SWSH|SVP|SV-P|SM|XY|BW|DP|HGSS|MEP|ME-P|SVEN)\\s?-?(\\d{1,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val HP_RE = Regex("(?:HP\\s*(\\d{2,3}))|(?:(\\d{2,3})\\s*HP)", RegexOption.IGNORE_CASE)
    private val WORD_RE = Regex("[A-Za-z0-9-]{2,6}")
    private val NOISE = Regex("^(basic|stage ?[12]|evolves from.*|hp ?\\d+|\\d+ ?hp|v ?union|tera|ability|trainer|item|supporter|stadium|pokemon tool|energy|たね|1進化|2進化|進化)$", RegexOption.IGNORE_CASE)

    fun clues(lines: List<OcrLine>): TextClues {
        val numbers = ArrayList<CollectorNumber>()
        val codes = LinkedHashSet<String>()
        var hp: Int? = null
        for (l in lines) {
            val t = fixDigits(l.text)
            for (m in NUM_RE.findAll(t)) {
                val num = m.groupValues[1].replace(" ", "")
                val total = m.groupValues[2].replace(" ", "")
                numbers += CollectorNumber(num, total, l.top > 0.7f)
            }
            for (m in PROMO_RE.findAll(l.text)) {
                val prefix = m.groupValues[1].uppercase().replace("-", "")
                numbers += CollectorNumber(prefix + m.groupValues[2], null, l.top > 0.7f)
                numbers += CollectorNumber(m.groupValues[2], prefix, l.top > 0.7f)
            }
            if (hp == null) HP_RE.find(l.text)?.let { hp = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
            if (l.top > 0.75f) for (w in WORD_RE.findAll(l.text)) codes += w.value.uppercase()
        }
        numbers.sortByDescending { it.bottom }
        val names = lines.filter { it.top < 0.2f && it.text.length >= 2 }
            .sortedByDescending { it.height }
            .flatMap { l -> listOf(l.text) + l.text.split(Regex("\\s{2,}|HP\\s*\\d+", RegexOption.IGNORE_CASE)) }
            .map { it.trim() }.filter { it.length >= 2 && !NOISE.matches(it) }.distinct().take(6)
        val all = lines.joinToString("\n") { it.text }
        return TextClues(numbers, codes, names, hp, scriptOf(all), all)
    }

    /** Common OCR slips inside collector numbers: O->0, l/I->1 when between digits. */
    private fun fixDigits(s: String): String {
        val c = s.toCharArray()
        for (i in c.indices) {
            val prevDigit = i > 0 && c[i - 1].isDigit()
            val nextDigit = i + 1 < c.size && c[i + 1].isDigit()
            if ((prevDigit || nextDigit) && c[i] in "Oo") c[i] = '0'
        }
        return String(c)
    }

    fun scriptOf(s: String): String? {
        var kana = 0; var hangul = 0; var han = 0; var thai = 0; var latin = 0
        for (ch in s) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.THAI -> thai++
                Character.UnicodeScript.LATIN -> latin++
                else -> {}
            }
        }
        return when {
            kana >= 2 -> "ja"
            hangul >= 2 -> "ko"
            thai >= 2 -> "th"
            han >= 3 -> "zh"
            else -> null
        }
    }

    /** Similarity of a card's name to the best name line, 0..1. */
    fun nameScore(card: Card, names: List<String>): Float {
        if (names.isEmpty()) return 0f
        val targets = listOfNotNull(card.name, card.nameLocal).map(::squash).filter { it.isNotEmpty() }
        var best = 0f
        for (n in names) {
            val q = squash(n)
            if (q.length < 2) continue
            for (t in targets) {
                val s = when {
                    q == t -> 1f
                    t.length >= 4 && q.contains(t) -> 0.95f
                    q.length >= 4 && t.contains(q) -> 0.8f * q.length / t.length + 0.15f
                    else -> 1f - levenshtein(q, t).toFloat() / max(q.length, t.length)
                }
                best = max(best, s)
            }
        }
        return best
    }

    private fun squash(s: String) = Catalog.fold(s).replace(" ", "")

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun langMatches(set: CardSet, script: String?): Boolean = when (script) {
        null -> set.lang !in setOf("ja", "ko", "zh-tw", "zh-cn", "th")
        "zh" -> set.lang.startsWith("zh") || set.lang == "ja"
        else -> set.lang == script
    }

    /**
     * Cards the text could belong to, best first. score ~ 1 means number, set code and
     * name all agree; ~0.6 a number plus a plausible name.
     */
    fun candidates(clues: TextClues, catalog: Catalog, preferredSets: Set<String> = emptySet(), limit: Int = 8): List<TextCandidate> {
        if (clues.isEmpty) return emptyList()
        val out = HashMap<String, TextCandidate>()
        fun offer(c: Card, score: Float, why: String) {
            val old = out[c.key]
            if (old == null || old.score < score) out[c.key] = TextCandidate(c, score, why)
        }
        val codeSets = clues.codes.flatMap { catalog.byCode[it] ?: emptyList() }.map { it.id }.toSet()
        for (n in clues.numbers) {
            val pool = catalog.byNumber[Catalog.normNumber(n.number)] ?: continue
            for (c in pool) {
                val set = catalog.sets[c.setId] ?: continue
                val total = n.total
                val totalOk = when {
                    total == null -> false
                    total.all { it.isDigit() } -> set.printed == total.toInt() || set.cards.count { it.mainSet } == total.toInt()
                    else -> set.cards.any { Catalog.normNumber(it.number) == Catalog.normNumber(total) }
                }
                val codeOk = set.id in codeSets
                if (!totalOk && !codeOk && n.total != null && n.total.all { it.isDigit() }) continue
                val ns = nameScore(c, clues.names)
                var score = 0.35f + (if (totalOk) 0.15f else 0f) + (if (codeOk) 0.3f else 0f) + 0.35f * ns
                if (!langMatches(set, clues.script)) score -= 0.25f
                if (c.setId in preferredSets) score += 0.05f
                if (clues.hp != null && c.kind == "P" && ns > 0.6f) score += 0.03f
                offer(c, score.coerceAtMost(1f), listOfNotNull("number", if (totalOk) "total" else null, if (codeOk) "set code" else null, if (ns > 0.7f) "name" else null).joinToString("+"))
            }
        }
        if (out.isEmpty() && clues.names.isNotEmpty()) {
            // No readable number: names only, within the set codes or preferred sets.
            val scope = (codeSets + preferredSets).mapNotNull { catalog.sets[it] }
            for (s in scope) for (c in s.cards) {
                val ns = nameScore(c, clues.names)
                if (ns >= 0.8f) offer(c, 0.25f + 0.3f * ns + (if (s.id in codeSets) 0.15f else 0f), "name")
            }
        }
        return out.values.sortedByDescending { it.score }.take(limit)
    }
}
