package com.mivimoose.scanner.scan

import android.graphics.Bitmap
import android.util.Log
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.text.TextCandidate
import com.mivimoose.core.text.TextMatch
import com.mivimoose.core.vision.Fingerprint
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
    /** Main card outline as fractions of the analysed frame, or null. */
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

    init {
        tracker.threshold = 1.5f
        tracker.minSightings = 2
        updatePool()
    }

    /** Recognition pool: the pack's set, plus the sets of recent scans. */
    fun updatePool() {
        val c = Graph.catalog.catalog.value ?: return
        val s = Graph.prefs.value
        val sets = LinkedHashSet<String>()
        if (s.scanMode == "pack") s.packSetId?.let { sets += it }
        Graph.store.scans(60).mapNotNull { c.card(it.cardKey)?.setId }.distinct().take(if (sets.isEmpty()) 3 else 2).forEach { sets += it }
        Graph.refs.setPool(c, sets.toList())
    }

    /** One portrait greyscale frame. Runs on the camera's analysis thread. */
    fun onFrame(gray: Mat, t: Double) {
        if (paused) return
        val c = Graph.catalog.catalog.value ?: return
        val a = try { rec.analyze(gray) } catch (e: Exception) { Log.w(TAG, "analyze", e); return }
        val w = gray.cols(); val h = gray.rows()
        val sightings = HashMap<String, Float>()
        for (s in a.sightings) if (s.strength > 0f) sightings[s.key] = maxOf(sightings[s.key] ?: 0f, s.strength)

        // Card text: when there's a card but vision isn't sure (or the pool doesn't have it).
        val quad = a.main
        val visualSure = a.sightings.firstOrNull()?.let { it.strength >= 0.55f } ?: false
        val now = System.currentTimeMillis()
        if (quad != null && !visualSure && Graph.prefs.value.ocr && now - lastOcr > 450 && quad.area > 0.06f * w * h && ocrBusy.compareAndSet(false, true)) {
            lastOcr = now
            val bmp = cardBitmap(gray, quad)
            scope.launch { try { readText(bmp, c) } finally { bmp.recycle(); ocrBusy.set(false) } }
        }
        // Recent strong text reads count as sightings too.
        if (now - textAt < 1200) textCands.firstOrNull()?.let { tc ->
            val second = textCands.getOrNull(1)?.score ?: 0f
            if (tc.score >= 0.7f && tc.score - second >= 0.08f) {
                val st = ((tc.score - 0.55f) * 2f).coerceIn(0.3f, 0.95f)
                sightings[tc.card.key] = maxOf(sightings[tc.card.key] ?: 0f, st)
            }
        }
        val confirmed = tracker.update(t, sightings, a.outlines.isNotEmpty())
        val prog = tracker.progress()
        val hint = when {
            quad == null -> "Show a card to the camera"
            quad.area < 0.12f * w * h -> "Move closer"
            a.glare > 0.08f -> "Glare: tilt the card a little"
            a.sharpness in 1f..40f -> "Hold steady"
            ocrBusy.get() && !visualSure -> "Reading the card…"
            Graph.refs.index.size == 0 -> "Reading the card…"
            else -> ""
        }
        _live.value = LiveScan(
            quad = quad?.let { q -> FloatArray(8) { if (it % 2 == 0) q.p[it] / w else q.p[it] / h } },
            frameW = w, frameH = h,
            state = if (tracker.locked != null && sightings.containsKey(tracker.locked)) "locked" else if (prog != null) "reading" else "searching",
            progress = prog?.second ?: 0f, hint = hint, leading = prog?.first?.let { c.card(it)?.name },
            reading = textCands.firstOrNull()?.takeIf { now - textAt < 2000 }?.let { "#${it.card.number} ${it.card.name}" },
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
        val script = if (want == "auto") ocrScript else want
        var lines = ocr.read(bmp, script)
        var clues = TextMatch.clues(lines)
        if (want == "auto") {
            // Switch reader when the text looks like another script (or nothing Latin was read)
            val next = clues.script ?: if (clues.names.isEmpty() && script == "en") "ja" else "en"
            if (next != script && (clues.script != null || clues.names.isEmpty())) {
                ocrScript = if (next == "zh" || next == "ko" || next == "th") "ja" else next
                if (clues.numbers.isEmpty()) { lines = ocr.read(bmp, ocrScript); clues = TextMatch.clues(lines) }
            }
        }
        val pool = Graph.refs.poolSetIds.toSet()
        val cands = TextMatch.candidates(clues, c, pool)
        if (cands.isEmpty()) return
        textCands = cands
        textAt = System.currentTimeMillis()
        // Let vision double-check the likely cards
        Graph.refs.addCards(c, cands.take(3).map { it.card.key })
    }

    /** Same artwork in two sets can't be told apart by sight: trust a fresh text read for which. */
    private fun resolveReprint(c: Catalog, key: String): String {
        val card = c.card(key) ?: return key
        val tc = textCands.firstOrNull()?.takeIf { System.currentTimeMillis() - textAt < 3000 && it.score >= 0.8f } ?: return key
        return if (tc.card.key != key && Catalog.fold(tc.card.name) == Catalog.fold(card.name)) tc.card.key else key
    }

    private fun confirm(c: Catalog, key: String, cands: List<Card>, via: String) {
        val card = c.card(key) ?: return
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
        if (mode == "pack") {
            val set = s.packSetId?.let { c.set(it) }
            packSize = set?.let { Graph.catalog.packs.packSize(it) } ?: 10
            if (packId == null) { packId = store.openPack(s.packSetId ?: card.setId, s.packCost); packKeys.clear(); packValue = 0.0 }
            if (key in packKeys) ignored = true else { packKeys += key; packValue += price }
            packCount = packKeys.size
            done = packCount >= packSize
        }
        if (!ignored && mode != "check") {
            val addIt = owned == 0 || s.dupePolicy == "warn"
            if (addIt) { store.add(key, variant, s.defaultCondition); added = true }
        }
        val scanId = if (ignored) -1 else store.addScan(key, variant, price, mode, packId, owned > 0, added)
        if (done) { packId?.let { store.finishPack(it) }; packId = null; packKeys.clear() }
        val r = ScanResult(scanId, card, variant, price, tier, owned, added, mode, ignored, packCount, packSize, packValue, done,
            cands.filter { it.key != key }.distinctBy { it.key }.take(4), via)
        if (done) packValue = 0.0
        feedback(r)
        _results.tryEmit(r)
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

    fun nextPack() { packId?.let { Graph.store.finishPack(it) }; packId = null; packKeys.clear(); packValue = 0.0; Graph.sounds.play("deal") }
    fun packState() = Triple(packKeys.size, packValue, packId != null)

    fun rescan() { tracker.reset() }

    fun close() { scope.cancel(); ocr.close() }

    companion object { private const val TAG = "ScanEngine" }
}
