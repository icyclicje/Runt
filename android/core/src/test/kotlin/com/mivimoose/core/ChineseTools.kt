package com.mivimoose.core

import com.mivimoose.core.catalog.Chinese
import com.mivimoose.core.catalog.ChineseCards
import com.mivimoose.core.net.JavaHttp
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Builds the Chinese card lists built into the app (assets/data/zh_sets.json.gz). Off unless -Patlas=zh. */
class ChineseTools {
    private fun isSpecial(s: Chinese.RawSet) = s.id.startsWith("zh-cn-151C") || s.id.startsWith("zh-cn-30th")

    @Test
    fun build() {
        if (System.getProperty("atlas") != "zh") return
        val client = ChineseCards(JavaHttp())
        val known = TestData.catalog.sets.keys
        val out = java.util.Collections.synchronizedList(ArrayList<Chinese.RawSet>())
        // A Traditional set is hundreds of pages and never changes: only new ones are fetched
        val file = File(TestData.cache, "../../android/app/src/main/assets/data/zh_sets.json.gz")
        val old = if (file.exists()) Chinese.fromJson(GZIPInputStream(file.inputStream()).bufferedReader().readText()).filter { it.lang == "zh-tw" }.associateBy { it.id } else emptyMap()
        val pool = Executors.newFixedThreadPool(4)
        val tw = client.twExpansions(pages = 12)
        println("zh: Traditional ${tw.size} sets on the site, missing: " + tw.filter { "zh-tw-${it.code}" !in known }.map { it.code })
        val jobs = tw.filter { "zh-tw-${it.code}" !in known }.map { e ->
            pool.submit(Callable { runCatching { out += old["zh-tw-${e.code}"] ?: client.twSet(e).also { println("zh: ${it.id} ${it.release}: ${it.cards.size} cards") } }.onFailure { println("zh: FAILED ${e.code}: $it") } })
        }
        val manifest = client.cnManifest()
        val cn = client.cnIndex()
        println("zh: Simplified ${cn.size} sets")
        val today = LocalDate.now().toString()
        val jobs2 = cn.map { s -> pool.submit(Callable { runCatching { client.cnSet(s, manifest[s.id], today) }.onFailure { println("zh: FAILED ${s.id}: $it") }.getOrNull() }) }
        val simplified = jobs2.mapNotNull { it.get() }                 // in the list's order, newest first
        jobs.forEach { it.get() }
        pool.shutdown()
        // Specials sit at the end of the list, out of order: their place says nothing about their
        // date, and no date is better than a wrong one
        val inOrder = client.fillDates(simplified.filter { !isSpecial(it) }, today)
        val sorted = (out + inOrder + simplified.filter { isSpecial(it) }.map { it.dated("") }).sortedBy { it.id }
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { it.write(Chinese.toJson(sorted)) }
        println("zh: wrote ${sorted.size} sets, ${sorted.sumOf { it.cards.size }} cards, ${file.length() / 1000} KB; newest: " +
            sorted.groupBy { it.lang }.map { (l, v) -> l + " " + v.maxByOrNull { it.release }?.let { it.id + " " + it.release } })
    }
}
