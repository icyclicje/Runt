package com.mivimoose.core

import com.mivimoose.core.vision.Atlas
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class AtlasTest {
    @Test
    fun findsACardAmongAllOfThem() {
        val dir = File(TestData.cache, "../../android/app/src/main/assets/data")
        val keys = GZIPInputStream(File(dir, "atlas_keys.txt.gz").inputStream()).bufferedReader().readText()
        val t0 = System.nanoTime()
        val atlas = File(dir, "atlas.bin").inputStream().use { Atlas(it, keys) }
        println("atlas: ${atlas.size} images, loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val refs = TestData.refs("sv3pt5").take(60)
        var right = 0
        val t1 = System.nanoTime()
        for (r in refs) {
            val hits = atlas.search(arrayOf(r.desc))
            // first, or right behind its twin in another language (Indonesian 151 looks just the same)
            if (hits.take(2).any { r.key in it.keys }) right++
        }
        println("atlas: right card in the top two for $right/${refs.size}, ${(System.nanoTime() - t1) / 1000 / refs.size} us per search")
        assertTrue("$right/${refs.size}", right >= refs.size * 0.95)
    }
}
