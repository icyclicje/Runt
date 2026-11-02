package com.mivimoose.core.vision

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.sqrt

/** A card image the atlas thinks it's looking at; `keys` are the cards printed with it. */
class AtlasHit(val row: Int, val score: Float, val keys: List<String>)

/**
 * Every card in the catalog as one small signature, built into the app: compressed
 * fingerprints, so a card can be looked up among all of them in about a millisecond, with
 * nothing downloaded first. Compressed fingerprints get look-alikes wrong (a third of right
 * names were another print), so this only ever suggests: what's suggested gets checked
 * against the card's printed name or its downloaded image.
 */
class Atlas(data: InputStream, keysText: String) {
    private val n: Int
    private val k: Int
    private val dim: Int
    private val scale: Float
    private val mu: FloatArray
    private val proj: FloatArray
    private val codes: ByteArray
    /** Sign of every signature element, for the first quick pass. */
    private val bits: LongArray
    private val words: Int
    private val rows: Array<List<String>>
    val size get() = n

    init {
        val i = DataInputStream(data.buffered(1 shl 16))
        val magic = ByteArray(4).also { i.readFully(it) }
        require(String(magic) == "ATL1") { "not an atlas" }
        n = i.readInt(); k = i.readInt(); dim = i.readInt(); scale = i.readFloat()
        require(dim == Fingerprint.DIM) { "atlas built for other fingerprints" }
        mu = floats(i, dim)
        proj = floats(i, k * dim)
        codes = ByteArray(n * k).also { i.readFully(it) }
        words = (k + 63) / 64
        bits = LongArray(n * words)
        for (r in 0 until n) {
            val at = r * k
            for (j in 0 until k) if (codes[at + j] > 0) {
                val w = r * words + (j ushr 6)
                bits[w] = bits[w] or (1L shl (j and 63))
            }
        }
        val lines = keysText.split('\n')
        rows = Array(n) { r -> lines.getOrNull(r)?.split('\t') ?: emptyList() }
    }

    private fun floats(i: DataInputStream, count: Int): FloatArray {
        val b = ByteArray(count * 4).also { i.readFully(it) }
        val out = FloatArray(count)
        java.nio.ByteBuffer.wrap(b).asFloatBuffer().get(out)
        return out
    }

    private fun project(fp: FloatArray): FloatArray {
        val c = FloatArray(dim) { fp[it] - mu[it] }
        val q = FloatArray(k)
        var s = 0f
        for (j in 0 until k) {
            val at = j * dim
            var v = 0f
            for (d in 0 until dim) v += c[d] * proj[at + d]
            q[j] = v
            s += v * v
        }
        val norm = 1f / (sqrt(s) + 1e-6f)
        for (j in 0 until k) q[j] *= norm
        return q
    }

    /** The closest card images to these fingerprints (a card upright and upside down), best first. */
    fun search(fingerprints: Array<FloatArray>, top: Int = 40): List<AtlasHit> {
        val best = java.util.HashMap<Int, Float>(PREFILTER * 2)
        val dist = ByteArray(n)
        val counts = IntArray(k + 1)
        for (fp in fingerprints) {
            val q = project(fp)
            val qb = LongArray(words)
            for (j in 0 until k) if (q[j] > 0f) qb[j ushr 6] = qb[j ushr 6] or (1L shl (j and 63))
            java.util.Arrays.fill(counts, 0)
            for (r in 0 until n) {
                var d = 0
                val at = r * words
                for (w in 0 until words) d += java.lang.Long.bitCount(bits[at + w] xor qb[w])
                dist[r] = d.toByte()
                counts[d]++
            }
            var cut = 0
            var kept = 0
            while (cut <= k && kept + counts[cut] < PREFILTER) { kept += counts[cut]; cut++ }
            val limit = cut.toByte()
            for (r in 0 until n) if (dist[r] <= limit) {
                val at = r * k
                var s = 0f
                for (j in 0 until k) s += q[j] * codes[at + j]
                s /= scale
                val old = best[r]
                if (old == null || s > old) best[r] = s
            }
        }
        return best.entries.sortedByDescending { it.value }.take(top).map { AtlasHit(it.key, it.value, rows[it.key]) }
    }

    private companion object {
        /** Images kept by the quick pass, then compared properly. */
        const val PREFILTER = 300
    }
}
