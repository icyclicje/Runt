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
    /** "199/165", "TG05/TG30", "GG12/GG70", "SV107/SV122"; a language mark ("EN 199") is not part of it. */
    private val NUM_RE = Regex("(?<![A-Za-z0-9])((?:TG|GG|SV|RC|SH|H)?\\d{1,3}[a-z]?)\\s*[/|\\\\]\\s*((?:TG|GG|SV|RC|SH|H)?\\d{1,3})(?![0-9])")
    private val PROMO_RE = Regex("(?<![A-Za-z0-9])(SWSH|SVP|SV-P|SM|XY|BW|DP|HGSS|MEP|ME-P|SVEN)\\s?-?(\\d{1,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val HP_RE = Regex("(?:HP\\s*(\\d{2,3}))|(?:(\\d{2,3})\\s*HP)", RegexOption.IGNORE_CASE)
    private val WORD_RE = Regex("[A-Za-z0-9-]{2,6}")
    private val NOISE = Regex("^(basic|stage ?[12]|evolves from.*|hp ?\\d+|\\d+ ?hp|v ?union|tera|ability|trainer|item|supporter|stadium|pokemon tool|energy|たね|1進化|2進化|進化)$", RegexOption.IGNORE_CASE)

    /** The same lines as they sit on the card turned upside down (the reader reads rotated
     *  text, so an upside-down card gives the right words in mirrored places). */
    fun rotated(lines: List<OcrLine>): List<OcrLine> = lines.map { l ->
        OcrLine(l.text, floatArrayOf(1f - l.box[2], 1f - l.box[3], 1f - l.box[0], 1f - l.box[1]))
    }

    /** Lines read off a rough crop around the guide, where the card can be any size: positions
     *  relative to the text itself, whose first and last lines are where a card's are. */
    fun fitted(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val y0 = lines.minOf { it.box[1] }
        val span = lines.maxOf { it.box[3] } - y0
        if (span < 0.3f) return lines
        return lines.map { l -> OcrLine(l.text, floatArrayOf(l.box[0], (l.box[1] - y0) / span, l.box[2], (l.box[3] - y0) / span)) }
    }

    /** Laid out like one card: one collector number (two with a promo's), along the bottom. */
    fun cardLike(c: TextClues): Boolean = c.numbers.count { it.bottom } in 1..2 && c.numbers.distinctBy { it.number }.size <= 3

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
        }
        // The set code is printed next to the collector number. Without one read, only the
        // very bottom of the card counts: the rules text above it is full of short words
        val numberLines = lines.filter { l -> l.top > 0.75f && NUM_RE.containsMatchIn(fixDigits(l.text)) }
        for (l in lines) {
            val onNumberLine = numberLines.any { n -> kotlin.math.abs((l.top + l.bottom) / 2 - (n.top + n.bottom) / 2) < 0.015f }
            if (onNumberLine || (numberLines.isEmpty() && l.top > 0.92f)) for (w in WORD_RE.findAll(l.text)) {
                val u = w.value.uppercase()
                codes += u
                // "MEW EN" read without its space
                if (u.length >= 4 && u.endsWith("EN")) codes += u.dropLast(2)
            }
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

    /** Same length, one character different (a typical OCR slip in a collector number). */
    private fun oneOff(a: String, b: String): Boolean {
        if (a.length != b.length || a == b) return false
        var d = 0
        for (i in a.indices) if (a[i] != b[i] && ++d > 1) return false
        return d == 1
    }

    /** Cards whose name looks like one of the read name lines. */
    private fun byName(catalog: Catalog, names: List<String>): List<Card> {
        val out = LinkedHashSet<Card>()
        for (n in names.take(4)) {
            val q = Catalog.squash(n)
            if (q.length < 3) continue
            for ((k, cards) in catalog.byName) {
                // the read line may hold the name plus other words ("Mr. Mime HP 90")
                val contains = k.length >= 5 && q.contains(k)
                if (!contains) {
                    if (kotlin.math.abs(k.length - q.length) > 3 || k[0] != q[0]) continue
                    if (1f - levenshtein(q, k).toFloat() / max(q.length, k.length) < 0.75f) continue
                }
                out += cards
            }
            if (out.size > 400) break
        }
        return out.toList()
    }

    /**
     * Cards the text could belong to, best first. Name, collector number (exact or one
     * character off), printed set size and set code are scored together, so one misread
     * field doesn't lead astray: ~1 means everything agrees, ~0.6 name plus set size.
     * `looks`: how much each card's artwork looks like what's in the guide adds to its score.
     */
    fun candidates(clues: TextClues, catalog: Catalog, preferredSets: Set<String> = emptySet(), limit: Int = 8, looks: Map<String, Float> = emptyMap()): List<TextCandidate> {
        if (clues.isEmpty) return emptyList()
        val codeSets = clues.codes.flatMap { catalog.byCode[it] ?: emptyList() }.map { it.id }.toSet()
        val readNums = clues.numbers.map { Catalog.normNumber(it.number) }.distinct()
        // A card prints its number along the bottom; a number elsewhere (a checklist, a price
        // list, a box) is weaker evidence
        val bottomNums = clues.numbers.filter { it.bottom }.map { Catalog.normNumber(it.number) }.toSet()
        val rawNums = clues.numbers.map { it.number.uppercase() }.distinct()
        val totals = clues.numbers.mapNotNull { it.total }.distinct()
        val pool = LinkedHashSet<Card>()
        for (n in readNums) {
            catalog.byNumber[n]?.let { pool += it }
            // one digit misread: only in sets whose printed size was read
            if (totals.isNotEmpty() && n.any { it.isDigit() }) {
                for (i in n.indices) if (n[i].isDigit()) for (d in '0'..'9') {
                    if (d == n[i]) continue
                    val v = Catalog.normNumber(n.substring(0, i) + d + n.substring(i + 1))
                    catalog.byNumber[v]?.filter { c -> catalog.sets[c.setId]?.let { totalOk(it, totals) } == true }?.let { pool += it }
                }
            }
        }
        pool += byName(catalog, clues.names)
        val out = ArrayList<TextCandidate>()
        for (c in pool) {
            val set = catalog.sets[c.setId] ?: continue
            val ns = nameScore(c, clues.names)
            val cn = Catalog.normNumber(c.number)
            val numExact = cn in readNums
            // compare digit for digit at the printed width ("022" read for "122")
            val numNear = !numExact && rawNums.any { r -> oneOff(r, c.number.uppercase().padStart(r.length, '0')) }
            val tOk = totals.isNotEmpty() && totalOk(set, totals)
            val codeOk = set.id in codeSets
            if (!numExact && !numNear && ns < 0.75f) continue
            val atBottom = cn in bottomNums
            var score = 0.40f * ns + (if (numExact) (if (atBottom) 0.25f else 0.06f) else if (numNear) 0.10f else 0f) + (if (tOk) 0.15f else 0f) + (if (codeOk) 0.20f else 0f)
            if (ns >= 0.9f && numExact && atBottom) score += 0.10f
            if (totals.isNotEmpty() && !tOk && !codeOk) score -= 0.15f
            if (!langMatches(set, clues.script)) score -= 0.25f
            // Latin text can't tell English from Indonesian: assume English
            if (clues.script == null && set.lang == "en") score += 0.03f
            if (clues.script == null && set.lang == "id") score -= 0.08f
            if (c.setId in preferredSets) score += 0.05f
            val look = looks[c.key] ?: 0f
            score += look
            // ties (e.g. a name read without its number) lean towards newer sets: what people open
            score += 0.02f * ((set.year - 1999).coerceIn(0, 27) / 27f)
            val why = listOfNotNull(if (ns >= 0.75f) "name" else null, if (numExact) "number" else if (numNear) "number~" else null,
                if (tOk) "set size" else null, if (codeOk) "set code" else null, if (look > 0f) "art" else null).joinToString("+")
            out += TextCandidate(c, score.coerceIn(0f, 1f), why)
        }
        return out.sortedByDescending { it.score }.distinctBy { it.card.key }.take(limit)
    }

    private fun totalOk(set: CardSet, totals: List<String>): Boolean = totals.any { t ->
        if (t.all { it.isDigit() }) t.toInt().let { n -> set.printed == n || set.cards.count { it.mainSet } == n }
        else set.cards.any { Catalog.normNumber(it.number) == Catalog.normNumber(t) }
    }
}
