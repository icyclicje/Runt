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
)

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

    var minArea = 0.04f
    var frameMaxSide = 960
    var frameBudget = 2000
    var useKeypoints = true

    private fun prob(z: Float) = (((1f / (1f + exp(-z.coerceIn(-30f, 30f)))) - 0.25f) / 0.75f).coerceIn(0f, 1f)

    fun edgeStrength(score: Float, margin: Float, inliers: Int): Float {
        val s = prob(edgeWeights[0] + edgeWeights[1] * ln(1f + max(0, inliers)) + edgeWeights[2] * score + edgeWeights[3] * margin)
        // An outline match only counts once keypoints back it up: during motion blur the
        // fingerprint alone can be fooled several frames running.
        return min(s, edgeCap) * ((inliers - 8) / 16f).coerceIn(0f, 1f)
    }

    fun kpStrength(score: Float, margin: Float, inliers: Int, spread: Float): Float =
        prob(kpWeights[0] + kpWeights[1] * ln(1f + max(0, inliers)) + kpWeights[2] * score + kpWeights[3] * margin + kpWeights[4] * spread)

    fun analyze(gray: Mat, maxCards: Int = 2): Analysis {
        val t0 = System.nanoTime()
        val fp = index.fingerprints
        val edges = ArrayList<EdgeMap>()
        val outlines = CardFinder.find(gray, maxCandidates = 4, minArea = minArea, edgesOut = edges)
        val edgeMap = edges.firstOrNull()
        val sightings = ArrayList<Sighting>()
        var sharp = 0f; var glare = 0f; var measured = false

        fun measure(canon: Mat) {
            if (!measured) { sharp = Fingerprint.sharpness(canon); glare = Fingerprint.glare(canon); measured = true }
        }

        // 1. Keypoints over the whole frame
        if (useKeypoints && index.keypoints.size > 0) {
            val ff = KeypointIndex.frameFeatures(gray, frameMaxSide, frameBudget)
            try {
                var hits = index.keypoints.detect(ff, gray.cols(), gray.rows(), maxCards)
                // Second look: cards the outlines' fingerprints suggest, in case the rough
                // keypoint shortlist missed them.
                if (fp.size > 0 && outlines.isNotEmpty()) {
                    val extra = LinkedHashSet<String>()
                    for (o in outlines.take(3)) {
                        if (hits.any { it.quad.iou(o.quad) > 0.5f }) continue
                        val (rows, canon) = Fingerprint.queryDescriptors(gray, o.quad, floatArrayOf(1f))
                        canon.release()
                        fp.match(rows, 2).forEach { extra += it.key }
                    }
                    extra.removeAll(hits.map { it.key }.toSet())
                    if (extra.isNotEmpty()) hits = hits + index.keypoints.detect(ff, gray.cols(), gray.rows(), maxCards, extra = extra, onlyExtra = true)
                }
                for (h in hits) kpSighting(gray, h, edgeMap)?.let { (s, canon) -> measure(canon); canon.release(); sightings += s }
            } finally {
                ff.release()
            }
        }

        // 2. Outlines not already explained by a confident keypoint sighting
        if (fp.size > 0) {
            var tried = 0
            for (o in outlines) {
                if (tried >= 2) break
                if (sightings.any { it.strength >= 0.5f && it.quad.iou(o.quad) > 0.5f }) continue
                tried++
                val (rows, canon) = Fingerprint.queryDescriptors(gray, o.quad)
                try {
                    measure(canon)
                    val cands = fp.match(rows, 5)
                    var best: Sighting? = null
                    for ((m, inl) in fp.verify(canon, cands, 3)) {
                        val other = cands.filter { it.key != m.key }.maxOfOrNull { it.score }
                        val margin = if (other != null) m.score - other else 0.5f
                        val s = edgeStrength(m.score, margin, inl)
                        if (best == null || s > best.strength) best = Sighting(m.key, m.score, margin, inl, s, o.quad, cands, "edge")
                    }
                    best?.let { sightings += it }
                } finally {
                    canon.release()
                }
            }
        } else if (outlines.isNotEmpty()) {
            val canon = Fingerprint.warp(gray, outlines[0].quad)
            measure(canon); canon.release()
        }

        // 3. One sighting per physical card: the strongest wins where outlines overlap
        sightings.sortByDescending { it.strength }
        val kept = ArrayList<Sighting>()
        for (s in sightings) if (kept.none { it.quad.iou(s.quad) > 0.5f }) kept += s
        val main = kept.firstOrNull { it.strength > 0f }?.quad ?: outlines.firstOrNull()?.quad
        return Analysis(outlines, kept, main, sharp, glare, (System.nanoTime() - t0) / 1_000_000)
    }

    /** A keypoint hit, double-checked against its card's fingerprint and scored. The corners
     *  projected from keypoints can drift a little, so the outline snapped onto the card's
     *  real edges is tried too and the better match kept. Returns the canon for measuring. */
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
        val s = kpStrength(score, margin, h.inliers, h.spread)
        return Sighting(h.key, score, margin, h.inliers, s, quad, cands, "kp") to canon
    }
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
) {
    private class Ev(var value: Float, var count: Int, var last: Double)

    private val ev = HashMap<String, Ev>()
    private var lastTime = -1.0
    var locked: String? = null; private set
    private var lockedSeen = 0.0

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
            if (sightings.containsKey(lk) || (cardInView && sightings.values.none { it > 0.3f })) lockedSeen = now
            if (now - lockedSeen > gap) unlock()
        }
        val best = ev.entries.filter { it.value.value >= threshold && it.value.count >= minSightings }
            .maxByOrNull { it.value.value } ?: return null
        if (best.key == locked) return null
        // A locked card is only replaced by a clearly stronger different card.
        if (locked != null && best.value.value < 1.5f * (ev[locked!!]?.value ?: 0f)) return null
        locked = best.key
        lockedSeen = now
        ev.keys.retainAll(setOf(best.key))
        return best.key
    }

    /** Evidence so far for the leading unconfirmed card, 0..1 of the threshold. */
    fun progress(): Pair<String, Float>? = ev.entries.filter { it.key != locked }
        .maxByOrNull { it.value.value }?.let { it.key to (it.value.value / threshold).coerceIn(0f, 1f) }

    fun unlock() {
        locked = null
    }

    fun reset() {
        ev.clear(); locked = null; lastTime = -1.0
    }
}
