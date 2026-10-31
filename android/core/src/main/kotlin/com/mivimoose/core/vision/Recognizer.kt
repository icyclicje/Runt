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
)

class Analysis(
    val outlines: List<Outline>,
    /** Best sighting per distinct outline, strongest first. */
    val sightings: List<Sighting>,
    /** The main outline (best sighting's, else the best-shaped one). */
    val main: Quad?,
    val sharpness: Float,
    val glare: Float,
    val millis: Long,
)

/**
 * Per-frame recognition: card outlines, then fingerprint search and keypoint
 * verification of the straightened card against the current pool of cards.
 */
class Recognizer(val index: FingerprintIndex) {
    /** Logistic model of "this sighting names the right card" over
     *  [1, log(1+inliers), score, margin], fitted on the desktop scanner's calibration footage. */
    private val weights = floatArrayOf(-9.0f, 1.9f, 5.8f, 20.0f)
    private val cap = 0.75f

    var minArea = 0.04f

    fun strengthOf(score: Float, margin: Float, inliers: Int): Float {
        val z = weights[0] + weights[1] * ln(1f + max(0, inliers)) + weights[2] * score + weights[3] * margin
        val p = 1f / (1f + exp(-z.coerceIn(-30f, 30f)))
        val s = ((p - 0.25f) / 0.75f).coerceIn(0f, 1f)
        // An outline match only counts once keypoints back it up: during motion blur the
        // fingerprint alone can be fooled several frames running.
        return min(s, cap) * ((inliers - 8) / 16f).coerceIn(0f, 1f)
    }

    fun analyze(gray: Mat, maxOutlines: Int = 3): Analysis {
        val t0 = System.nanoTime()
        val outlines = CardFinder.find(gray, maxCandidates = 4, minArea = minArea)
        val sightings = ArrayList<Sighting>()
        var sharp = 0f; var glare = 0f
        var mainCanonDone = false
        if (outlines.isNotEmpty()) {
            for (o in outlines.take(maxOutlines + 2)) {
                if (sightings.any { it.quad.iou(o.quad) > 0.6f }) continue
                val (rows, canon) = Fingerprint.queryDescriptors(gray, o.quad)
                try {
                    if (!mainCanonDone) {
                        sharp = Fingerprint.sharpness(canon); glare = Fingerprint.glare(canon); mainCanonDone = true
                    }
                    if (index.size == 0) continue
                    val cands = index.match(rows, 5)
                    val checked = index.verify(canon, cands, 3)
                    var best: Sighting? = null
                    for ((m, inl) in checked) {
                        val other = cands.filter { it.key != m.key }.maxOfOrNull { it.score }
                        val margin = if (other != null) m.score - other else 0.5f
                        val s = strengthOf(m.score, margin, inl)
                        if (best == null || s > best.strength) best = Sighting(m.key, m.score, margin, inl, s, o.quad, cands)
                    }
                    if (best != null) sightings += best
                } finally {
                    canon.release()
                }
                if (sightings.size >= maxOutlines) break
            }
        }
        sightings.sortByDescending { it.strength }
        val main = sightings.firstOrNull { it.strength > 0f }?.quad ?: outlines.firstOrNull()?.quad
        return Analysis(outlines, sightings, main, sharp, glare, (System.nanoTime() - t0) / 1_000_000)
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
            if (sightings.containsKey(lk) || (cardInView && sightings.isEmpty())) lockedSeen = now
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
