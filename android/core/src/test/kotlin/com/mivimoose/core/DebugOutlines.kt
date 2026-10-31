package com.mivimoose.core

import com.mivimoose.core.vision.CardFinder
import org.junit.Test
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Draws the outlines found in chosen frames: -PdebugFrames=normal/015/012,normal/001/015 */
class DebugOutlines {
    @Test
    fun drawOutlines() {
        val spec = System.getProperty("debugFrames") ?: return
        val out = File(TestData.bench, "debug").apply { mkdirs() }
        for (s in spec.split(',')) {
            val f = File(TestData.bench, "$s.jpg")
            val img = Imgcodecs.imread(f.path)
            val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val found = CardFinder.find(g, maxCandidates = 4, minArea = 0.04f)
            found.forEachIndexed { i, o ->
                val pts = MatOfPoint(*o.quad.points().map { Point(it.x, it.y) }.toTypedArray())
                val col = if (i == 0) Scalar(0.0, 255.0, 0.0) else Scalar(0.0, 128.0, 255.0)
                Imgproc.polylines(img, listOf(pts), true, col, if (i == 0) 5 else 2)
                Imgproc.putText(img, "$i %.2f".format(o.score), o.quad.points()[0], Imgproc.FONT_HERSHEY_SIMPLEX, 1.2, col, 3)
            }
            val dst = File(out, s.replace('/', '_') + ".jpg")
            Imgcodecs.imwrite(dst.path, img)
            println("$s: ${found.size} outlines -> $dst")
        }
    }
}
