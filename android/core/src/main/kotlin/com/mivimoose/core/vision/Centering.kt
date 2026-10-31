package com.mivimoose.core.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max

/**
 * Card centering: the card is straightened, then on each side the inner edge of the
 * border is found as the strongest edge inside the outer 2-17% of the card (averaged
 * along the middle of that side). Borders give left/right and top/bottom ratios.
 */
object Centering {
    const val W = 630
    const val H = 880

    class Result(
        val left: Float, val right: Float, val top: Float, val bottom: Float,
        /** How clear each inner edge was (0..1); low = full art or blur, treat as unreliable. */
        val confidence: Float,
    ) {
        val lr get() = left / max(1e-3f, left + right)
        val tb get() = top / max(1e-3f, top + bottom)
        /** Worse of the two splits as the larger share, e.g. 0.56 for 56/44. */
        val worst get() = max(max(lr, 1 - lr), max(tb, 1 - tb))

        /** Best PSA grade the front centering allows (PSA's published tolerances). */
        val psaCap: Int
            get() = when {
                worst <= 0.55f -> 10
                worst <= 0.60f -> 9
                worst <= 0.65f -> 8
                worst <= 0.70f -> 7
                worst <= 0.80f -> 6
                worst <= 0.85f -> 5
                else -> 4
            }

        fun split(a: Float) = "%d/%d".format(Math.round(a * 100), 100 - Math.round(a * 100))
    }

    fun measure(gray: Mat, quad: Quad): Result? = matScope {
        val card = Fingerprint.warp(gray, quad, W, H).track()
        val blur = mat()
        Imgproc.GaussianBlur(card, blur, org.opencv.core.Size(3.0, 3.0), 0.0)
        val gx = mat(); val gy = mat()
        Imgproc.Sobel(blur, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(blur, gy, CvType.CV_32F, 0, 1, 3)
        Core.absdiff(gx, org.opencv.core.Scalar(0.0), gx)
        Core.absdiff(gy, org.opencv.core.Scalar(0.0), gy)
        val ax = FloatArray(W * H).also { gx.get(0, 0, it) }
        val ay = FloatArray(W * H).also { gy.get(0, 0, it) }
        // column profile of |dI/dx| over the middle rows, row profile of |dI/dy| over the middle columns
        val colProf = FloatArray(W)
        for (y in (H * 0.25).toInt() until (H * 0.75).toInt()) for (x in 0 until W) colProf[x] += ax[y * W + x]
        val rowProf = FloatArray(H)
        for (y in 0 until H) for (x in (W * 0.25).toInt() until (W * 0.75).toInt()) rowProf[y] += ay[y * W + x]
        fun peak(p: FloatArray, from: Int, to: Int): Pair<Int, Float> {
            var bi = from; var bv = -1f
            for (i in from until to) if (p[i] > bv) { bv = p[i]; bi = i }
            // contrast of the peak against the profile's median in the band
            val band = p.copyOfRange(from, to).sorted()
            val med = band[band.size / 2]
            return bi to ((bv - med) / max(1f, bv)).coerceIn(0f, 1f)
        }
        val (l, cl) = peak(colProf, (W * 0.02).toInt(), (W * 0.17).toInt())
        val (r, cr) = peak(colProf, (W * 0.83).toInt(), (W * 0.98).toInt())
        val (t, ct) = peak(rowProf, (H * 0.02).toInt(), (H * 0.17).toInt())
        val (b, cb) = peak(rowProf, (H * 0.83).toInt(), (H * 0.98).toInt())
        val res = Result(l.toFloat(), (W - 1 - r).toFloat(), t.toFloat(), (H - 1 - b).toFloat(), minOf(minOf(cl, cr), minOf(ct, cb)))
        if (abs(res.left - res.right) > W * 0.12 || abs(res.top - res.bottom) > H * 0.12) null else res
    }
}
