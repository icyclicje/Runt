package com.mivimoose.scanner.scan

import android.graphics.Bitmap
import android.util.Log
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.text.TextCandidate
import com.mivimoose.core.text.TextMatch
import com.mivimoose.core.vision.Fingerprint
import com.mivimoose.core.vision.Guide
import com.mivimoose.core.vision.Quad
import com.mivimoose.core.vision.Recognizer
import com.mivimoose.core.vision.Tracker
import com.mivimoose.scanner.Graph
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
)

/**
 * Turns camera frames into scans: per-frame recognition against the card pool, card text
 * (collector number, set code, name) read when vision alone isn't sure, a tracker that
 * confirms a card over a few frames, and the mode's rules (collection, pack, price check).
 */
class ScanEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rec = Recognizer(Graph.refs.index)
    private val tracker = Tracker()
    private val ocr = Ocr()
    private val ocrBusy = AtomicBoolean(false)
    private var lastOcr = 0L
    private var ocrScript = "en"
    @Volatile private var textCands: List<TextCandidate> = emptyList()
    @Volatile private var textAt = 0L
    /** The last text read was laid out like a card (a collector number along the bottom). */
    @Volatile private var textCardLike = false

    private val _live = MutableStateFlow(LiveScan())
    val live: StateFlow<LiveScan> = _live
    private val _results = MutableSharedFlow<ScanResult>(extraBufferCapacity = 8)
    val results: SharedFlow<ScanResult> = _results

    @Volatile var paused = false
    /** Printing to use for the next card ("reverse", "pokeball"...), then back to normal. */
    @Volatile var nextVariant: String? = null
    private var packId: Long? = null
    private val packKeys = ArrayList<String>()
    private var packValue = 0.0
    private var packSet: String? = null

    /** The set being scanned: chosen by hand in the scanner, else detected from the cards. */
    private val _set = MutableStateFlow<String?>(null)
    val set: StateFlow<String?> = _set

    init {
        tracker.threshold = 1.5f
        tracker.minSightings = 2
        updatePool()
    }

    /** Recognition pool: the set being scanned (all of it), plus the sets of recent scans. */
    fun updatePool() {
        val c = Graph.catalog.catalog.value ?: return
        val s = Graph.prefs.value
        val recent = Graph.store.scans(60).mapNotNull { c.card(it.cardKey)?.setId }.distinct()
        _set.value = s.focusSetId ?: _set.value ?: recent.firstOrNull()
        val sets = LinkedHashSet<String>()
        _set.value?.let { sets += it }
        recent.take(2).forEach { sets += it }
        Graph.refs.setPool(c, sets.toList())
    }

    /** The cards come from this set: load all of it, so the rest scan instantly. */
    private fun detect(setId: String) {
        if (Graph.prefs.value.focusSetId != null || _set.value == setId) return
        _set.value = setId
        Log.i(TAG, "set detected: $setId")
        updatePool()
        // its pack's live price, for pack tracking
        Graph.catalog.catalog.value?.set(setId)?.let { s -> scope.launch { Graph.catalog.refreshSet(s.id, 360); Graph.prices.refreshPack(s) } }
    }

    /** Back to detecting the set from the cards (after one was chosen by hand). */
    fun autoSet() { _set.value = null; updatePool() }

    /** One portrait greyscale frame. Runs on the camera's analysis thread. */
    fun onFrame(gray: Mat, t: Double) {
        if (paused) return
        val c = Graph.catalog.catalog.value ?: return
        val a = try { rec.analyze(gray) } catch (e: Exception) { Log.w(TAG, "analyze", e); return }
        val w = gray.cols(); val h = gray.rows()
        val sightings = HashMap<String, Float>()
        // Only the card held in the guide counts, not cards lying around it on the table.
        for (s in a.sightings) if (s.strength > 0f && Guide.holds(s.quad, w, h)) sightings[s.key] = maxOf(sightings[s.key] ?: 0f, s.strength)

        // Card text: when there's a card but vision isn't sure (or the pool doesn't have it).
        val quad = a.sightings.firstOrNull { it.strength > 0f && Guide.holds(it.quad, w, h) }?.quad
            ?: a.outlines.firstOrNull { Guide.holds(it.quad, w, h) }?.quad
        val visualSure = a.sightings.firstOrNull()?.let { it.strength >= 0.55f } ?: false
        val now = System.currentTimeMillis()
        // What's outlined on screen: only something known to be a card (recognised by sight,
        // or its printed text just read as a card's), never any card-shaped object.
        val textStrong = textCardLike && now - textAt < 1200 && (textCands.firstOrNull()?.score ?: 0f) >= 0.7f
        val shown = Guide.main(a, w, h)?.quad ?: quad?.takeIf { textStrong }
        // No outline (card filling the frame, corners cut off, low contrast): read the guide box,
        // where the scanner asks you to hold the card.
        val textQuad = quad?.takeIf { it.area > 0.06f * w * h } ?: Quad.guide(w, h, 0.72f)
        val coldStart = Graph.refs.index.size == 0
        if (!visualSure && Graph.prefs.value.ocr && now - lastOcr > (if (coldStart) 300 else 450) && ocrBusy.compareAndSet(false, true)) {
            lastOcr = now
            val bmp = cardBitmap(gray, textQuad)
            scope.launch { try { readText(bmp, c) } finally { bmp.recycle(); ocrBusy.set(false) } }
        }
        // Recent strong text reads count as sightings too.
        if (textCardLike && now - textAt < 1200) textCands.firstOrNull()?.let { tc ->
            val second = textCands.getOrNull(1)?.score ?: 0f
            if (tc.score >= 0.7f && tc.score - second >= 0.08f) {
                val st = ((tc.score - 0.55f) * 2f).coerceIn(0.3f, 0.95f)
                sightings[tc.card.key] = maxOf(sightings[tc.card.key] ?: 0f, st)
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
        if (confirmed != null) confirm(c, resolveReprint(c, confirmed), a.sightings.firstOrNull()?.cands?.mapNotNull { c.card(it.key) } ?: emptyList(),
            if (a.sightings.any { it.key == confirmed }) "vision" else "text")
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

    private suspend fun readText(bmp: Bitmap, c: Catalog) {
        val want = Graph.prefs.value.scanLanguage
        // Automatic: Latin first (it also reads numbers on Asian cards); the Japanese reader
        // (Japanese + Latin) is tried when Latin finds no usable name, and kept while the cards
        // it reads really are in Japanese/Chinese/Korean.
        val first = if (want == "auto") ocrScript else want
        var lines = ocr.read(bmp, first)
        var clues = TextMatch.clues(lines)
        var script = first
        if (want == "auto") {
            val weak = clues.names.none { n -> n.count { it.isLetter() } >= 3 }
            if (first == "en" && weak) {
                val l2 = ocr.read(bmp, "ja"); val c2 = TextMatch.clues(l2)
                if (c2.script != null) { lines = l2; clues = c2; script = "ja"; ocrScript = "ja" }
            } else if (first == "ja" && clues.script == null) {
                ocrScript = "en"
            }
        }
        if (clues.numbers.isEmpty() && clues.names.isEmpty()) {
            // maybe the card is upside down
            val m = android.graphics.Matrix().apply { postRotate(180f) }
            val flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            val l2 = ocr.read(flipped, script)
            flipped.recycle()
            TextMatch.clues(l2).takeIf { it.numbers.isNotEmpty() || it.names.isNotEmpty() }?.let { clues = it }
        }
        val pool = Graph.refs.poolSetIds.toSet()
        val cands = TextMatch.candidates(clues, c, pool)
        Log.i(TAG, "text[$script] numbers=${clues.numbers.map { it.number + "/" + it.total }} names=${clues.names.take(3)} " +
            "codes=${clues.codes.take(8)} -> ${cands.take(3).map { it.card.key + " %.2f".format(it.score) }}")
        if (cands.isEmpty()) return
        textCardLike = clues.numbers.any { it.bottom }
        textCands = cands
        textAt = System.currentTimeMillis()
        // The printed text names the set before the card is certain: start loading it now
        if (textCardLike && cands.first().score >= 0.6f) detect(cands.first().card.setId)
        // Let vision double-check the likely cards (more of them when the text was weak)
        // only in the leading card's language: a same-art print in another language would compete
        val n = if (cands.first().score >= 0.7f) 3 else 8
        val lang = cands.first().card.lang
        Graph.refs.addCards(c, cands.filter { it.card.lang == lang }.take(n).map { it.card.key })
    }

    /** Same artwork in two sets can't be told apart by sight: trust a fresh text read for which. */
    private fun resolveReprint(c: Catalog, key: String): String {
        val card = c.card(key) ?: return key
        val tc = textCands.firstOrNull()?.takeIf { System.currentTimeMillis() - textAt < 3000 && it.score >= 0.8f } ?: return key
        return if (tc.card.key != key && Catalog.fold(tc.card.name) == Catalog.fold(card.name)) tc.card.key else key
    }

    private var lastName = ""
    private var lastAt = 0L

    private fun confirm(c: Catalog, key: String, cands: List<Card>, via: String) {
        val card = c.card(key) ?: return
        // A same-name card moments after the last scan is the same physical card seen as a reprint
        // or another language's print, not a second card.
        val now = System.currentTimeMillis()
        if (via != "manual" && Catalog.fold(card.name) == lastName && now - lastAt < 3000) { lastAt = now; return }
        lastName = Catalog.fold(card.name); lastAt = now
        Log.i(TAG, "confirmed $key via $via; text=${textCands.firstOrNull()?.let { it.card.key + " %.2f".format(it.score) }}")
        val s = Graph.prefs.value
        val store = Graph.store
        val variant = nextVariant?.takeIf { v -> card.variants().any { it.id == v } } ?: card.variants().first().id
        nextVariant = null
        val price = card.priceFor(variant)
        val tier = when { price >= s.jackpotValue -> 3; price >= s.bigValue -> 2; price >= s.hitValue -> 1; else -> 0 }
        val owned = store.ownedCount(key)
        val mode = s.scanMode
        var added = false
        var ignored = false
        var packCount = 0; var packSize = 0; var done = false
        val inPack = s.trackPacks && mode == "collect"
        if (inPack) {
            // a card from another set means a new pack
            if (packId != null && packSet != card.setId) nextPack(quiet = true)
            val set = c.set(card.setId)
            packSize = set?.let { Graph.catalog.packs.packSize(it) } ?: 10
            if (packId == null) {
                packId = store.openPack(card.setId, set?.let { it.sealedPrice ?: Graph.catalog.packs.products(it).firstOrNull()?.price })
                packSet = card.setId; packKeys.clear(); packValue = 0.0
            }
            if (key in packKeys) ignored = true else { packKeys += key; packValue += price }
            packCount = packKeys.size
            done = packCount >= packSize
        }
        if (!ignored && mode != "check") {
            val addIt = owned == 0 || s.dupePolicy == "warn"
            if (addIt) { store.add(key, variant, s.defaultCondition); added = true }
        }
        val scanId = if (ignored) -1 else store.addScan(key, variant, price, if (inPack) "pack" else mode, packId, owned > 0, added)
        if (done) { packId?.let { store.finishPack(it) }; packId = null; packKeys.clear() }
        val r = ScanResult(scanId, card, variant, price, tier, owned, added, if (inPack) "pack" else mode, ignored, packCount, packSize, packValue, done,
            cands.filter { it.key != key }.distinctBy { it.key }.take(4), via)
        if (done) packValue = 0.0
        feedback(r)
        _results.tryEmit(r)
        detect(card.setId)
        if (via == "text" || card.setId !in Graph.refs.poolSetIds) updatePool()
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

    /** Undo a scan: remove it and the copy it added. */
    fun undo(r: ScanResult) {
        if (r.scanId > 0) Graph.store.deleteScan(r.scanId)
        if (r.added) Graph.store.removeOne(r.card.key, r.variant)
        if (r.mode == "pack") { packKeys.remove(r.card.key); packValue -= r.price }
        tracker.unlock()
        Graph.sounds.play("undo")
    }

    /** The scanner picked the wrong card: replace it with the right one. */
    fun correct(r: ScanResult, right: Card): ScanResult {
        undo(r)
        val c = Graph.catalog.catalog.value ?: return r
        tracker.reset()
        confirm(c, right.key, emptyList(), "manual")
        return r
    }

    /** Change the printing of a scan (e.g. it was a reverse holo). */
    fun setVariant(r: ScanResult, variant: String): ScanResult {
        val price = r.card.priceFor(variant)
        if (r.scanId > 0) Graph.store.setScanVariant(r.scanId, variant, price)
        if (r.added) { Graph.store.removeOne(r.card.key, r.variant); Graph.store.add(r.card.key, variant, Graph.prefs.value.defaultCondition) }
        return r.copy(variant = variant, price = price)
    }

    /** Add a scan that wasn't added (duplicate skipped / price check). */
    fun addAnyway(r: ScanResult): ScanResult {
        Graph.store.add(r.card.key, r.variant, Graph.prefs.value.defaultCondition)
        if (r.scanId > 0) Graph.store.setScanAdded(r.scanId, true)
        Graph.sounds.play("select")
        return r.copy(added = true)
    }

    fun nextPack(quiet: Boolean = false) {
        packId?.let { Graph.store.finishPack(it) }; packId = null; packKeys.clear(); packValue = 0.0
        if (!quiet) Graph.sounds.play("deal")
    }
    fun packState() = Triple(packKeys.size, packValue, packId != null)

    fun rescan() { tracker.reset() }

    /** Forget the current card and text reads (between benchmark sequences). */
    fun resetForBench() { tracker.reset(); textCands = emptyList(); textAt = 0L; textCardLike = false; lastName = ""; lastAt = 0L }

    fun close() { scope.cancel(); ocr.close() }

    companion object { private const val TAG = "ScanEngine" }
}
