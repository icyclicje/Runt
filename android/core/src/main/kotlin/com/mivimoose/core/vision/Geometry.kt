package com.mivimoose.core.vision

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

const val CANON_W = 200
const val CANON_H = 280
const val CARD_ASPECT = 63f / 88f          // width / height of a standard card

/** Releases every Mat created through it when the block ends (OpenCV memory is native). */
class MatScope : AutoCloseable {
    private val mats = ArrayList<Mat>()
    fun <M : Mat> M.track(): M { mats.add(this); return this }
    fun mat(): Mat = Mat().track()
    override fun close() {
        for (m in mats) m.release()
        mats.clear()
    }
}

inline fun <T> matScope(block: MatScope.() -> T): T = MatScope().use { it.block() }

/** A card outline: four corners TL, TR, BR, BL as x0,y0,x1,y1,... in pixels. */
class Quad(val p: FloatArray) {
    init { require(p.size == 8) }

    fun x(i: Int) = p[2 * i]
    fun y(i: Int) = p[2 * i + 1]
    val cx get() = (p[0] + p[2] + p[4] + p[6]) / 4f
    val cy get() = (p[1] + p[3] + p[5] + p[7]) / 4f

    fun side(i: Int): Float {
        val j = (i + 1) % 4
        return hypot(x(j) - x(i), y(j) - y(i))
    }

    /** width / height, averaged over opposite sides. */
    val aspect: Float
        get() {
            val top = (side(0) + side(2)) / 2f
            val sides = (side(1) + side(3)) / 2f
            return if (sides > 0) top / sides else 0f
        }

    val area: Float
        get() {
            var a = 0f
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                a += x(i) * y(j) - x(j) * y(i)
            }
            return abs(a) / 2f
        }

    fun scaled(s: Float) = Quad(FloatArray(8) { p[it] * s })
    fun translated(dx: Float, dy: Float) = Quad(FloatArray(8) { if (it % 2 == 0) p[it] + dx else p[it] + dy })

    fun expanded(f: Float): Quad {
        val cx = cx; val cy = cy
        return Quad(FloatArray(8) { if (it % 2 == 0) cx + (p[it] - cx) * f else cy + (p[it] - cy) * f })
    }

    /** Largest corner distance to another quad (same corner order). */
    fun maxDist(o: Quad): Float {
        var d = 0f
        for (i in 0 until 8) d = max(d, abs(p[i] - o.p[i]))
        return d
    }

    /** Overlap of the two outlines' bounding boxes, intersection over union. */
    fun iou(o: Quad): Float {
        val (ax0, ay0, ax1, ay1) = bounds()
        val (bx0, by0, bx1, by1) = o.bounds()
        val iw = minOf(ax1, bx1) - max(ax0, bx0)
        val ih = minOf(ay1, by1) - max(ay0, by0)
        if (iw <= 0 || ih <= 0) return 0f
        val inter = iw * ih
        return inter / ((ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter)
    }

    fun bounds(): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until 4) {
            x0 = minOf(x0, x(i)); x1 = max(x1, x(i)); y0 = minOf(y0, y(i)); y1 = max(y1, y(i))
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    fun points(): Array<Point> = Array(4) { Point(x(it).toDouble(), y(it).toDouble()) }
    fun toMat2f(): MatOfPoint2f = MatOfPoint2f(*points())
    fun toMatOfPoint(): MatOfPoint = MatOfPoint(*Array(4) { Point(x(it).toDouble(), y(it).toDouble()) })

    fun isConvex(): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4; val k = (i + 2) % 4
            val cross = (x(j) - x(i)) * (y(k) - y(j)) - (y(j) - y(i)) * (x(k) - x(j))
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    override fun toString() = p.joinToString(",", "Quad(", ")") { "%.1f".format(it) }

    companion object {
        /** Corners as TL, TR, BR, BL with the long side vertical (portrait). */
        fun ordered(pts: FloatArray): Quad {
            val cx = (pts[0] + pts[2] + pts[4] + pts[6]) / 4f
            val cy = (pts[1] + pts[3] + pts[5] + pts[7]) / 4f
            val idx = (0 until 4).sortedBy { atan2(pts[2 * it + 1] - cy, pts[2 * it] - cx) }
            var o = FloatArray(8)
            idx.forEachIndexed { n, i -> o[2 * n] = pts[2 * i]; o[2 * n + 1] = pts[2 * i + 1] }
            val start = (0 until 4).minBy { o[2 * it] + o[2 * it + 1] }
            o = roll(o, start)
            val q = Quad(o)
            val top = q.side(0) + q.side(2)
            val sides = q.side(1) + q.side(3)
            return if (top > sides) Quad(roll(o, 1)) else q      // card lying sideways
        }

        private fun roll(o: FloatArray, by: Int) = FloatArray(8) { o[(it + 2 * by) % 8] }

        /** The card-shaped guide box in the middle of a w x h frame. */
        fun guide(w: Int, h: Int, size: Float = 0.8f): Quad {
            var ch = h * size
            var cw = ch * CARD_ASPECT
            if (cw > w * 0.95f) { cw = w * 0.95f; ch = cw / CARD_ASPECT }
            val x0 = (w - cw) / 2f; val y0 = (h - ch) / 2f
            return Quad(floatArrayOf(x0, y0, x0 + cw, y0, x0 + cw, y0 + ch, x0, y0 + ch))
        }
    }
}
