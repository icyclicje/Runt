package com.mivimoose.core

import com.mivimoose.core.net.JavaHttp
import com.mivimoose.core.net.PriceCharting
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live checks of the graded-data source (skipped with -Doffline). */
class NetTest {
    @Test
    fun pricechartingFindsPopulation() {
        if (System.getProperty("offline") != null) return
        val pc = PriceCharting(JavaHttp("okhttp/5.1.0"))
        for (q in listOf("pokemon Scarlet & Violet 151 Mr Mime 122", "pokemon Scarlet & Violet 151 Charizard ex 199")) {
            val (url, links) = pc.search(q)
            val target = url ?: links.firstOrNull()
            println("$q -> $url / ${links.take(3)}")
            assertTrue(target != null)
            val g = pc.card(target!!)
            println("  psa=${g.psa?.toList()} prices=${g.prices} tcg=${g.tcgId} history=${g.history.mapValues { it.value.size }}")
        }
    }
}
