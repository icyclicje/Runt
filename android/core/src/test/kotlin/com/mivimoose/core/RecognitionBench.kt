package com.mivimoose.core

import com.mivimoose.core.vision.CardIndex
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
        var ok = 0; var wrong = 0; var doubles = 0; var n = 0
        val delays = ArrayList<Int>(); val times = ArrayList<Long>()
        for (dir in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val truth = JSONObject(File(dir, "truth.json").readText())
            val key = truth.getString("key")
            val held = truth.getJSONArray("held")
            val tracker = Tracker()
            val confirmed = ArrayList<Pair<String, Int>>()
            val firstHeld = (0 until held.length()).first { held.getBoolean(it) }
            val frames = dir.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
            val log = StringBuilder()
            for ((i, f) in frames.withIndex()) {
                val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
                val a = rec.analyze(g)
                g.release()
                times += a.millis
                val sightings = a.sightings.associate { it.key to it.strength }
                tracker.update(i / 20.0, sightings, a.outlines.isNotEmpty())?.let { confirmed += it to (i - firstHeld) }
                if (verbose && i < held.length() && held.getBoolean(i) && i % 3 == 0) {
                    val s = a.sightings.firstOrNull()
                    log.append("   f$i outlines=${a.outlines.size} " + (s?.let {
                        "best=${if (it.key == key) "RIGHT" else it.key} ${it.source} sc=%.3f m=%.3f inl=%d str=%.2f".format(it.score, it.margin, it.inliers, it.strength) +
                            " truthRank=${it.cands.indexOfFirst { c -> c.key == key }}"
                    } ?: "no sighting") + " sharp=%.0f".format(a.sharpness) + "\n")
                }
            }
            n++
            val mine = confirmed.filter { it.first == key }
            val others = confirmed.filter { it.first != key }
            if (mine.isNotEmpty()) { ok++; delays += mine.first().second }
            if (mine.size > 1) doubles++
            wrong += others.size
            val status = if (mine.isNotEmpty()) "ok after ${mine.first().second} frames" else "MISSED"
            println("${dir.name} ${truth.getString("name")} (${truth.getString("rarity")}): $status" +
                (if (others.isNotEmpty()) "  FALSE: ${others.map { it.first }}" else ""))
            if (verbose && (mine.isEmpty() || others.isNotEmpty())) print(log)
        }
        times.sort()
        println("== $name: scanned $ok/$n, false $wrong, double $doubles; median delay ${delays.sorted().getOrNull(delays.size / 2)} frames; " +
            "frame time median ${times[times.size / 2]} ms, p90 ${times[(times.size * 9) / 10]} ms")
        assertEquals("false scans", 0, wrong)
        assertTrue("scanned $ok/$n", ok >= n * 0.8)
    }
}
