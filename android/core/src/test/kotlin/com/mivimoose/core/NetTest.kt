package com.mivimoose.core

import com.mivimoose.core.net.JavaHttp
import com.mivimoose.core.net.LivePrice
import com.mivimoose.core.net.PriceCharting
import com.mivimoose.core.net.TcgPlayer
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live checks of the graded-data and live-price sources (skipped with -Doffline). */
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

    @Test
    fun tcgplayerLivePrices() {
        if (System.getProperty("offline") != null) return
        val tcg = TcgPlayer(JavaHttp("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"))
        // Pikachu ex 277/217 (Ascended Heroes), a 151 booster pack
        for ((id, cond) in listOf(676089L to "Near Mint", 504467L to "Unopened")) {
            val market = tcg.pricePoints(id).firstNotNullOfOrNull { it.market }
            val sales = tcg.latestSales(id).filter { it.condition == cond }
            val lows = tcg.lowestListings(id, cond)
            val q = LivePrice.compute(market, sales.map { it.price }, lows.values.minOrNull())
            println("$id market=$market sales=${sales.map { it.day + " " + it.price }} lowest=$lows -> ${q?.price} (${q?.basis})")
            assertTrue(sales.isNotEmpty() && lows.isNotEmpty() && q != null)
        }
    }
}
