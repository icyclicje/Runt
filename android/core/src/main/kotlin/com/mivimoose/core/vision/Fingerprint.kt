package com.mivimoose.core.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lighting-independent fingerprints of straightened cards (port of vision.describe):
 * the whole card at low resolution, the artwork window, and a grid of edge-direction
 * histograms. dot(a, b) of two fingerprints is a weighted correlation in [-1, 1].
 */
object Fingerprint {
    private const val MARGIN = 0.035f
    private const val W_INT = 0.35f
    private const val W_ART = 0.20f
    private const val W_HOG = 0.45f
    const val DIM = 24 * 34 + 36 * 24 + 6 * 8 * 9

    private val DST = MatOfPoint2f(
        org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point(CANON_W - 1.0, 0.0),
        org.opencv.core.Point(CANON_W - 1.0, CANON_H - 1.0), org.opencv.core.Point(0.0, CANON_H - 1.0),
    )

    /** The card inside `quad` straightened to w x h. Large cards are shrunk first so the
     *  warp doesn't alias. */
    fun warp(gray: Mat, quad: Quad, w: Int = CANON_W, h: Int = CANON_H): Mat = matScope {
        val cardH = (quad.side(1) + quad.side(3)) / 2f
        var src = gray
        var q = quad
        if (cardH > 2.2f * h) {
            val s = 1.5f * h / cardH
            src = mat()
            Imgproc.resize(gray, src, Size(), s.toDouble(), s.toDouble(), Imgproc.INTER_AREA)
            q = quad.scaled(s)
        }
        val dst = if (w == CANON_W && h == CANON_H) DST else MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point(w - 1.0, 0.0),
            org.opencv.core.Point(w - 1.0, h - 1.0), org.opencv.core.Point(0.0, h - 1.0),
        ).track()
        val srcPts = q.toMat2f().track()
        val m = Imgproc.getPerspectiveTransform(srcPts, dst).track()
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        out
    }

    fun describe(canon: Mat): FloatArray = matScope {
        val h = canon.rows(); val w = canon.cols()
        val mx = (w * MARGIN).toInt(); val my = (h * MARGIN).toInt()
        val inner = mat()
        Imgproc.GaussianBlur(canon.submat(Rect(mx, my, w - 2 * mx, h - 2 * my)).track(), inner, Size(3.0, 3.0), 0.0)
        val ih = inner.rows()
        val a = mat()
        Imgproc.resize(inner, a, Size(24.0, 34.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val artRoi = inner.submat(Rect(0, (ih * 0.07).toInt(), inner.cols(), (ih * 0.52).toInt() - (ih * 0.07).toInt())).track()
        val art = mat()
        Imgproc.resize(artRoi, art, Size(36.0, 24.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val hogSrc = mat()
        Imgproc.resize(inner, hogSrc, Size(96.0, 128.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val g = hog(hogSrc)
        val out = FloatArray(DIM)
        var o = 0
        for ((v, wt) in listOf(bytes(a) to W_INT, bytes(art) to W_ART, g to W_HOG)) {
            unitInto(v, sqrt(wt), out, o)
            o += v.size
        }
        out
    }

    private fun bytes(m: Mat): FloatArray {
        val b = ByteArray((m.total() * m.channels()).toInt())
        m.get(0, 0, b)
        return FloatArray(b.size) { (b[it].toInt() and 0xff).toFloat() }
    }

    private fun unitInto(v: FloatArray, weight: Float, out: FloatArray, at: Int) {
        var mean = 0.0
        for (x in v) mean += x
        mean /= v.size
        var n = 0.0
        for (x in v) n += (x - mean) * (x - mean)
        val s = if (n > 1e-12) (weight / sqrt(n)).toFloat() else 0f
        for (i in v.indices) out[at + i] = ((v[i] - mean) * s).toFloat()
    }

    /** Edge-direction histograms on a 6 x 8 grid, 9 bins, per-cell contrast normalised. */
    private fun hog(img: Mat, cellsX: Int = 6, cellsY: Int = 8, bins: Int = 9): FloatArray = matScope {
        val gx = mat(); val gy = mat(); val mag = mat(); val ang = mat()
        Imgproc.Sobel(img, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(img, gy, CvType.CV_32F, 0, 1, 3)
        Core.cartToPolar(gx, gy, mag, ang, false)
        val hh = img.rows(); val ww = img.cols()
        val m = FloatArray(hh * ww); mag.get(0, 0, m)
        val a = FloatArray(hh * ww); ang.get(0, 0, a)
        val hist = FloatArray(cellsX * cellsY * bins)
        for (y in 0 until hh) {
            val cy = y * cellsY / hh
            for (x in 0 until ww) {
                val i = y * ww + x
                var t = a[i] % PI.toFloat()
                if (t < 0) t += PI.toFloat()
                val b = min((t * (bins / PI.toFloat())).toInt(), bins - 1)
                hist[((cy * cellsX) + x * cellsX / ww) * bins + b] += m[i]
            }
        }
        val cells = cellsX * cellsY
        val norms = FloatArray(cells) { c ->
            var s = 0f
            for (b in 0 until bins) s += hist[c * bins + b] * hist[c * bins + b]
            sqrt(s)
        }
        val mean = norms.average().toFloat()
        for (c in 0 until cells) {
            val d = norms[c] + 0.15f * mean + 1e-6f
            for (b in 0 until bins) hist[c * bins + b] /= d
        }
        hist
    }

    /** Fingerprints of the card upright and upside down, at two crop sizes (absorbs an
     *  outline that is slightly too tight). Rows: [upright, flipped, upright 1.05, flipped 1.05]. */
    fun queryDescriptors(gray: Mat, quad: Quad, expands: FloatArray = floatArrayOf(1f, 1.05f)): Pair<Array<FloatArray>, Mat> {
        val rows = ArrayList<FloatArray>()
        var canon0: Mat? = null
        for (f in expands) {
            val c = warp(gray, if (f != 1f) quad.expanded(f) else quad)
            rows += describe(c)
            val r = Mat()
            Core.rotate(c, r, Core.ROTATE_180)
            rows += describe(r)
            r.release()
            if (canon0 == null) canon0 = c else c.release()
        }
        return rows.toTypedArray() to canon0!!
    }

    fun sharpness(canon: Mat): Float = matScope {
        val lap = mat()
        Imgproc.Laplacian(canon, lap, CvType.CV_32F)
        val mean = org.opencv.core.MatOfDouble().track(); val std = org.opencv.core.MatOfDouble().track()
        Core.meanStdDev(lap, mean, std)
        val s = std.toArray()[0]
        (s * s).toFloat()
    }

    /** Share of the card blown out to white (shiny cards under a lamp). */
    fun glare(canon: Mat): Float = matScope {
        val t = mat()
        Imgproc.threshold(canon, t, 249.0, 255.0, Imgproc.THRESH_BINARY)
        Core.countNonZero(t).toFloat() / max(1, canon.rows() * canon.cols())
    }

    /** A downloaded card scan as a straightened grey canon; transparent corners become mid-grey. */
    fun referenceCanon(img: Mat): Mat = matScope {
        val gray = mat()
        when (img.channels()) {
            4 -> {
                val bg = Mat(img.size(), CvType.CV_8UC3, Scalar(200.0, 200.0, 200.0)).track()
                val planes = ArrayList<Mat>()
                Core.split(img, planes)
                planes.forEach { it.track() }
                val alpha = mat(); planes[3].convertTo(alpha, CvType.CV_32F, 1.0 / 255)
                val rgb = mat(); Core.merge(planes.subList(0, 3), rgb)
                val rgbF = mat(); rgb.convertTo(rgbF, CvType.CV_32FC3)
                val bgF = mat(); bg.convertTo(bgF, CvType.CV_32FC3)
                val a3 = mat(); Core.merge(listOf(alpha, alpha, alpha), a3)
                val inv = mat(); Core.subtract(Mat.ones(a3.size(), a3.type()).track(), a3, inv)
                val p1 = mat(); Core.multiply(rgbF, a3, p1)
                val p2 = mat(); Core.multiply(bgF, inv, p2)
                val sum = mat(); Core.add(p1, p2, sum)
                val u8 = mat(); sum.convertTo(u8, CvType.CV_8UC3)
                Imgproc.cvtColor(u8, gray, Imgproc.COLOR_BGR2GRAY)
            }
            3 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            else -> img.copyTo(gray)
        }
        val out = Mat()
        val interp = if (gray.rows() >= CANON_H) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.resize(gray, out, Size(CANON_W.toDouble(), CANON_H.toDouble()), 0.0, 0.0, interp)
        out
    }
}
