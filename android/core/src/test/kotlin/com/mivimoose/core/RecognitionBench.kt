package com.mivimoose.core

import com.mivimoose.core.vision.CardIndex
import com.mivimoose.core.vision.Guide
import com.mivimoose.core.vision.Recognizer
import com.mivimoose.core.vision.Tracker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Runs the phone pipeline (outline -> fingerprint -> keypoint check -> tracker) over the
 * synthetic phone-camera sequences from tests/export_android_bench.py, at 20 fps.
 * Set -Dbench=<folder name> to pick a sequence folder (default "normal").
 */
class RecognitionBench {
    @Test
    fun scansHandheldCards() {
        val name = System.getProperty("bench") ?: "normal"
        val root = File(TestData.bench, name)
        if (!root.exists()) { println("skipped: no $root"); return }
        val sets = (System.getProperty("benchSets") ?: "sv3pt5,base1,swsh12pt5").split(',')
        val index = CardIndex()
        val t0 = System.currentTimeMillis()
        index.load(sets.flatMap { TestData.refs(it) })
        println("index: ${index.size} cards in ${System.currentTimeMillis() - t0} ms")
        val rec = Recognizer(index)
        val verbose = System.getProperty("benchVerbose") != null
        var ok = 0; var wrong = 0; var doubles = 0; var n = 0; var positives = 0
        // Non-card objects (key ""): frames where the scanner would outline them as a card
        var negFrames = 0; var negShown = 0; var negOutlined = 0
        val delays = ArrayList<Int>(); val times = ArrayList<Long>()
        // sides on edges (0..4) of in-guide sightings, for right and wrong cards
        val sidesRight = IntArray(5); val sidesWrong = IntArray(5)
        // keypoint sightings by the share of the card's height their matches span, in fifths
        val spanRight = IntArray(5); val spanWrong = IntArray(5)
        // outline sightings: margin buckets (<.03 <.06 <.1 <.2 >=.2) and inlier buckets (<12 <20 <30 <50 >=50)
        val edgeMarginRight = IntArray(5); val edgeMarginWrong = IntArray(5); val edgeInlRight = IntArray(5); val edgeInlWrong = IntArray(5)
        fun mb(m: Float) = when { m < 0.03f -> 0; m < 0.06f -> 1; m < 0.1f -> 2; m < 0.2f -> 3; else -> 4 }
        fun ib(n: Int) = when { n < 12 -> 0; n < 20 -> 1; n < 30 -> 2; n < 50 -> 3; else -> 4 }
        for (dir in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val truth = JSONObject(File(dir, "truth.json").readText())
            val key = truth.getString("key")
            if (key.isNotEmpty()) positives++
            val held = truth.getJSONArray("held")
            val tracker = Tracker()
            val confirmed = ArrayList<Pair<String, Int>>()
            val firstHeld = (0 until held.length()).first { held.getBoolean(it) }
            val frames = dir.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
            val log = StringBuilder()
            var shownHere = 0
            for ((i, f) in frames.withIndex()) {
                val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
                val w = g.cols(); val h = g.rows()
                val a = rec.analyze(g)
                g.release()
                times += a.millis
                if (key.isEmpty()) {
                    negFrames++
                    if (Guide.main(a, w, h) != null) { negShown++; shownHere++ }
                    if (a.outlines.any { Guide.holds(it.quad, w, h) }) negOutlined++
                }
                for (s in a.sightings) if (s.strength > 0f && Guide.holds(s.quad, w, h)) {
                    if (s.key == key) sidesRight[s.sides]++ else sidesWrong[s.sides]++
                    if (s.source == "kp") { val b = (s.ySpan * 5).toInt().coerceIn(0, 4); if (s.key == key) spanRight[b]++ else spanWrong[b]++ }
                    else if (s.key == key) { edgeMarginRight[mb(s.margin)]++; edgeInlRight[ib(s.inliers)]++ } else { edgeMarginWrong[mb(s.margin)]++; edgeInlWrong[ib(s.inliers)]++ }
                }
                val sightings = a.sightings.filter { Guide.holds(it.quad, w, h) }.associate { it.key to it.strength }
                tracker.update(i / 20.0, sightings, a.outlines.isNotEmpty())?.let { confirmed += it to (i - firstHeld) }
                if (verbose && ((i < held.length() && held.getBoolean(i) && i % 3 == 0) || (key.isEmpty() && a.sightings.any { it.strength > 0f }))) {
                    val s = a.sightings.firstOrNull()
                    log.append("   f$i outlines=${a.outlines.size} " + (s?.let {
                        "best=${if (it.key == key) "RIGHT" else it.key} ${it.source} sc=%.3f m=%.3f inl=%d str=%.2f".format(it.score, it.margin, it.inliers, it.strength) +
                            " truthRank=${it.cands.indexOfFirst { c -> c.key == key }} sides=${it.sides} span=%.2f area=%.2f".format(it.ySpan, it.quad.area / (w * h))
                    } ?: "no sighting") + " sharp=%.0f".format(a.sharpness) + "\n")
                }
            }
            n++
            val mine = confirmed.filter { it.first == key }
            val others = confirmed.filter { it.first != key }
            if (mine.isNotEmpty()) { ok++; delays += mine.first().second }
            if (mine.size > 1) doubles++
            wrong += others.size
            val status = if (key.isEmpty()) "not a card, shown as a card in $shownHere frames"
                else if (mine.isNotEmpty()) "ok after ${mine.first().second} frames" else "MISSED"
            println("${dir.name} ${truth.getString("name")} (${truth.getString("rarity")}): $status" +
                (if (others.isNotEmpty()) "  FALSE: ${others.map { it.first }}" else ""))
            if (verbose && (mine.isEmpty() || others.isNotEmpty())) print(log)
        }
        times.sort()
        println("== $name: scanned $ok/$n, false $wrong, double $doubles; median delay ${delays.sorted().getOrNull(delays.size / 2)} frames; " +
            "frame time median ${times[times.size / 2]} ms, p90 ${times[(times.size * 9) / 10]} ms")
        println("== sides on edges, right card: ${sidesRight.toList()}, wrong card or not a card: ${sidesWrong.toList()}")
        println("== keypoint height span (fifths), right card: ${spanRight.toList()}, wrong card or not a card: ${spanWrong.toList()}")
        println("== outline sightings margin, right: ${edgeMarginRight.toList()}, wrong: ${edgeMarginWrong.toList()}; inliers, right: ${edgeInlRight.toList()}, wrong: ${edgeInlWrong.toList()}")
        if (negFrames > 0) println("== non-cards: shown as a card in $negShown/$negFrames frames (outline found in the guide: $negOutlined)")
        assertEquals("false scans", 0, wrong)
        assertTrue("scanned $ok/$positives", ok >= positives * 0.8)
    }
}
