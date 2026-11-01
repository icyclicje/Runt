package com.mivimoose.core.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A card-shaped outline found in a frame, with how card-like it looks (0..1). */
class Outline(val quad: Quad, val score: Float)

/** The frame's thin edge pixels, for snapping outlines found some other way onto real edges. */
class EdgeMap(val pts: FloatArray, val scale: Float) {
    fun snap(q: Quad): Quad = if (pts.isEmpty()) q else CardFinder.refine(q.scaled(scale), pts).scaled(1f / scale)

    /** How many of the quad's sides run along a straight edge in the frame (0..4). A card held
     *  up shows its edges; its artwork on a pack, a poster or a screen doesn't have them where
     *  the card's outline would be. */
    fun sides(q: Quad): Int = if (pts.isEmpty()) 0 else CardFinder.sidesOnEdges(q.scaled(scale), pts)
}

/**
 * Finds card outlines: several edge passes (contrast-adaptive, normal, strict),
 * contours approximated to four corners, then each side snapped onto the straight
 * edge pixels next to it, so corners hidden under fingers or glare still land
 * where the card's edges meet. Port of the desktop scanner's vision.find_cards.
 */
object CardFinder {
    const val WORK_SIZE = 640

    fun find(gray: Mat, maxCandidates: Int = 5, minArea: Float = 0.02f, edgesOut: MutableList<EdgeMap>? = null): List<Outline> = matScope {
        val h = gray.rows(); val w = gray.cols()
        val scale = if (max(h, w) > WORK_SIZE) WORK_SIZE.toFloat() / max(h, w) else 1f
        val small = if (scale != 1f) mat().also { Imgproc.resize(gray, it, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA) } else gray
        val sh = small.rows(); val sw = small.cols()
        val frameArea = (sh * sw).toFloat()
        val blur = mat()
        Imgproc.GaussianBlur(small, blur, Size(5.0, 5.0), 0.0)

        val gx = mat(); val gy = mat(); val mag = mat()
        Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(gx, gy, mag)
        val hiAuto = percentile(mag, 0.9f).coerceIn(24f, 400f)
        val passes = arrayOf(floatArrayOf(0.35f * hiAuto, hiAuto), floatArrayOf(30f, 90f), floatArrayOf(60f, 170f))

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).track()
        val cands = ArrayList<Outline>()
        var edgePts: FloatArray? = null
        val edges = mat()
        for (pass in passes) {
            Imgproc.Canny(blur, edges, pass[0].toDouble(), pass[1].toDouble(), 3, true)
            if (edgePts == null) edgePts = nonZero(edges)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            val contours = ArrayList<MatOfPoint>()
            val hier = mat()
            Imgproc.findContours(edges, contours, hier, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            for (cnt in contours) {
                try {
                    val area = Imgproc.contourArea(cnt)
                    if (area < minArea * frameArea || area > 0.97 * frameArea) continue
                    val q = quadFromContour(cnt, frameArea) ?: continue
                    cands += q
                } finally {
                    cnt.release()
                }
            }
        }
        edgesOut?.add(EdgeMap(edgePts ?: FloatArray(0), scale))

        cands.sortByDescending { it.score }
        val out = ArrayList<Outline>()
        val tol = 0.025f * max(sh, sw)
        for (c in cands) {
            if (out.none { it.quad.maxDist(c.quad) < tol }) {
                out += c
                if (out.size >= maxCandidates) break
            }
        }
        // Snap each candidate onto straight edges; keep the rough one too when they differ:
        // the recogniser picks whichever matches better.
        val refined = ArrayList<Outline>()
        val pts = edgePts ?: FloatArray(0)
        for (c in out) {
            val r = if (pts.isNotEmpty()) refine(c.quad, pts) else c.quad
            refined += Outline(r, c.score)
            if (r.maxDist(c.quad) > 0.006f * max(sh, sw)) refined += Outline(c.quad, c.score * 0.98f)
        }
        refined.map { Outline(it.quad.scaled(1f / scale), it.score) }
    }

    private fun MatScope.quadFromContour(cnt: MatOfPoint, frameArea: Float): Outline? {
        val hullIdx = MatOfInt().track()
        Imgproc.convexHull(cnt, hullIdx)
        val all = cnt.toArray()
        val hullPts = hullIdx.toArray().map { all[it] }
        if (hullPts.size < 3) return null
        val hull2f = MatOfPoint2f(*hullPts.toTypedArray()).track()
        val hullArea = Imgproc.contourArea(hull2f).toFloat()
        val peri = Imgproc.arcLength(hull2f, true)
        var quad: Quad? = null
        val approx = MatOfPoint2f().track()
        for (eps in doubleArrayOf(0.015, 0.025, 0.04, 0.06)) {
            Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
            if (approx.rows() == 4) {
                val a = approx.toArray()
                quad = Quad.ordered(FloatArray(8) { if (it % 2 == 0) a[it / 2].x.toFloat() else a[it / 2].y.toFloat() })
                break
            }
        }
        if (quad == null) {                              // rounded or partly covered corners
            val box = arrayOfNulls<Point>(4)
            Imgproc.minAreaRect(hull2f).points(box)
            quad = Quad.ordered(FloatArray(8) { if (it % 2 == 0) box[it / 2]!!.x.toFloat() else box[it / 2]!!.y.toFloat() })
        }
        val qa = quad.area
        if (qa <= 0f) return null
        val fill = min(hullArea, qa) / max(hullArea, qa)          // how rectangular the outline is
        val aspErr = abs(quad.aspect - CARD_ASPECT) / CARD_ASPECT
        if (fill < 0.8f || aspErr > 0.3f) return null
        return Outline(quad, (qa / frameArea).pow(0.35f) * fill * (1 - aspErr))
    }

    /** Snap a rough quad onto the card's real edges (a wide pass, then a tight one). */
    fun refine(q: Quad, edgePts: FloatArray): Quad = refineOnce(refineOnce(q, edgePts, 0.09f), edgePts, 0.03f)

    private fun refineOnce(q: Quad, pts: FloatArray, bandFrac: Float): Quad {
        val size = sqrt(q.area)
        val band = max(3f, bandFrac * size)
        val lines = Array(4) { fitSide(pts, q.x(it), q.y(it), q.x((it + 1) % 4), q.y((it + 1) % 4), band) }
        if (lines.count { it != null } < 3) return q
        val out = q.p.copyOf()
        for (i in 0 until 4) {                           // corner i joins side i-1 and side i
            val la = lines[(i + 3) % 4] ?: continue
            val lb = lines[i] ?: continue
            val p = intersect(la, lb) ?: continue
            if (hypot(p[0] - q.x(i), p[1] - q.y(i)) < 0.25f * size) {
                out[2 * i] = p[0]; out[2 * i + 1] = p[1]
            }
        }
        val r = Quad(out)
        if (!r.isConvex()) return q
        val ratio = r.area / max(1f, q.area)
        if (ratio !in 0.75f..1.3f || abs(r.aspect - CARD_ASPECT) / CARD_ASPECT > 0.3f) return q
        return r
    }

    fun sidesOnEdges(q: Quad, pts: FloatArray): Int {
        val size = sqrt(q.area)
        val band = max(3f, 0.03f * size)
        var n = 0
        for (i in 0 until 4) {
            val x0 = q.x(i); val y0 = q.y(i); val x1 = q.x((i + 1) % 4); val y1 = q.y((i + 1) % 4)
            val l = fitSide(pts, x0, y0, x1, y1, band) ?: continue
            val len = hypot(x1 - x0, y1 - y0)
            val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
            // the line found must be the side itself: same direction, through its middle
            if (abs(ux * l[3] - uy * l[2]) > 0.08f) continue
            val mx = (x0 + x1) / 2 - l[0]; val my = (y0 + y1) / 2 - l[1]
            if (abs(mx * l[3] - my * l[2]) > band) continue
            n++
        }
        return n
    }

    /** A straight line along one side (point + unit direction), robustly fitted to the edge
     *  pixels near it; null when too little of that side is visible. */
    private fun fitSide(pts: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float, band: Float): FloatArray? {
        val dx = x1 - x0; val dy = y1 - y0
        val length = hypot(dx, dy)
        if (length < 10f) return null
        val ux = dx / length; val uy = dy / length
        val nx = -uy; val ny = ux
        val n = pts.size / 2
        var sel = FloatArray(256); var ns = 0
        for (k in 0 until n) {
            val rx = pts[2 * k] - x0; val ry = pts[2 * k + 1] - y0
            val t = (rx * ux + ry * uy) / length
            if (t <= 0.06f || t >= 0.94f) continue
            if (abs(rx * nx + ry * ny) >= band) continue
            if (2 * ns + 2 > sel.size) sel = sel.copyOf(sel.size * 2)
            sel[2 * ns] = pts[2 * k]; sel[2 * ns + 1] = pts[2 * k + 1]; ns++
        }
        if (ns < max(12f, 0.2f * length)) return null
        // RANSAC: 48 random lines, keep the one most edge pixels sit on
        val rng = Random(1)
        var bestCount = -1; var bax = 0f; var bay = 0f; var bnx = 0f; var bny = 0f
        repeat(48) {
            val i = rng.nextInt(ns); val j = rng.nextInt(ns)
            val ax = sel[2 * i]; val ay = sel[2 * i + 1]
            val ddx = sel[2 * j] - ax; val ddy = sel[2 * j + 1] - ay
            val dn = hypot(ddx, ddy)
            if (dn <= 0.15f * length) return@repeat
            val lnx = -ddy / dn; val lny = ddx / dn
            var c = 0
            for (k in 0 until ns) if (abs((sel[2 * k] - ax) * lnx + (sel[2 * k + 1] - ay) * lny) < 1.6f) c++
            if (c > bestCount) { bestCount = c; bax = ax; bay = ay; bnx = lnx; bny = lny }
        }
        if (bestCount < max(10f, 0.15f * length)) return null
        // Total least squares through the inliers
        var mx = 0.0; var my = 0.0; var m = 0
        for (k in 0 until ns) {
            if (abs((sel[2 * k] - bax) * bnx + (sel[2 * k + 1] - bay) * bny) < 1.6f) { mx += sel[2 * k]; my += sel[2 * k + 1]; m++ }
        }
        mx /= m; my /= m
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (k in 0 until ns) {
            val px = sel[2 * k]; val py = sel[2 * k + 1]
            if (abs((px - bax) * bnx + (py - bay) * bny) < 1.6f) {
                val ex = px - mx; val ey = py - my
                sxx += ex * ex; syy += ey * ey; sxy += ex * ey
            }
        }
        val theta = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
        return floatArrayOf(mx.toFloat(), my.toFloat(), kotlin.math.cos(theta).toFloat(), kotlin.math.sin(theta).toFloat())
    }

    private fun intersect(a: FloatArray, b: FloatArray): FloatArray? {
        val den = a[2] * b[3] - a[3] * b[2]
        if (abs(den) < 1e-6f) return null
        val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den
        return floatArrayOf(a[0] + t * a[2], a[1] + t * a[3])
    }

    private fun nonZero(edges: Mat): FloatArray {
        val loc = MatOfPoint()
        try {
            Core.findNonZero(edges, loc)
            val n = loc.rows()
            if (n == 0) return FloatArray(0)
            val ints = IntArray(n * 2)
            loc.get(0, 0, ints)
            return FloatArray(n * 2) { ints[it].toFloat() }
        } finally {
            loc.release()
        }
    }

    /** Approximate percentile of a float image (sampled). */
    fun percentile(m: Mat, q: Float): Float {
        val n = (m.total() * m.channels()).toInt()
        val all = FloatArray(n)
        m.get(0, 0, all)
        val step = max(1, n / 60000)
        val sample = FloatArray((n + step - 1) / step) { all[it * step] }
        sample.sort()
        return sample[((sample.size - 1) * q).toInt()]
    }
}
