package com.mivimoose.core.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.util.LinkedHashMap
import kotlin.math.hypot
import kotlin.math.sqrt

/** ORB keypoints of a straightened card: points (x,y pairs) and 32-byte descriptors. */
class OrbFeatures(val pts: FloatArray, val desc: ByteArray) {
    val count get() = pts.size / 2

    fun descMat(): Mat {
        val m = Mat(count, 32, CvType.CV_8UC1)
        if (count > 0) m.put(0, 0, desc)
        return m
    }
}

object Orb {
    private val orb = ThreadLocal.withInitial { ORB.create(500, 1.2f, 5, 12, 0, 2, ORB.HARRIS_SCORE, 17, 20) }
    private val bf = ThreadLocal.withInitial { BFMatcher.create(Core.NORM_HAMMING, false) }

    fun features(canon: Mat): OrbFeatures = matScope {
        val eq = mat()
        Imgproc.equalizeHist(canon, eq)
        val kp = MatOfKeyPoint().track()
        val des = mat()
        orb.get().detectAndCompute(eq, mat(), kp, des)
        val k = kp.toArray()
        if (k.isEmpty() || des.empty()) return@matScope OrbFeatures(FloatArray(0), ByteArray(0))
        val pts = FloatArray(k.size * 2) { if (it % 2 == 0) k[it / 2].pt.x.toFloat() else k[it / 2].pt.y.toFloat() }
        val d = ByteArray(des.rows() * 32)
        des.get(0, 0, d)
        OrbFeatures(pts, d)
    }

    /** Geometrically consistent keypoint matches between two straightened cards. Both are
     *  the same size, so the right card lines up with a near-identity similarity transform. */
    fun inliers(q: OrbFeatures, r: OrbFeatures): Int = matScope {
        if (q.count < 8 || r.count < 8) return@matScope 0
        val qd = q.descMat().track(); val rd = r.descMat().track()
        val pairs = ArrayList<MatOfDMatch>()
        bf.get().knnMatch(qd, rd, pairs, 2)
        val src = ArrayList<Point>(); val dst = ArrayList<Point>()
        for (p in pairs) {
            val m = p.toArray()
            p.release()
            if (m.size == 2 && m[0].distance < 0.8f * m[1].distance) {
                val qi = m[0].queryIdx; val ti = m[0].trainIdx
                src += Point(q.pts[2 * qi].toDouble(), q.pts[2 * qi + 1].toDouble())
                dst += Point(r.pts[2 * ti].toDouble(), r.pts[2 * ti + 1].toDouble())
            }
        }
        if (src.size < 8) return@matScope src.size / 2
        val mask = mat()
        val a = Calib3d.estimateAffinePartial2D(
            MatOfPoint2f(*src.toTypedArray()).track(), MatOfPoint2f(*dst.toTypedArray()).track(), mask,
            Calib3d.RANSAC, 6.0, 300, 0.98, 10,
        ).track()
        if (a.empty() || mask.empty()) return@matScope 0
        val scale = hypot(a.get(0, 0)[0], a.get(1, 0)[0])
        if (scale !in 0.75..1.33) return@matScope 0
        Core.countNonZero(mask)
    }
}

/** One reference card for the matcher: its key, fingerprint and straightened grey image. */
class RefCard(val key: String, val desc: FloatArray, val canon: ByteArray)

class Match(val key: String, val score: Float, val row: Int)

/**
 * Fingerprints of every card in the current pool, searched with one pass of dot
 * products, plus ORB keypoint checks between straightened images.
 */
class FingerprintIndex {
    var keys: Array<String> = emptyArray(); private set
    private var desc = FloatArray(0)
    private var mu: FloatArray? = null
    private val canon = HashMap<String, ByteArray>()
    private val featCache = object : LinkedHashMap<String, OrbFeatures>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OrbFeatures>?) = size > 1500
    }
    val size get() = keys.size

    @Synchronized
    fun load(refs: Collection<RefCard>) {
        val d = Fingerprint.DIM
        val n = refs.size
        val raw = FloatArray(n * d)
        refs.forEachIndexed { i, r -> System.arraycopy(r.desc, 0, raw, i * d, d) }
        // Every card shares the same layout (border, text box, HP bar...). Removing the
        // average fingerprint leaves what makes each card different, which widens the gap
        // between the right card and look-alikes.
        mu = if (n >= 8) FloatArray(d) { j -> var s = 0f; for (i in 0 until n) s += raw[i * d + j]; s / n } else null
        for (i in 0 until n) center(raw, i * d)
        desc = raw
        keys = refs.map { it.key }.toTypedArray()
        canon.clear()
        refs.forEach { canon[it.key] = it.canon }
        synchronized(featCache) { featCache.keys.retainAll(canon.keys) }
    }

    fun contains(key: String) = canon.containsKey(key)

    private fun center(v: FloatArray, at: Int) {
        val m = mu ?: return
        var n = 0.0
        for (j in m.indices) { v[at + j] -= m[j]; n += v[at + j] * v[at + j] }
        val s = (1.0 / (sqrt(n) + 1e-6)).toFloat()
        for (j in m.indices) v[at + j] *= s
    }

    /** Best `topk` cards for a query (several fingerprint rows; the best row counts). */
    fun match(query: Array<FloatArray>, topk: Int = 5): List<Match> {
        val keys = keys; val desc = desc
        if (keys.isEmpty()) return emptyList()
        val d = Fingerprint.DIM
        val q = query.map { r -> r.copyOf().also { center(it, 0) } }
        val best = FloatArray(keys.size) { -2f }
        val bestRow = IntArray(keys.size)
        for (i in keys.indices) {
            val off = i * d
            for ((ri, r) in q.withIndex()) {
                var s = 0f
                for (j in 0 until d) s += r[j] * desc[off + j]
                if (s > best[i]) { best[i] = s; bestRow[i] = ri }
            }
        }
        return keys.indices.sortedByDescending { best[it] }.take(topk).map { Match(keys[it], best[it], bestRow[it]) }
    }

    fun refFeatures(key: String): OrbFeatures? {
        synchronized(featCache) { featCache[key]?.let { return it } }
        val c = canon[key] ?: return null
        val m = Mat(CANON_H, CANON_W, CvType.CV_8UC1)
        m.put(0, 0, c)
        val f = Orb.features(m)
        m.release()
        synchronized(featCache) { featCache[key] = f }
        return f
    }

    /** Keypoint inliers for the top candidates, in the orientation the fingerprint chose. */
    fun verify(qCanon: Mat, cands: List<Match>, n: Int = 3): List<Pair<Match, Int>> {
        val feats = HashMap<Boolean, OrbFeatures>()
        fun q(upsideDown: Boolean) = feats.getOrPut(upsideDown) {
            if (!upsideDown) Orb.features(qCanon) else {
                val r = Mat(); Core.rotate(qCanon, r, Core.ROTATE_180)
                Orb.features(r).also { r.release() }
            }
        }
        return cands.take(n).map { m ->
            val rf = refFeatures(m.key)
            m to (if (rf == null) 0 else Orb.inliers(q(m.row % 2 == 1), rf))
        }
    }
}
