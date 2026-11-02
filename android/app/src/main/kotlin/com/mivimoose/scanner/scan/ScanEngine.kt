package com.mivimoose.scanner.scan

import android.graphics.Bitmap
import android.util.Log
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.text.OcrLine
import com.mivimoose.core.text.TextCandidate
import com.mivimoose.core.text.TextMatch
import com.mivimoose.core.vision.AtlasHit
import com.mivimoose.core.vision.Fingerprint
import com.mivimoose.core.vision.Guide
import com.mivimoose.core.vision.Quad
import com.mivimoose.core.vision.Recognizer
import com.mivimoose.core.vision.Tracker
import com.mivimoose.scanner.Graph
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean

/** What the camera overlay shows, updated every analysed frame. */
data class LiveScan(
    /** Outline of the card being scanned as fractions of the analysed frame; null until
     *  something in the guide is known to be a card (not just card-shaped). */
    val quad: FloatArray? = null,
    val frameW: Int = 0,
    val frameH: Int = 0,
    /** searching, reading, locked */
    val state: String = "searching",
    val progress: Float = 0f,
    val hint: String = "",
    val leading: String? = null,
    val reading: String? = null,
    val pool: Int = 0,
    val ms: Long = 0,
)

/** A card the scanner confirmed. */
data class ScanResult(
    val scanId: Long,
    val card: Card,
    val variant: String,
    val price: Double,
    /** 0 normal, 1 hit, 2 big hit, 3 jackpot */
    val tier: Int,
    /** Copies owned before this scan. */
    val ownedBefore: Int,
    val added: Boolean,
    val mode: String,
    val ignored: Boolean = false,
    val packCount: Int = 0,
    val packSize: Int = 0,
    val packValue: Double = 0.0,
    val packDone: Boolean = false,
    val alternatives: List<Card> = emptyList(),
    val via: String = "",
    /** The pack this scan went into, and the open pack as it stood before it (for undoing). */
    val packId: Long? = null,
    val packBefore: OpenPack? = null,
)

/** The pack being opened (pack tracking on). */
class OpenPack(val id: Long, val setId: String, val keys: List<String>, val value: Double)

/** What the pack bar shows. */
data class PackInfo(val open: Boolean = false, val setId: String? = null, val count: Int = 0, val size: Int = 10, val value: Double = 0.0)

/**
 * What outlives the scanner screen, so looking at a card's details and coming back continues
 * the same session: the last card (not scanned again if it's still there), the open pack and
 * the set being scanned.
 */
private class ScanSession {
    val startedAt = System.currentTimeMillis()
    var lastName = ""
    var lastKey = ""
    var lastAt = 0L
    /** When the last scanned card was last recognised in the guide. */
    var lastSeenAt = 0L
    /** The guide has been empty since; the last time it was empty for a moment. */
    var emptySince = 0L
    var emptyAt = 0L
    /** Corrected with "wrong card?": not scanned as that card again until the guide was empty. */
    var rejectedKey = ""
    var rejectedAt = 0L
    var pack: OpenPack? = null
    val set = MutableStateFlow<String?>(null)
    val packInfo = MutableStateFlow(PackInfo())
}

/**
 * Turns camera frames into scans: per-frame recognition against the card pool, card text
 * (collector number, set code, name) read when vision alone isn't sure, a tracker that
 * confirms a card over a few frames, and the mode's rules (collection, pack, price check).
 */
class ScanEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "scanner", e) })
    private val session get() = SESSION
    private val rec = Recognizer(Graph.refs.index)
    private val tracker = Tracker()
    private val ocr = Ocr()
    private val ocrBusy = AtomicBoolean(false)
    private var lastOcr = 0L
    private var ocrScript = "en"
    @Volatile private var textCands: List<TextCandidate> = emptyList()
    @Volatile private var textAt = 0L
    /** The last text read was laid out like a card (a collector number along the bottom), or
     *  named what the card in the guide looks like. */
    @Volatile private var textCardLike = false
    /** What the built-in index thought the card in the guide is, over the last moments: only
     *  some frames show the card sharply enough to name it. Card key to (how sure, when). */
    private val looksLike = java.util.concurrent.ConcurrentHashMap<String, Pair<Float, Long>>()
    @Volatile private var looksLang: String? = null
    private var lastTop: Set<Int> = emptySet()
    private val fetched = HashSet<String>()
    /** What script the card's text was last read in ("latin", or "ja", "ko", "zh", "th"): a
     *  print in another language has the same artwork, so only its text tells them apart. */
    @Volatile private var textLang: String? = null
    @Volatile private var textLangAt = 0L
    private var langWaitSince = 0L
    /** The card the text keeps naming and the card's looks agree with, since when: without a
     *  connection to check its image, that has to do after a moment. */
    private var namedKey = ""
    private var namedSince = 0L

    private val _live = MutableStateFlow(LiveScan())
    val live: StateFlow<LiveScan> = _live
    private val _results = MutableSharedFlow<ScanResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ScanResult> = _results

    /** The set being scanned: chosen by hand in the scanner, else detected from the cards. */
    val set: StateFlow<String?> = session.set
    val pack: StateFlow<PackInfo> = session.packInfo
    /** Sets whose daily and pack prices were fetched by this scanner. */
    private val pricedSets = HashSet<String>()

    init {
        tracker.threshold = 1.5f
        tracker.minSightings = 2
        // The camera just came back: a card still in front of it counts as the same card.
        session.lastSeenAt = System.currentTimeMillis()
        publishPack()
        Graph.refs.loadAtlas()
        updatePool()
        warmUp()
    }

    /** The first frames and the first text read are the slowest: right after an install the code
     *  isn't optimised yet, and the reader's models aren't loaded. Get that done before the first
     *  card arrives, on something card-shaped that nothing can be scanned from. */
    private fun warmUp() {
        Lasting.launch {
            val t0 = System.currentTimeMillis()
            try {
                val frame = Mat(1920, 1080, org.opencv.core.CvType.CV_8UC1)
                org.opencv.core.Core.randu(frame, 0.0, 255.0)
                // (blurred: sharp noise is all edges, far more work than any real picture)
                Imgproc.GaussianBlur(frame, frame, org.opencv.core.Size(15.0, 15.0), 0.0)
                Imgproc.rectangle(frame, org.opencv.core.Point(250.0, 500.0), org.opencv.core.Point(830.0, 1310.0), org.opencv.core.Scalar(230.0), 12)
                // (a slow phone has better things to do with its time: the camera's frames)
                repeat(WARM_FRAMES) { if (System.currentTimeMillis() - t0 < WARM_MS) rec.analyze(frame) }
                frame.release()
                val bmp = Bitmap.createBitmap(400, 120, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawText("Pikachu 25/102", 20f, 70f, android.graphics.Paint().apply { textSize = 40f })
                }
                ocr.read(bmp, "en")
                // the Japanese reader is big: only for those scanning cards it reads
                val c = Graph.catalog.catalog.value
                val asian = Graph.prefs.value.scanLanguage.let { it == "ja" || it.startsWith("zh") || it == "ko" } ||
                    Graph.store.scans(10).any { s -> c?.card(s.cardKey)?.lang.let { it != null && it != "en" && it != "id" } }
                if (asian) ocr.read(bmp, "ja")
                bmp.recycle()
                Log.i(TAG, "warmed up in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Exception) { Log.w(TAG, "warm up", e) }
        }
    }

    /** Recognition pool: the set being scanned (all of it), plus the sets of recent scans. */
    fun updatePool() {
        val c = Graph.catalog.catalog.value ?: return
        val s = Graph.prefs.value
        val recent = Graph.store.scans(60).mapNotNull { c.card(it.cardKey)?.setId }.distinct()
        val current = s.focusSetId ?: session.set.value ?: recent.firstOrNull()
        session.set.value = current
        val sets = LinkedHashSet<String>()
        current?.let { sets += it }
        recent.take(2).forEach { sets += it }
        Graph.refs.setPool(c, sets.toList())
        // TCGplayer's prices for the set, and its pack's live price
        if (current != null && pricedSets.add(current)) c.set(current)?.let { set ->
            scope.launch { Graph.catalog.refreshSet(set.id, 360); Graph.prices.refreshPack(set) }
        }
    }

    /** The cards come from this set: load all of it, so the rest scan instantly. */
    private fun detect(setId: String) {
        if (Graph.prefs.value.focusSetId != null || session.set.value == setId) return
        session.set.value = setId
        Log.i(TAG, "set detected: $setId")
        updatePool()
    }

    /** Back to detecting the set from the cards (after one was chosen by hand). */
    fun autoSet() { session.set.value = null; updatePool() }

    /** One portrait greyscale frame. Runs on the camera's analysis thread. */
    fun onFrame(gray: Mat, t: Double) {
        val c = Graph.catalog.catalog.value ?: return
        val a = try { rec.analyze(gray) } catch (e: Exception) { Log.w(TAG, "analyze", e); return }
        val w = gray.cols(); val h = gray.rows()
        val sightings = HashMap<String, Float>()
        // Only the card held in the guide counts, not cards lying around it on the table.
        for (s in a.sightings) if (s.strength > 0f && Guide.holds(s.quad, w, h)) sightings[s.key] = maxOf(sightings[s.key] ?: 0f, s.strength)

        if (a.suggested.isNotEmpty()) {
            remember(c, a.suggested)
            fetchLikely(c, a.suggested)
        }
        // Card text: when there's a card but vision isn't sure (or the pool doesn't have it).
        val quad = a.sightings.firstOrNull { it.strength > 0f && Guide.holds(it.quad, w, h) }?.quad
            ?: a.outlines.firstOrNull { Guide.holds(it.quad, w, h) }?.quad
        val now = System.currentTimeMillis()
        val unsettled = checkLanguage(c, sightings, now)
        val visualSure = !unsettled && a.sightings.firstOrNull()?.let { it.strength >= 0.55f } ?: false
        // The guide stayed empty for a moment: whatever comes next is another card, even a second
        // copy of the last one. (Not one blurred frame: that can happen while a card is held.)
        if (quad != null || sightings.isNotEmpty()) session.emptySince = 0L
        else {
            if (session.emptySince == 0L) session.emptySince = now
            if (now - session.emptySince >= EMPTY_MS) { session.emptyAt = now; tracker.unlock(); looksLang = null; textLang = null }
        }
        if (sightings.containsKey(session.lastKey)) session.lastSeenAt = now
        // What's outlined on screen: only something known to be a card (recognised by sight,
        // or its printed text just read as a card's), never any card-shaped object.
        val textStrong = textCardLike && now - textAt < 1200 && (textCands.firstOrNull()?.score ?: 0f) >= 0.7f
        val shown = Guide.main(a, w, h)?.quad ?: quad?.takeIf { textStrong }
        // No outline (card filling the frame, corners cut off, low contrast): read the guide box,
        // where the scanner asks you to hold the card.
        val textQuad = quad?.takeIf { it.area > 0.06f * w * h } ?: Quad.guide(w, h, 0.72f)
        // A card in the guide is read over and over until it's known; the empty guide box only now and then.
        val onCard = textQuad === quad
        // (The card just scanned, still there, is read no further.)
        val scanned = tracker.locked != null && now - session.lastSeenAt < SETTLED_MS
        if (!visualSure && !scanned && Graph.prefs.value.ocr && (onCard || now - lastOcr > 450) && ocrBusy.compareAndSet(false, true)) {
            lastOcr = now
            val bmp = try { if (onCard) stripsBitmap(gray, textQuad) else cardBitmap(gray, textQuad) } catch (e: Exception) { ocrBusy.set(false); throw e }
            // not cancelled with the scanner: the text reader may still be using the image
            Lasting.launch { try { readText(bmp, c, onCard) } finally { bmp.recycle(); ocrBusy.set(false) } }
        }
        // Recent strong text reads count as sightings too.
        if (textCardLike && now - textAt < 1200) textCands.firstOrNull()?.let { tc ->
            val second = textCands.getOrNull(1)?.score ?: 0f
            if (tc.score >= 0.7f && tc.score - second >= 0.08f) {
                val st = ((tc.score - 0.55f) * 3f).coerceIn(0.3f, 0.95f)
                sightings[tc.card.key] = maxOf(sightings[tc.card.key] ?: 0f, st)
            } else if (tc.score >= 0.55f && looksLike.containsKey(tc.card.key)) {
                if (tc.card.key != namedKey) { namedKey = tc.card.key; namedSince = now }
                else if (now - namedSince > NAMED_MS) sightings[tc.card.key] = maxOf(sightings[tc.card.key] ?: 0f, 0.8f)
            }
        }
        val confirmed = tracker.update(t, sightings, quad != null)
        val prog = tracker.progress()
        val hint = when {
            shown == null -> ""
            shown.area < 0.12f * w * h -> "Move closer"
            a.glare > 0.08f -> "Glare: tilt the card a little"
            a.sharpness in 1f..40f -> "Hold steady"
            else -> ""
        }
        _live.value = LiveScan(
            quad = shown?.let { q -> FloatArray(8) { if (it % 2 == 0) q.p[it] / w else q.p[it] / h } },
            frameW = w, frameH = h,
            state = if (tracker.locked != null && sightings.containsKey(tracker.locked)) "locked" else if (prog != null) "reading" else "searching",
            progress = prog?.second ?: 0f, hint = hint, leading = prog?.first?.let { c.card(it)?.name },
            reading = textCands.firstOrNull()?.takeIf { textCardLike && now - textAt < 2000 && it.score >= 0.6f }?.let { "#${it.card.number} ${it.card.name}" },
            pool = Graph.refs.index.size, ms = a.millis,
        )
        if (confirmed != null) {
            val key = resolveReprint(c, confirmed)
            val alternatives = a.sightings.firstOrNull()?.cands?.mapNotNull { c.card(it.key) } ?: emptyList()
            val via = if (a.sightings.any { it.key == confirmed }) "vision" else "text"
            // the database and the pool's update don't hold up the camera
            Lasting.launch { try { confirm(c, key, alternatives, via) } catch (e: Exception) { Log.e(TAG, "confirm $confirmed", e) } }
        }
        // A blurry card in the guide: the camera may be focused on what's behind it
        if (quad != null && a.sharpness in 1f..BLURRY && now - focusAt > FOCUS_MS) { focusAt = now; onBlurry?.invoke() }
    }

    /** Called when a card in the guide is out of focus. */
    @Volatile var onBlurry: (() -> Unit)? = null
    private var focusAt = 0L

    /** Drops sightings of prints in the wrong language; true while the text has yet to say. A card
     *  of the set being scanned needs no such check, nor does anything once the reader had time. */
    private fun checkLanguage(c: Catalog, sightings: MutableMap<String, Float>, now: Long): Boolean {
        val want = Graph.prefs.value.scanLanguage
        if (want != "auto" || sightings.isEmpty()) {
            if (want != "auto") sightings.keys.removeAll { k -> c.card(k)?.let { it.lang != want && it.setId != session.set.value } == true }
            langWaitSince = 0L
            return false
        }
        val read = textLang?.takeIf { now - textLangAt < LANG_MS }
        var waiting = false
        for (k in sightings.keys.toList()) {
            val card = c.card(k) ?: continue
            if (card.setId == session.set.value) continue
            val latin = card.lang == "en" || card.lang == "id"
            when {
                // (the text may be from the card before: read this one)
                read == "latin" -> if (!latin) { sightings.remove(k); waiting = true }
                read != null -> if (latin) { sightings.remove(k); waiting = true }
                // only when an English print of it looks like this too: nothing else to mix it up with
                !latin && hasLatinTwin(c, card, now) -> {
                    if (langWaitSince == 0L) langWaitSince = now
                    if (now - langWaitSince < LANG_WAIT_MS) { sightings.remove(k); waiting = true }
                }
            }
        }
        if (!waiting) langWaitSince = 0L
        return waiting
    }

    /** The built-in index just ranked an English or Indonesian print of this card near the top. */
    private fun hasLatinTwin(c: Catalog, card: Card, now: Long): Boolean = looksLike.entries.any { (k, v) ->
        v.first >= 0.15f && now - v.second < LOOKS_MS && c.card(k)?.let { (it.lang == "en" || it.lang == "id") && it.name.equals(card.name, ignoreCase = true) } == true
    }

    /** The card's images most likely to be it, ready to be checked against it: the ones the
     *  built-in index keeps coming back to, a few at a time. */
    private fun fetchLikely(c: Catalog, all: List<AtlasHit>) {
        // Only the languages being collected: Indonesian 151 looks just like English 151, and
        // fetching it would make one card two. A card in another language is found by its text.
        val hits = all.take(12).filter { h -> preferred(c, h.keys) != null }
        if (hits.isEmpty()) return
        val top = hits.take(4).map { it.row }.toSet()
        val steady = hits.take(2).filter { it.row in lastTop }
        lastTop = top
        // only some frames show the card sharply: one clear answer is worth checking
        val clear = hits.take(1).filter { hits.size < 2 || it.score - hits[1].score >= CLEAR_LEAD }
        val want = (clear + steady).mapNotNull { h -> preferred(c, h.keys) }.distinct().filter { it !in Graph.refs.index.keys && it !in fetched }
        if (want.isEmpty()) return
        if (fetched.size > 400) fetched.clear()
        fetched += want
        Graph.refs.addCards(c, want)
    }

    /** Of the cards printed with one image, the one you most likely have. */
    private fun preferred(c: Catalog, keys: List<String>): String? {
        val s = Graph.prefs.value
        val cards = keys.mapNotNull { c.card(it) }.filter {
            it.setId == session.set.value || if (s.scanLanguage == "auto") it.lang in s.cardLanguages else it.lang == s.scanLanguage
        }
        return (cards.firstOrNull { it.setId in Graph.refs.poolSetIds } ?: cards.firstOrNull { it.lang == "en" } ?: cards.firstOrNull())?.key
    }

    private fun remember(c: Catalog, hits: List<AtlasHit>) {
        val now = System.currentTimeMillis()
        val top = hits.first().score
        for ((rank, h) in hits.withIndex()) {
            // not enough to confirm a card by name and looks alone: prints of one Pokémon look
            // alike. It decides which prints get their real images fetched and checked first.
            val v = when { top - h.score < ART_TIE -> 0.2f; rank < 5 -> 0.15f; else -> 0.1f }
            for (k in h.keys) {
                val old = looksLike[k]
                if (old == null || now - old.second > LOOKS_MS || v >= old.first) looksLike[k] = v to now
            }
        }
        if (hits.size < 2 || top - hits[1].score >= CLEAR_LEAD) looksLang = hits.first().keys.firstOrNull()?.let { c.card(it)?.lang }
        if (looksLike.size > 2000) looksLike.entries.removeIf { now - it.value.second > LOOKS_MS }
    }

    /** How much each card looks like what's in the guide, for weighing what the text says. */
    private fun looks(): Map<String, Float> {
        val now = System.currentTimeMillis()
        return looksLike.entries.filter { now - it.value.second < LOOKS_MS }.associate { it.key to it.value.first }
    }

    /** The two bands of the straightened card that say which card it is, the name along the top
     *  and the number along the bottom, enlarged: the reader needs tall characters and reads
     *  much faster without the attack text. */
    private fun stripsBitmap(gray: Mat, q: Quad): Bitmap {
        val top = Fingerprint.warpBand(gray, q, STRIP_W, STRIP_H, 0f, STRIP)
        val bottom = Fingerprint.warpBand(gray, q, STRIP_W, STRIP_H, 1f - STRIP, 1f)
        val both = Mat()
        org.opencv.core.Core.vconcat(listOf(top, bottom), both)
        val rgba = Mat()
        Imgproc.cvtColor(both, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bmp = Bitmap.createBitmap(both.cols(), both.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        top.release(); bottom.release(); both.release(); rgba.release()
        return bmp
    }

    /** Lines read off the strips, placed where they are on the card. */
    private fun unstrip(lines: List<OcrLine>): List<OcrLine> = lines.map { l ->
        fun y(v: Float) = if (v < 0.5f) v * 2f * STRIP else 1f - STRIP + (v - 0.5f) * 2f * STRIP
        OcrLine(l.text, floatArrayOf(l.box[0], y((l.box[1] + l.box[3]) / 2 - (l.box[3] - l.box[1]) / 2), l.box[2], y((l.box[1] + l.box[3]) / 2 + (l.box[3] - l.box[1]) / 2)))
    }

    /** The card straightened at high resolution for the text reader. */
    private fun cardBitmap(gray: Mat, q: Quad): Bitmap {
        val warped = Fingerprint.warp(gray, q, 630, 880)
        val rgba = Mat()
        Imgproc.cvtColor(warped, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bmp = Bitmap.createBitmap(630, 880, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        warped.release(); rgba.release()
        return bmp
    }

    /** onCard: the image is the card's outline straightened; else a rough crop around the guide,
     *  where positions only mean something relative to the text itself. */
    private suspend fun readText(bmp: Bitmap, c: Catalog, onCard: Boolean) {
        suspend fun read(b: Bitmap, script: String) = com.mivimoose.core.vision.Prof.time("ocr.$script") { ocr.read(b, script) }.let { if (onCard) unstrip(it) else TextMatch.fitted(it) }
        val want = Graph.prefs.value.scanLanguage
        // Automatic: Latin first (it also reads numbers on Asian cards); the Japanese reader
        // (Japanese + Latin) is tried when Latin finds no usable name, and kept while the cards
        // it reads really are in Japanese/Chinese/Korean.
        // (What the card looks like says which reader to try first.)
        val lookLang = looksLang
        if (want == "auto" && lookLang != null) ocrScript = if (lookLang == "ja" || lookLang.startsWith("zh")) "ja" else "en"
        val first = if (want == "auto") ocrScript else want
        var lines = read(bmp, first)
        var clues = TextMatch.clues(lines)
        var script = first
        if (want == "auto") {
            val weak = clues.names.none { n -> n.count { it.isLetter() } >= 3 }
            if (first == "en" && weak && lookLang == null) {
                val l2 = read(bmp, "ja"); val c2 = TextMatch.clues(l2)
                if (c2.script != null) { lines = l2; clues = c2; script = "ja"; ocrScript = "ja" }
            } else if (first == "ja" && clues.script == null) {
                ocrScript = "en"
            }
        }
        if (clues.numbers.isEmpty() && clues.names.isEmpty()) {
            // maybe the card is upside down
            val m = android.graphics.Matrix().apply { postRotate(180f) }
            val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            val l2 = read(flipped, script)
            flipped.recycle()
            TextMatch.clues(l2).takeIf { it.numbers.isNotEmpty() || it.names.isNotEmpty() }?.let { clues = it; lines = l2 }
        }
        val pool = Graph.refs.poolSetIds.toSet()
        val looksNow = looks()
        var cands = TextMatch.candidates(clues, c, pool, limit = 24, looks = looksNow)
        // an upside-down card: the same words, read from the other end
        val flip = TextMatch.clues(TextMatch.rotated(lines))
        if (TextMatch.cardLike(flip) && !TextMatch.cardLike(clues) || flip.names.isNotEmpty()) {
            val fc = TextMatch.candidates(flip, c, pool, limit = 24, looks = looksNow)
            if ((fc.firstOrNull()?.score ?: 0f) > (cands.firstOrNull()?.score ?: 0f) + 0.05f) { clues = flip; cands = fc }
        }
        Log.i(TAG, "text[$script] numbers=${clues.numbers.map { it.number + "/" + it.total }} names=${clues.names.take(3)} " +
            "codes=${clues.codes.take(8)} -> ${cands.take(3).map { it.card.key + " %.2f".format(it.score) }}")
        // Latin text only counts when it named a card: a Japanese card read as Latin gives gibberish
        val heard = clues.script ?: "latin".takeIf { cands.firstOrNull()?.takeIf { it.why.contains("name") }?.card?.lang?.let { l -> l == "en" || l == "id" } == true }
        if (heard != null) { textLang = heard; textLangAt = System.currentTimeMillis() }
        if (cands.isEmpty()) return
        textCardLike = TextMatch.cardLike(clues) || (looksNow[cands.first().card.key] ?: 0f) >= 0.15f
        textCands = cands
        textAt = System.currentTimeMillis()
        // The printed text names the set before the card is certain: start loading it now
        if (textCardLike && cands.first().score >= 0.6f) detect(cands.first().card.setId)
        // Let vision double-check the likely cards (more of them when the text was weak)
        // only in the leading card's language: a same-art print in another language would compete
        val n = if (cands.first().score >= 0.7f) 3 else 12
        val lang = cands.first().card.lang
        Graph.refs.addCards(c, cands.filter { it.card.lang == lang }.take(n).map { it.card.key })
    }

    /** Same artwork in two sets can't be told apart by sight: trust a fresh text read for which. */
    private fun resolveReprint(c: Catalog, key: String): String {
        val card = c.card(key) ?: return key
        val tc = textCands.firstOrNull()?.takeIf { System.currentTimeMillis() - textAt < 3000 && it.score >= 0.8f } ?: return key
        return if (tc.card.key != key && Catalog.fold(tc.card.name) == Catalog.fold(card.name)) tc.card.key else key
    }

    private fun tierOf(price: Double): Int {
        val s = Graph.prefs.value
        return when { price >= s.jackpotValue -> 3; price >= s.bigValue -> 2; price >= s.hitValue -> 1; else -> 0 }
    }

    @Synchronized
    private fun confirm(c: Catalog, key: String, cands: List<Card>, via: String) {
        val card = c.card(key) ?: return
        val now = System.currentTimeMillis()
        val ses = session
        val name = Catalog.fold(card.name)
        if (via != "manual") {
            if (key == ses.rejectedKey && ses.emptyAt < ses.rejectedAt) return
            // One physical card is scanned once. The same card again only counts once the guide
            // has been empty (or it wasn't recognised for a long time: a second copy swapped in
            // over something that always looks card-shaped, like a binder).
            if (ses.emptyAt < ses.lastAt) {
                val sameCard = key == ses.lastKey && now - ses.lastSeenAt < SAME_CARD_MS
                // the same Pokémon right after: a reprint or another language's print of that card
                val twin = key != ses.lastKey && name == ses.lastName && now - ses.lastSeenAt < TWIN_MS
                if (sameCard || twin) {
                    Log.i(TAG, "same card still in view, not scanned again: $key")
                    return
                }
            }
        }
        ses.lastName = name; ses.lastKey = key; ses.lastAt = now; ses.lastSeenAt = now
        Log.i(TAG, "confirmed $key via $via; text=${textCands.firstOrNull()?.let { it.card.key + " %.2f".format(it.score) }}")
        val s = Graph.prefs.value
        val store = Graph.store
        val variant = card.variants().first().id
        val price = card.priceFor(variant)
        val owned = store.ownedCount(key)
        val mode = if (s.autoAdd) "collect" else "check"
        var added = false
        var ignored = false
        var packSize = 0; var done = false
        val inPack = s.trackPacks && mode == "collect"
        val before = ses.pack
        if (inPack) {
            // a card from another set means a new pack
            if (before != null && before.setId != card.setId) { store.finishPack(before.id); ses.pack = null }
            val set = c.set(card.setId)
            packSize = set?.let { Graph.catalog.packs.packSize(it) } ?: 10
            val open = ses.pack ?: OpenPack(store.openPack(card.setId, set?.let { it.sealedPrice ?: Graph.catalog.packs.products(it).firstOrNull()?.price }), card.setId, emptyList(), 0.0).also { p ->
                // the row keeps what the pack sells for now, once that's known
                if (set != null) scope.launch { Graph.prices.refreshPack(set)?.let { q -> store.setPackCost(p.id, q.price) } }
            }
            if (key in open.keys) { ignored = true; ses.pack = open }
            else ses.pack = OpenPack(open.id, open.setId, open.keys + key, open.value + price)
            done = ses.pack!!.keys.size >= packSize
        }
        val pack = if (inPack) ses.pack else null
        if (!ignored && mode != "check") {
            val addIt = owned == 0 || s.dupePolicy == "warn"
            if (addIt) { store.add(key, variant, s.defaultCondition); added = true }
        }
        val scanId = if (ignored) -1 else store.addScan(key, variant, price, if (inPack) "pack" else mode, pack?.id, owned > 0, added)
        val r = ScanResult(scanId, card, variant, price, tierOf(price), owned, added, if (inPack) "pack" else mode, ignored, pack?.keys?.size ?: 0, packSize, pack?.value ?: 0.0, done,
            cands.filter { it.key != key }.distinctBy { it.key }.take(4), via, pack?.id, before)
        if (done) { pack?.let { store.finishPack(it.id) }; ses.pack = null }
        publishPack()
        feedback(r)
        _results.tryEmit(r)
        detect(card.setId)
        if (via == "text" || card.setId !in Graph.refs.poolSetIds) updatePool()
    }

    private fun publishPack() {
        val p = session.pack
        val set = p?.setId?.let { Graph.catalog.catalog.value?.set(it) }
        session.packInfo.value = PackInfo(p != null, p?.setId, p?.keys?.size ?: 0, set?.let { Graph.catalog.packs.packSize(it) } ?: 10, p?.value ?: 0.0)
    }

    private fun feedback(r: ScanResult) {
        val snd = Graph.sounds
        when {
            r.ignored -> snd.play("ignored")
            r.tier == 3 -> { snd.play("jackpot"); snd.buzz("heavy") }
            r.tier == 2 -> { snd.play("big"); snd.buzz("success") }
            r.tier == 1 -> { snd.play("hit"); snd.buzz("success") }
            r.ownedBefore > 0 -> { snd.play("dupe"); snd.buzz("warn") }
            else -> { snd.play("new"); snd.buzz("tick") }
        }
        if (r.packDone) scope.launch { kotlinx.coroutines.delay(900); snd.play("pack") }
    }

    /** Undo a scan: remove it and the copy it added, and put the pack back as it was. It isn't
     *  scanned again until the card has been taken away. */
    @Synchronized
    fun undo(r: ScanResult) {
        if (r.scanId > 0) Graph.store.deleteScan(r.scanId)
        if (r.added) Graph.store.removeOne(r.card.key, r.variant)
        if (r.mode == "pack" && !r.ignored) restorePack(r)
        tracker.unlock()
        Graph.sounds.play("undo")
    }

    private fun restorePack(r: ScanResult) {
        val now = session.pack
        // "Next pack" may have been tapped since: then this pack just has one card less
        val untouched = if (r.packDone) now == null else now?.id == r.packId
        if (!untouched) return
        val before = r.packBefore
        if (before?.id != r.packId) r.packId?.let { Graph.store.deletePack(it) }     // this scan opened it: it's empty now
        before?.let { Graph.store.reopenPack(it.id) }
        session.pack = before
        publishPack()
    }

    /** The scanner picked the wrong card: replace it with the right one. */
    @Synchronized
    fun correct(r: ScanResult, right: Card) {
        undo(r)
        val c = Graph.catalog.catalog.value ?: return
        session.rejectedKey = r.card.key; session.rejectedAt = System.currentTimeMillis()
        tracker.reset()
        confirm(c, right.key, emptyList(), "manual")
    }

    /** The scan at a new price (its live price arrived, or another printing was chosen). */
    private fun priced(r: ScanResult, variant: String, price: Double): ScanResult {
        if (r.scanId > 0) Graph.store.setScanVariant(r.scanId, variant, price)
        val diff = price - r.price
        val p = session.pack
        if (r.mode == "pack" && !r.ignored && p != null && p.id == r.packId) {
            session.pack = OpenPack(p.id, p.setId, p.keys, p.value + diff)
            publishPack()
        }
        return r.copy(variant = variant, price = price, tier = tierOf(price), packValue = if (r.mode == "pack") r.packValue + diff else r.packValue)
    }

    /** The card's live price arrived after the scan. */
    @Synchronized
    fun reprice(r: ScanResult, price: Double): ScanResult = priced(r, r.variant, price)

    /** Change the printing of a scan (e.g. it was a reverse holo). */
    @Synchronized
    fun setVariant(r: ScanResult, variant: String): ScanResult {
        if (r.added) { Graph.store.removeOne(r.card.key, r.variant); Graph.store.add(r.card.key, variant, Graph.prefs.value.defaultCondition) }
        return priced(r, variant, r.card.priceFor(variant))
    }

    /** Add a scan that wasn't added (duplicate skipped / price check). */
    @Synchronized
    fun addAnyway(r: ScanResult): ScanResult {
        if (r.added) return r
        Graph.store.add(r.card.key, r.variant, Graph.prefs.value.defaultCondition)
        if (r.scanId > 0) Graph.store.setScanAdded(r.scanId, true)
        Graph.sounds.play("select")
        return r.copy(added = true)
    }

    @Synchronized
    fun nextPack() {
        session.pack?.let { Graph.store.finishPack(it.id) }
        session.pack = null
        publishPack()
        Graph.sounds.play("deal")
    }

    /** Forget the current card and text reads (between benchmark sequences). */
    fun resetForBench() {
        tracker.reset(); textCands = emptyList(); textAt = 0L; textCardLike = false; looksLike.clear(); looksLang = null; lastTop = emptySet(); namedKey = ""; namedSince = 0L; fetched.clear(); textLang = null; textLangAt = 0L; langWaitSince = 0L
        session.lastName = ""; session.lastKey = ""; session.lastAt = 0L; session.emptyAt = 0L; session.emptySince = 0L; session.rejectedKey = ""
    }

    fun close() { scope.cancel(); ocr.close() }

    companion object {
        private const val TAG = "ScanEngine"
        private val SESSION = ScanSession()
        /** How long the guide must stay empty before the same card can be scanned again. */
        private const val EMPTY_MS = 1200L
        private const val SAME_CARD_MS = 5000L
        private const val TWIN_MS = 3000L
        /** Artwork scores this close can't tell two images apart. */
        private const val ART_TIE = 0.03f
        /** The best image this far ahead of the next one: most likely the card itself. */
        private const val CLEAR_LEAD = 0.05f
        private const val LOOKS_MS = 1200L
        private const val NAMED_MS = 1800L
        private const val WARM_FRAMES = 3
        private const val WARM_MS = 500L
        private const val SETTLED_MS = 400L
        private const val BLURRY = 40f
        private const val FOCUS_MS = 1500L
        private const val LANG_MS = 1500L
        private const val LANG_WAIT_MS = 1000L
        /** The bands read off a card: this share of its height at either end, enlarged. */
        private const val STRIP = 0.17f
        private const val STRIP_W = 860
        private const val STRIP_H = 1200
        /** Outlives the scanner screen: a text read still holds its image, and a scan must get saved. */
        private val Lasting = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "reader", e) })
        /** When this session of scanning began (for the strip of its scans). */
        val sessionStart get() = SESSION.startedAt
    }
}
