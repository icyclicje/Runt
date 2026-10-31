package com.mivimoose.core

import com.mivimoose.core.catalog.BundleParser
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.vision.RefCard
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.io.DataInputStream
import java.io.File

/** Shared fixtures: the desktop scanner's test cache (tests/.cache in the repo). */
object TestData {
    val cache = File(System.getProperty("cacheDir") ?: "../../tests/.cache")
    val bench = File(cache, "android_bench")

    init {
        nu.pattern.OpenCV.loadLocally()
    }

    val catalog: Catalog by lazy {
        val bundle = File(cache, "data/bundle.js").readText()
        val art = File(cache, "data/pack-art.js").takeIf { it.exists() }?.readText()
        BundleParser.parse(bundle, art)
    }

    /** Reference cards exported by tests/export_android_bench.py, prepared as the app does. */
    fun refs(setId: String): List<RefCard> {
        val f = File(bench, "refs_$setId.bin")
        require(f.exists()) { "missing $f: run python -m tests.export_android_bench tests/.cache first" }
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val n = inp.readInt(); val h = inp.readInt(); val w = inp.readInt()
            return (0 until n).map {
                val key = ByteArray(inp.readUnsignedShort()).also { b -> inp.readFully(b) }.toString(Charsets.UTF_8)
                val px = ByteArray(h * w).also { b -> inp.readFully(b) }
                val m = Mat(h, w, CvType.CV_8UC1); m.put(0, 0, px)
                RefCard.build(key, m).also { m.release() }
            }
        }
    }
}
