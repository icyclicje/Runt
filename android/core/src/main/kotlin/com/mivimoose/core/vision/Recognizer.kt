package com.mivimoose.core.vision

import org.opencv.core.Mat
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One look at a card: which card it most likely is, and how convincing that is (0..1). */
class Sighting(
    val key: String,
    val score: Float,
    val margin: Float,
    val inliers: Int,
    val strength: Float,
    val quad: Quad,
    val cands: List<Match>,
    /** "kp" (found by its artwork keypoints) or "edge" (by its outline). */
    val source: String,
    /** Sides of the outline that lie on straight edges in the frame (0..4). */
    val sides: Int = 4,
    /** Share of the card's height its matched keypoints span (keypoint sightings). */
    val ySpan: Float = 1f,
    /** Matched keypoints on the artwork: same-layout cards can't fake these. */
    val artInliers: Int = 0,
)

class Analysis(
    val outlines: List<Outline>,
    /** Distinct cards seen, strongest first. */
    val sightings: List<Sighting>,
    /** The main card's outline (best sighting's, else the best-shaped outline). */
    val main: Quad?,
    val sharpness: Float,
    val glare: Float,
    val millis: Long,
    /** What the built-in index thinks the unsettled card in the guide is: suggestions only. */
    val suggested: List<AtlasHit> = emptyList(),
)

/** Where the frame time goes, in total milliseconds per stage (for the benchmarks). */
object Prof {
    private val totals = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
    inline fun <T> time(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { add(name, System.nanoTime() - t0) }
    }
    fun add(name: String, nanos: Long) {
        val a = totals.getOrPut(name) { LongArray(2) }
        synchronized(a) { a[0] += nanos; a[1]++ }
    }
    fun reset() = totals.clear()
    /** "stage total/calls" per stage, per analysed frame of `frames`. */
    fun report(frames: Int): String = totals.entries.sortedByDescending { it.value[0] }.joinToString("  ") { (k, v) ->
        "$k %.1fms(%.2fx)".format(v[0] / 1e6 / frames.coerceAtLeast(1), v[1].toFloat() / frames.coerceAtLeast(1))
    }
}

/**
 * Per-frame recognition, as in the desktop scanner: artwork keypoints matched over the
 * whole frame (finds cards without visible edges), plus card outlines matched by
 * fingerprint and checked with keypoints between straightened images.
 */
class Recognizer(val index: CardIndex) {
    /** Logistic models of "this sighting names the right card", fitted on the desktop
     *  scanner's calibration footage. Keypoint: [1, log(1+inliers), score, margin, spread];
     *  outline: [1, log(1+inliers), score, margin]. */
    private val kpWeights = floatArrayOf(-6.0f, 2.0f, 6.0f, 20.0f, 2.0f)
    private val edgeWeights = floatArrayOf(-9.0f, 1.9f, 5.8f, 20.0f)
    private val edgeCap = 0.75f
    /** With this many keypoints agreeing on the straightened card there's no doubt left. */
    private val edgeProof = 40

    var minArea = 0.04f
    var frameMaxSide = 720
    var frameBudget = 1200
    var useKeypoints = true

    private fun prob(z: Float) = (((1f / (1f + exp(-z.coerceIn(-30f, 30f)))) - 0.25f) / 0.75f).coerceIn(0f, 1f)

    fun edgeStrength(score: Float, margin: Float, inliers: Int): Float {
        val s = prob(edgeWeights[0] + edgeWeights[1] * ln(1f + max(0, inliers)) + edgeWeights[2] * score + edgeWeights[3] * margin)
        // An outline match only counts once keypoints back it up: during motion blur the
        // fingerprint alone can be fooled several frames running.
        return (if (inliers >= edgeProof) min(s, 0.95f) else min(s, edgeCap)) * ((inliers - 8) / 16f).coerceIn(0f, 1f)
    }

    fun kpStrength(score: Float, margin: Float, inliers: Int, spread: Float): Float =
        prob(kpWeights[0] + kpWeights[1] * ln(1f + max(0, inliers)) + kpWeights[2] * score + kpWeights[3] * margin + kpWeights[4] * spread)

    fun analyze(gray: Mat, maxCards: Int = 2): Analysis {
        val t0 = System.nanoTime()
        val fp = index.fingerprints
        val w = gray.cols(); val h = gray.rows()
        val edges = ArrayList<EdgeMap>()
        val outlines = Prof.time("find") { CardFinder.find(gray, maxCandidates = 2, minArea = minArea, edgesOut = edges) { Guide.holds(it, w, h) } }
        val edgeMap = edges.firstOrNull()
        val sightings = ArrayList<Sighting>()
        var sharp = 0f; var glare = 0f; var measured = false

        fun measure(canon: Mat) {
            if (!measured) { sharp = Fingerprint.sharpness(canon); glare = Fingerprint.glare(canon); measured = true }
        }

        // 1. Outlines, the cheap look: a card whose edges show is named by its fingerprint and
        // checked with keypoints between straightened images, in a fraction of the time it
        // takes to search the whole frame.
        val suggested = LinkedHashSet<String>()
        val atlas = index.atlas
        var atlasHits: List<AtlasHit> = emptyList()
        if (fp.size > 0 || atlas != null) {
            var tried = 0
            for (o in outlines) {
                if (tried >= 2) break
                if (sightings.any { it.strength >= SURE && it.quad.iou(o.quad) > 0.5f }) continue
                tried++
                val (rows, canon) = Prof.time("edgeDesc") { Fingerprint.queryDescriptors(gray, o.quad, ONE_SIZE) }
                try {
                    measure(canon)
                    val cands = if (fp.size > 0) Prof.time("edgeMatch") { fp.match(rows, 5) } else emptyList()
                    cands.take(2).forEach { suggested += it.key }
                    fun marginOf(m: Match): Float {
                        val other = cands.filter { it.key != m.key }.maxOfOrNull { it.score }
                        return if (other != null) m.score - other else 0.5f
                    }
                    val checked = ArrayList<Pair<Match, Inliers>>(3)
                    Prof.time("edgeVerify") {
                        for (c in fp.verify(canon, cands, 3)) {
                            checked += c
                            // a clear lead that the keypoints back up: the rest can't beat it
                            val margin = marginOf(c.first)
                            if (margin >= CLOSE_CALL && c.second.art >= MIN_ART && edgeStrength(c.first.score, margin, c.second.all) >= SURE) break
                        }
                    }
                    var best: Sighting? = null
                    for ((m, found) in checked) {
                        val inl = found.all
                        val margin = marginOf(m)
                        val rival = checked.filter { it.first !== m }.maxOfOrNull { it.second.all } ?: 0
                        // A close call between two cards has to be settled by the keypoints: few of
                        // them is how card-like pictures (a playmat, a poster) get mistaken for a
                        // card, and cards of a set share a layout that alone gives dozens.
                        val s = if (found.art < MIN_ART || (margin < CLOSE_CALL && (inl < 30 || inl < 1.5f * rival))) 0f else edgeStrength(m.score, margin, inl)
                        if (best == null || s > best!!.strength) best = Sighting(m.key, m.score, margin, inl, s, o.quad, cands, "edge", edgeMap?.sides(o.quad) ?: 4, artInliers = found.art)
                    }
                    best?.let { sightings += it }
                    if (atlas != null && (best?.strength ?: 0f) < SURE) {
                        val found = Prof.time("atlas") { atlas.search(rows) }
                        if ((found.firstOrNull()?.score ?: -1f) > (atlasHits.firstOrNull()?.score ?: -1f)) atlasHits = found
                    }
                } finally {
                    canon.release()
                }
            }
        }

        // 2. Keypoints over the whole frame, when the outline didn't settle it: finds cards
        // without visible edges and cards the fingerprints got wrong.
        val sure = sightings.any { it.strength >= SURE }
        if (!sure && useKeypoints && index.keypoints.size > 0) {
            val ff = Prof.time("orb") { KeypointIndex.frameFeatures(gray, frameMaxSide, frameBudget) }
            try {
                var hits = Prof.time("detect") { index.keypoints.detect(ff, w, h, maxCards) }
                // Second look: cards the outlines' fingerprints suggest, in case the rough
                // keypoint shortlist missed them.
                val extra = suggested - hits.map { it.key }.toSet()
                if (extra.isNotEmpty() && hits.none { h1 -> outlines.any { h1.quad.iou(it.quad) > 0.5f } })
                    hits = hits + Prof.time("second") { index.keypoints.detect(ff, w, h, maxCards, extra = extra, onlyExtra = true) }
                Prof.time("kpCheck") { for (h1 in hits) kpSighting(gray, h1, edgeMap)?.let { (s, canon) -> measure(canon); canon.release(); sightings += s } }
            } finally {
                ff.release()
            }
        }

        // 3. One sighting per physical card: the strongest wins where outlines overlap
        sightings.sortByDescending { it.strength }
        val kept = ArrayList<Sighting>()
        for (s in sightings) if (kept.none { it.quad.iou(s.quad) > 0.5f }) kept += s
        val main = kept.firstOrNull { it.strength > 0f }?.quad ?: outlines.firstOrNull()?.quad
        return Analysis(outlines, kept, main, sharp, glare, (System.nanoTime() - t0) / 1_000_000, atlasHits)
    }

    /** A keypoint hit, double-checked against its card's fingerprint and scored. The corners
     *  projected from keypoints can drift a little, so the outline snapped onto the card's
     *  real edges is tried too and the better match kept. Returns the canon for measuring. */
    private companion object {
        /** Keypoint sighting strength by how many of its outline's sides lie on edges (0..4). */
        val SIDE_FACTOR = floatArrayOf(0.3f, 0.5f, 0.85f, 1f, 1f)
        /** A sighting this strong needs no second opinion in this frame. */
        const val SURE = 0.5f
        /** Fingerprint scores this close don't decide between two cards. */
        const val CLOSE_CALL = 0.06f
        /** Matches on the artwork a sighting needs: cards of a set share everything else. */
        const val MIN_ART = 3
        val ONE_SIZE = floatArrayOf(1f)
    }

    private fun kpSighting(gray: Mat, h: KpHit, edgeMap: EdgeMap?): Pair<Sighting, Mat>? {
        val fp = index.fingerprints
        var quad = h.quad
        var (rows, canon) = Fingerprint.queryDescriptors(gray, quad, floatArrayOf(1f))
        var score = fp.scoreFor(rows, h.key)
        if (edgeMap != null) {
            val q2 = edgeMap.snap(quad)
            if (q2.maxDist(quad) > 1f) {
                val (rows2, canon2) = Fingerprint.queryDescriptors(gray, q2, floatArrayOf(1f))
                val s2 = fp.scoreFor(rows2, h.key)
                if (s2 > score) { canon.release(); quad = q2; rows = rows2; canon = canon2; score = s2 } else canon2.release()
            }
        }
        val cands = fp.match(rows, 5)
        val other = cands.filter { it.key != h.key }.maxOfOrNull { it.score }
        val margin = if (other != null) score - other else 0.5f
        val sides = edgeMap?.sides(quad) ?: 4
        // A card held up shows straight edges where its outline is, and its matches reach from
        // the artwork into the name and text. The same artwork on a pack, a poster or a book has
        // neither: matches only in the artwork don't count (if a hand hides the text, the
        // outline path still sees the card), and few edges weaken the sighting, since a
        // low-contrast card can hide them.
        val ruledOut = h.ySpan < 0.4f || (sides == 0 && h.ySpan < 0.6f) || h.artInliers < MIN_ART
        val spanF = if (h.ySpan < 0.5f) 0.8f else 1f
        val s = if (ruledOut) 0f else kpStrength(score, margin, h.inliers, h.spread) * SIDE_FACTOR[sides] * spanF
        return Sighting(h.key, score, margin, h.inliers, s, quad, cands, "kp", sides, h.ySpan, h.artInliers) to canon
    }
}

/** The phone scanner's guide: only a card held there counts, not cards lying around it. */
object Guide {
    /** Strength from which a sighting is shown on screen as "this card". */
    const val SHOW = 0.2f

    fun holds(q: Quad, w: Int, h: Int): Boolean =
        q.cx in 0.18f * w..0.82f * w && q.cy in 0.18f * h..0.82f * h && q.area > 0.06f * w * h &&
            // a picture of a card filling the whole background (a playmat, a poster on the
            // wall) is bigger than a card held up to the camera: one filling the width of a
            // 9:16 screen covers 79% of it, unless it pokes out past the edges of the frame
            q.area < (if (inside(q, w, h)) 0.84f else 0.93f) * w * h

    private fun inside(q: Quad, w: Int, h: Int): Boolean = (0 until 4).all { q.x(it) in 0f..w.toFloat() && q.y(it) in 0f..h.toFloat() }

    /** The sighting the scanner shows and reads: the strongest one held in the guide. */
    fun main(a: Analysis, w: Int, h: Int): Sighting? = a.sightings.firstOrNull { it.strength >= SHOW && holds(it.quad, w, h) }
}

/**
 * Builds up fading evidence per card across frames; a card is confirmed once enough
 * evidence arrives over a few sightings. The confirmed card stays locked until it has
 * been out of view for `gap` seconds, so one card held in front of the camera is
 * scanned once.
 */
class Tracker(
    var threshold: Float = 1.5f,
    var minSightings: Int = 2,
    var halfLife: Double = 0.5,
    var gap: Double = 0.9,
    /** A locked card that's unrecognised keeps its lock this long at most while something
     *  card-shaped stays in view (glare, blur; not a binder page sitting there for ever). */
    var holdMax: Double = 5.0,
    /** How long after the locked card was last seen another card can take over. */
    var swap: Double = 0.25,
) {
    private class Ev(var value: Float, var count: Int, var last: Double)

    private val ev = HashMap<String, Ev>()
    private var lastTime = -1.0
    var locked: String? = null; private set
    private var lockedSeen = 0.0
    private var lockedSighted = 0.0

    /** Feed one frame's sightings (key to strength). Returns a newly confirmed key, or null. */
    fun update(now: Double, sightings: Map<String, Float>, cardInView: Boolean): String? {
        if (lastTime >= 0) {
            val f = Math.pow(0.5, (now - lastTime) / halfLife).toFloat()
            val it = ev.entries.iterator()
            while (it.hasNext()) {
                val e = it.next().value
                e.value *= f
                if (e.value < 0.02f && now - e.last > 2.0) it.remove()
            }
        }
        lastTime = now
        for ((k, s) in sightings) {
            if (s <= 0f) continue
            val e = ev.getOrPut(k) { Ev(0f, 0, now) }
            e.value += s; e.count++; e.last = now
        }
        val lk = locked
        if (lk != null) {
            if (sightings.containsKey(lk)) { lockedSeen = now; lockedSighted = now }
            else if (cardInView && now - lockedSighted < holdMax && sightings.values.none { it > 0.3f }) lockedSeen = now
            if (now - lockedSeen > gap) unlock()
        }
        // Only a card seen just now: evidence fades slowly, so a card that was held for a while
        // still has plenty of it after it has gone
        val best = ev.entries.filter { it.value.value >= threshold && it.value.count >= minSightings && now - it.value.last < 0.3 }
            .maxByOrNull { it.value.value } ?: return null
        if (best.key == locked) return null
        // A locked card seen just now is probably still there (a blurred frame looking like some
        // other card): only a clearly stronger card replaces it. A moment after it was last seen
        // the next card counts at once: flipping through a stack mustn't wait for it to fade.
        if (locked != null && now - lockedSighted < swap && best.value.value < 1.5f * (ev[locked!!]?.value ?: 0f)) return null
        locked = best.key
        lockedSeen = now
        lockedSighted = now
        ev.keys.retainAll(setOf(best.key))
        return best.key
    }

    /** Evidence so far for the leading unconfirmed card, 0..1 of the threshold. */
    fun progress(): Pair<String, Float>? = ev.entries.filter { it.key != locked }
        .maxByOrNull { it.value.value }?.let { it.key to (it.value.value / threshold).coerceIn(0f, 1f) }

    fun unlock() {
        locked?.let { ev.remove(it) }
        locked = null
    }

    fun reset() {
        ev.clear(); locked = null; lastTime = -1.0
    }
}
