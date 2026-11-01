package com.mivimoose.core.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.KeyPoint
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

const val REF_W = 240
const val REF_H = 336

/** A card found by keypoint matching: inliers agree with the homography, matched voted for
 *  the card, spread = share of the card's area the inliers cover. */
class KpHit(val key: String, val quad: Quad, val inliers: Int, val votes: Int, val spread: Float, val matched: Int, val ySpan: Float = 1f)

/** Frame keypoints in frame pixels plus the downscale factor the homographies were fitted at. */
class FrameFeatures(val pts: FloatArray, val desc: Mat, val scale: Float) {
    val count get() = pts.size / 2
    fun release() = desc.release()
}

/**
 * Finds and identifies cards anywhere in a frame by their artwork (port of the desktop
 * scanner's features.py). Every reference card contributes a few hundred ORB keypoints;
 * a frame's keypoints are looked up in an LSH index, every match votes for its card, and
 * the best-voted cards are confirmed with a homography whose projected corners give the
 * card's exact outline, even when its edges can't be seen.
 */
class KeypointIndex {
    private var keys: Array<String> = emptyArray()
    private var cardOf = IntArray(0)
    private var off = IntArray(1)
    private var pts = FloatArray(0)
    private var bytes = ByteArray(0)
    private var lsh: Lsh? = null
    private var indexOf: Map<String, Int> = emptyMap()
    var minSpread = 0.06f
    val size get() = keys.size

    @Synchronized
    fun load(refs: Collection<RefCard>) {
        val list = refs.filter { it.kpPts != null && it.kpDes != null }
        keys = list.map { it.key }.toTypedArray()
        indexOf = keys.withIndex().associate { it.value to it.index }
        val sizes = list.map { it.kpPts!!.size / 2 }
        off = IntArray(list.size + 1).also { o -> sizes.forEachIndexed { i, n -> o[i + 1] = o[i] + n } }
        val total = off.last()
        cardOf = IntArray(total)
        pts = FloatArray(total * 2)
        bytes = ByteArray(total * 32)
        list.forEachIndexed { i, r ->
            for (j in off[i] until off[i + 1]) cardOf[j] = i
            System.arraycopy(r.kpPts!!, 0, pts, off[i] * 2, sizes[i] * 2)
            System.arraycopy(r.kpDes!!, 0, bytes, off[i] * 32, sizes[i] * 32)
        }
        // Bigger hash keys keep buckets small when many sets are loaded.
        lsh = if (total == 0) null else Lsh(bytes, total, tables = if (total < 300_000) 6 else 8, keyBits = if (total < 150_000) 16 else if (total < 300_000) 18 else 22)
    }

    /** Cards most of the frame's keypoints roughly point at (fast, approximate). */
    fun shortlist(q: ByteArray, n: Int, top: Int = 15): List<Int> {
        val l = lsh ?: return emptyList()
        val votes = IntArray(keys.size)
        for (i in 0 until n) {
            val (idx, d) = l.nearest(q, i)
            if (idx >= 0 && d < 64) votes[cardOf[idx]]++
        }
        return votes.indices.sortedByDescending { votes[it] }.take(top).filter { votes[it] >= 3 }
    }

    fun detect(frame: FrameFeatures, frameW: Int, frameH: Int, maxCards: Int = 3, minInliers: Int = 10,
               extra: Collection<String> = emptyList(), onlyExtra: Boolean = false): List<KpHit> {
        if (lsh == null || frame.count < 8) return emptyList()
        val n = frame.count
        val qBytes = ByteArray(n * 32).also { frame.desc.get(0, 0, it) }
        val cand = (if (onlyExtra) mutableListOf() else shortlist(qBytes, n).toMutableList())
        for (k in extra) indexOf[k]?.let { if (it !in cand) cand += it }
        if (cand.isEmpty()) return emptyList()
        val rows = cand.flatMap { c -> (off[c] until off[c + 1]).toList() }.toIntArray()
        if (rows.isEmpty()) return emptyList()
        val train = Mat(rows.size, 32, CvType.CV_8UC1)
        val tb = ByteArray(rows.size * 32)
        rows.forEachIndexed { i, r -> System.arraycopy(bytes, r * 32, tb, i * 32, 32) }
        train.put(0, 0, tb)
        val pairs = ArrayList<MatOfDMatch>()
        try {
            BF.get().knnMatch(frame.desc, train, pairs, 2)
        } catch (e: Exception) {
            train.release(); return emptyList()
        }
        train.release()
        val qi = ArrayList<Int>(); val ti = ArrayList<Int>()
        for (p in pairs) {
            val m = p.toArray(); p.release()
            if (m.isEmpty()) continue
            val a = m[0]
            if (a.distance > 72) continue
            if (m.size > 1) {
                val b = m[1]
                // a near-tie between two different cards is ambiguous; within one card it's fine
                if (cardOf[rows[a.trainIdx]] != cardOf[rows[b.trainIdx]] && a.distance >= 0.82f * b.distance) continue
            }
            qi += a.queryIdx; ti += rows[a.trainIdx]
        }
        if (qi.size < minInliers) return emptyList()
        val votes = IntArray(keys.size)
        for (t in ti) votes[cardOf[t]]++
        val order = votes.indices.sortedByDescending { votes[it] }
        val used = BooleanArray(n)
        val hits = ArrayList<KpHit>()
        val s = frame.scale
        val floor = max(8f, minInliers * 0.7f)
        for (c in order.take(maxCards * 3)) {
            if (votes[c] < floor || hits.size >= maxCards) break
            val sel = qi.indices.filter { cardOf[ti[it]] == c && !used[qi[it]] }
            if (sel.size < 8) continue
            val src = MatOfPoint2f(*sel.map { Point(pts[2 * ti[it]].toDouble(), pts[2 * ti[it] + 1].toDouble()) }.toTypedArray())
            val dst = MatOfPoint2f(*sel.map { Point(frame.pts[2 * qi[it]] * s.toDouble(), frame.pts[2 * qi[it] + 1] * s.toDouble()) }.toTypedArray())
            val mask = Mat()
            val h = try {
                Calib3d.findHomography(src, dst, Calib3d.USAC_MAGSAC, 3.5, mask, 800, 0.995)
            } catch (e: Exception) { Mat() }
            try {
                if (h.empty() || mask.empty()) continue
                val inl = ByteArray(mask.rows()).also { mask.get(0, 0, it) }
                val nIn = inl.count { it.toInt() != 0 }
                if (nIn < minInliers) continue
                // the matches must cover a fair part of the card, not one small detail
                var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                sel.forEachIndexed { j, it ->
                    if (inl[j].toInt() != 0) {
                        val x = pts[2 * ti[it]]; val y = pts[2 * ti[it] + 1]
                        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                    }
                }
                val spread = (x1 - x0) * (y1 - y0) / (REF_W * REF_H)
                if (spread < minSpread) continue
                val corners = MatOfPoint2f(Point(0.0, 0.0), Point(REF_W - 1.0, 0.0), Point(REF_W - 1.0, REF_H - 1.0), Point(0.0, REF_H - 1.0))
                val proj = MatOfPoint2f()
                Core.perspectiveTransform(corners, proj, h)
                val p = proj.toArray()
                corners.release(); proj.release()
                val qs = Quad(FloatArray(8) { if (it % 2 == 0) p[it / 2].x.toFloat() else p[it / 2].y.toFloat() })
                if (!quadOk(qs, frameW * s, frameH * s)) continue
                val quad = qs.scaled(1f / s)
                if (hits.any { it.quad.iou(quad) > 0.4f }) continue
                sel.forEachIndexed { j, it -> if (inl[j].toInt() != 0) used[qi[it]] = true }
                hits += KpHit(keys[c], quad, nIn, votes[c], spread, sel.size, (y1 - y0) / REF_H)
            } finally {
                src.release(); dst.release(); mask.release(); h.release()
            }
        }
        hits.sortByDescending { it.inliers }
        return hits
    }

    private fun quadOk(q: Quad, w: Float, h: Float): Boolean {
        if (!q.isConvex()) return false
        val area = q.area
        if (area < 0.004f * w * h || area > 1.6f * w * h) return false
        val s = FloatArray(4) { q.side(it) }
        if (s.min() < 12f) return false
        // opposite sides of a card seen in perspective stay within ~2.5x of each other
        if (max(max(s[0] / s[2], s[2] / s[0]), max(s[1] / s[3], s[3] / s[1])) > 2.5f) return false
        val asp = (s[0] + s[2]) / (s[1] + s[3])
        return asp in 0.4f..1.25f
    }

    companion object {
        private val BF = ThreadLocal.withInitial { BFMatcher.create(Core.NORM_HAMMING, false) }
        private val CLAHE_TL = ThreadLocal.withInitial<CLAHE> { Imgproc.createCLAHE(2.0, Size(8.0, 8.0)) }
        private val REF_ORB = ThreadLocal.withInitial { ORB.create(700, 1.2f, 6, 19, 0, 2, ORB.HARRIS_SCORE, 31, 10) }
        private val FRAME_ORB = ThreadLocal<Pair<Int, ORB>>()

        private fun frameOrb(n: Int): ORB {
            val cur = FRAME_ORB.get()
            if (cur != null && cur.first == n) return cur.second
            val o = ORB.create(n, 1.2f, 8, 19, 0, 2, ORB.HARRIS_SCORE, 31, 8)
            FRAME_ORB.set(n to o)
            return o
        }

        /** Keypoints of a reference image (REF_H x REF_W grey): points and 32-byte descriptors. */
        fun refFeatures(ref: Mat, maxKp: Int = 360): Pair<FloatArray, ByteArray> = matScope {
            val eq = mat()
            CLAHE_TL.get().apply(ref, eq)
            val kp = MatOfKeyPoint().track()
            REF_ORB.get().detect(eq, kp)
            val kept = MatOfKeyPoint(*bucket(kp.toArray(), eq.cols(), eq.rows(), 4, 6, maxKp)).track()
            val des = mat()
            REF_ORB.get().compute(eq, kept, des)
            val k = kept.toArray()
            if (k.isEmpty() || des.empty()) return@matScope FloatArray(0) to ByteArray(0)
            val b = ByteArray(des.rows() * 32).also { des.get(0, 0, it) }
            FloatArray(k.size * 2) { if (it % 2 == 0) k[it / 2].pt.x.toFloat() else k[it / 2].pt.y.toFloat() } to b
        }

        /** Keypoints of a camera frame, in the frame's own pixel coordinates. */
        fun frameFeatures(gray: Mat, maxSide: Int = 960, budget: Int = 2500): FrameFeatures = matScope {
            val h = gray.rows(); val w = gray.cols()
            val s = if (max(h, w) > maxSide) maxSide.toFloat() / max(h, w) else 1f
            val small = if (s != 1f) mat().also { Imgproc.resize(gray, it, Size(), s.toDouble(), s.toDouble(), Imgproc.INTER_AREA) } else gray
            val eq = mat()
            CLAHE_TL.get().apply(small, eq)
            val orb = frameOrb(budget * 2)
            val kp = MatOfKeyPoint().track()
            orb.detect(eq, kp)
            val kept = MatOfKeyPoint(*bucket(kp.toArray(), eq.cols(), eq.rows(), 12, 8, budget)).track()
            val des = Mat()
            orb.compute(eq, kept, des)
            val k = kept.toArray()
            if (k.isEmpty() || des.empty()) { des.release(); return@matScope FrameFeatures(FloatArray(0), Mat(), s) }
            FrameFeatures(FloatArray(k.size * 2) { (if (it % 2 == 0) k[it / 2].pt.x else k[it / 2].pt.y).toFloat() / s }, des, s)
        }

        /** The strongest few keypoints per grid cell, so a busy background can't use up the budget. */
        fun bucket(kps: Array<KeyPoint>, w: Int, h: Int, gx: Int, gy: Int, keep: Int): Array<KeyPoint> {
            if (kps.size <= keep) return kps
            val per = max(2, (keep * 1.6 / (gx * gy)).toInt())
            val byCell = kps.groupBy { k ->
                min(gy - 1, (k.pt.y * gy / h).toInt()) * gx + min(gx - 1, (k.pt.x * gx / w).toInt())
            }
            return byCell.values.flatMap { cell -> cell.sortedByDescending { it.response }.take(per) }
                .sortedByDescending { it.response }.take(keep).toTypedArray()
        }
    }
}

/**
 * Locality-sensitive hashing for 256-bit ORB descriptors: each table hashes a fixed random
 * subset of bits; a query checks its bucket in every table and keeps the nearest by
 * Hamming distance. Approximate, used only to shortlist cards.
 */
class Lsh(private val bytes: ByteArray, n: Int, tables: Int, keyBits: Int, seed: Long = 7) {
    private val words = LongArray(n * 4).also { w ->
        for (i in 0 until n) for (j in 0 until 4) {
            var v = 0L
            for (b in 0 until 8) v = v or ((bytes[i * 32 + j * 8 + b].toLong() and 0xff) shl (8 * b))
            w[i * 4 + j] = v
        }
    }
    private val bits: Array<IntArray>
    private val sortedKeys: Array<IntArray>
    private val sortedIdx: Array<IntArray>

    init {
        val rng = java.util.Random(seed)
        bits = Array(tables) { val all = (0 until 256).shuffled(rng); IntArray(keyBits) { all[it] } }
        sortedKeys = Array(tables) { IntArray(n) }
        sortedIdx = Array(tables) { IntArray(n) }
        val packed = LongArray(n)
        for (t in 0 until tables) {
            for (i in 0 until n) packed[i] = (key(words, i * 4, t).toLong() shl 32) or i.toLong()
            packed.sort()
            for (i in 0 until n) { sortedKeys[t][i] = (packed[i] ushr 32).toInt(); sortedIdx[t][i] = (packed[i] and 0xffffffffL).toInt() }
        }
    }

    private fun key(w: LongArray, at: Int, t: Int): Int {
        var k = 0
        for (p in bits[t]) k = (k shl 1) or ((w[at + (p ushr 6)] ushr (p and 63)) and 1L).toInt()
        return k
    }

    /** Nearest reference descriptor (index, Hamming distance) to query row qi, or (-1, 999). */
    fun nearest(q: ByteArray, qi: Int, maxScan: Int = 48): Pair<Int, Int> {
        val qw = LongArray(4)
        for (j in 0 until 4) {
            var v = 0L
            for (b in 0 until 8) v = v or ((q[qi * 32 + j * 8 + b].toLong() and 0xff) shl (8 * b))
            qw[j] = v
        }
        var best = -1; var bestD = 999
        for (t in bits.indices) {
            val k = key(qw, 0, t)
            val keysT = sortedKeys[t]
            var lo = java.util.Arrays.binarySearch(keysT, k)
            if (lo < 0) continue
            while (lo > 0 && keysT[lo - 1] == k) lo--
            var i = lo; var scanned = 0
            while (i < keysT.size && keysT[i] == k && scanned < maxScan) {
                val r = sortedIdx[t][i] * 4
                val d = java.lang.Long.bitCount(qw[0] xor words[r]) + java.lang.Long.bitCount(qw[1] xor words[r + 1]) +
                    java.lang.Long.bitCount(qw[2] xor words[r + 2]) + java.lang.Long.bitCount(qw[3] xor words[r + 3])
                if (d < bestD) { bestD = d; best = sortedIdx[t][i] }
                i++; scanned++
            }
        }
        return best to bestD
    }
}
