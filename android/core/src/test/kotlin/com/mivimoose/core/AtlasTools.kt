package com.mivimoose.core

import com.mivimoose.core.vision.CardFinder
import com.mivimoose.core.vision.Fingerprint
import com.mivimoose.core.vision.Guide
import com.mivimoose.core.vision.REF_H
import com.mivimoose.core.vision.REF_W
import org.json.JSONObject
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the data behind the app's built-in card index: every card's fingerprint from the
 * images tests/fetch_all_refs.py downloaded, and fingerprints of the outlines in the test
 * footage, for tests/build_atlas.py to compress and evaluate. Off unless -Patlas=... is given.
 */
class AtlasTools {
    private val dir = File(TestData.cache, "atlas")
    private fun nameOf(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) + ".jpg"

    @Test
    fun fingerprints() {
        if (System.getProperty("atlas") != "fingerprints") return
        val c = TestData.catalog
        val rows = LinkedHashMap<String, MutableList<String>>()       // image -> cards showing it
        for (s in c.sets.values) if (!s.firstEdition) for (card in s.cards) {
            if (card.noImage || card.img.isEmpty()) continue
            val f = nameOf(card.img)
            if (File(dir, "img/$f").exists()) rows.getOrPut(f) { ArrayList() } += card.key
        }
        val names = rows.keys.toList()
        println("atlas: ${names.size} images for ${rows.values.sumOf { it.size }} cards")
        val out = FloatArray(names.size * Fingerprint.DIM)
        val pool = Executors.newFixedThreadPool(3)
        val done = AtomicInteger()
        for ((i, n) in names.withIndex()) pool.execute {
            val g = Imgcodecs.imread(File(dir, "img/$n").path, Imgcodecs.IMREAD_GRAYSCALE)
            val canon = Mat()
            Imgproc.resize(g, canon, Size(com.mivimoose.core.vision.CANON_W.toDouble(), com.mivimoose.core.vision.CANON_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            System.arraycopy(Fingerprint.describe(canon), 0, out, i * Fingerprint.DIM, Fingerprint.DIM)
            g.release(); canon.release()
            if (done.incrementAndGet() % 5000 == 0) println("atlas: ${done.get()}")
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.HOURS)
        DataOutputStream(File(dir, "fp.bin").outputStream().buffered(1 shl 20)).use { o ->
            o.writeInt(names.size); o.writeInt(Fingerprint.DIM)
            val bb = java.nio.ByteBuffer.allocate(Fingerprint.DIM * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in names.indices) { bb.clear(); bb.asFloatBuffer().put(out, i * Fingerprint.DIM, Fingerprint.DIM); o.write(bb.array()) }
        }
        File(dir, "rows.txt").writeText(names.joinToString("\n") { n -> rows[n]!!.joinToString("\t") })
        println("atlas: wrote fp.bin, rows.txt")
    }

    /** Fingerprints of what the outline finder sees in the footage, with the truth. */
    @Test
    fun queries() {
        val benches = System.getProperty("atlas")?.takeIf { it.startsWith("queries:") }?.substringAfter(':')?.split(',') ?: return
        for (b in benches) {
            val root = File(TestData.bench, b)
            DataOutputStream(File(dir, "q_$b.bin").outputStream().buffered(1 shl 20)).use { o ->
                var n = 0
                val bb = java.nio.ByteBuffer.allocate(Fingerprint.DIM * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for ((si, d) in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.withIndex()) {
                    val truth = JSONObject(File(d, "truth.json").readText())
                    val key = truth.getString("key")
                    val held = truth.getJSONArray("held")
                    val frames = d.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
                    for ((fi, f) in frames.withIndex()) {
                        val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
                        val outs = CardFinder.find(g, maxCandidates = 4, minArea = 0.04f).filter { Guide.holds(it.quad, g.cols(), g.rows()) }.take(2)
                        for ((oi, q) in outs.withIndex()) {
                            val (rows, canon) = Fingerprint.queryDescriptors(g, q.quad, floatArrayOf(1f))
                            canon.release()
                            o.writeUTF(key); o.writeInt(si); o.writeInt(fi); o.writeInt(oi); o.writeBoolean(fi < held.length() && held.getBoolean(fi))
                            for (r in rows) { bb.clear(); bb.asFloatBuffer().put(r); o.write(bb.array()) }
                            n++
                        }
                        g.release()
                    }
                }
                println("atlas: $b: $n outline queries")
            }
        }
    }
}
