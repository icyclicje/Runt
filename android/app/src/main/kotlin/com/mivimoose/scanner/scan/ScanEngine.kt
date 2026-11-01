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
    /** The last text read was laid out like a card (a collector number along the bottom). */
    @Volatile private var textCardLike = false

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
        updatePool()
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

        // Card text: when there's a card but vision isn't sure (or the pool doesn't have it).
        val quad = a.sightings.firstOrNull { it.strength > 0f && Guide.holds(it.quad, w, h) }?.quad
            ?: a.outlines.firstOrNull { Guide.holds(it.quad, w, h) }?.quad
        val visualSure = a.sightings.firstOrNull()?.let { it.strength >= 0.55f } ?: false
        val now = System.currentTimeMillis()
        // The guide stayed empty for a moment: whatever comes next is another card, even a second
        // copy of the last one. (Not one blurred frame: that can happen while a card is held.)
        if (quad != null || sightings.isNotEmpty()) session.emptySince = 0L
        else {
            if (session.emptySince == 0L) session.emptySince = now
            if (now - session.emptySince >= EMPTY_MS) { session.emptyAt = now; tracker.unlock() }
        }
        if (sightings.containsKey(session.lastKey)) session.lastSeenAt = now
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
            val onCard = textQuad === quad
            scope.launch { try { readText(bmp, c, onCard) } finally { bmp.recycle(); ocrBusy.set(false) } }
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
        if (confirmed != null) try {
            confirm(c, resolveReprint(c, confirmed), a.sightings.firstOrNull()?.cands?.mapNotNull { c.card(it.key) } ?: emptyList(),
                if (a.sightings.any { it.key == confirmed }) "vision" else "text")
        } catch (e: Exception) { Log.e(TAG, "confirm $confirmed", e) }
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
        suspend fun read(b: Bitmap, script: String) = ocr.read(b, script).let { if (onCard) it else TextMatch.fitted(it) }
        val want = Graph.prefs.value.scanLanguage
        // Automatic: Latin first (it also reads numbers on Asian cards); the Japanese reader
        // (Japanese + Latin) is tried when Latin finds no usable name, and kept while the cards
        // it reads really are in Japanese/Chinese/Korean.
        val first = if (want == "auto") ocrScript else want
        var lines = read(bmp, first)
        var clues = TextMatch.clues(lines)
        var script = first
        if (want == "auto") {
            val weak = clues.names.none { n -> n.count { it.isLetter() } >= 3 }
            if (first == "en" && weak) {
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
        var cands = TextMatch.candidates(clues, c, pool, limit = 24)
        // an upside-down card: the same words, read from the other end
        val flip = TextMatch.clues(TextMatch.rotated(lines))
        if (TextMatch.cardLike(flip) && !TextMatch.cardLike(clues) || flip.names.isNotEmpty()) {
            val fc = TextMatch.candidates(flip, c, pool, limit = 24)
            if ((fc.firstOrNull()?.score ?: 0f) > (cands.firstOrNull()?.score ?: 0f) + 0.05f) { clues = flip; cands = fc }
        }
        Log.i(TAG, "text[$script] numbers=${clues.numbers.map { it.number + "/" + it.total }} names=${clues.names.take(3)} " +
            "codes=${clues.codes.take(8)} -> ${cands.take(3).map { it.card.key + " %.2f".format(it.score) }}")
        if (cands.isEmpty()) return
        textCardLike = TextMatch.cardLike(clues)
        textCands = cands
        textAt = System.currentTimeMillis()
        // The printed text names the set before the card is certain: start loading it now
        if (textCardLike && cands.first().score >= 0.6f) detect(cands.first().card.setId)
        // Let vision double-check the likely cards (more of them when the text was weak)
        // only in the leading card's language: a same-art print in another language would compete
        val n = if (cands.first().score >= 0.7f) 3 else 24
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
        tracker.reset(); textCands = emptyList(); textAt = 0L; textCardLike = false
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
        /** When this session of scanning began (for the strip of its scans). */
        val sessionStart get() = SESSION.startedAt
    }
}
