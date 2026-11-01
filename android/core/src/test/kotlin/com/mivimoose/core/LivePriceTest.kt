package com.mivimoose.core

import com.mivimoose.core.net.LivePrice
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real numbers from TCGplayer on 2026-11-01. */
class LivePriceTest {
    @Test
    fun followsRecentSales() {
        // Pikachu ex 277/217: market price 272.43, but it sells for ~230 and the cheapest copy is 228.99
        val q = LivePrice.compute(272.43, listOf(238.63, 230.0, 229.99, 228.7, 238.64), 228.99)!!
        assertEquals("sales", q.basis)
        assertEquals(230.0, q.price, 0.01)
    }

    @Test
    fun dropsOddOnesOut() {
        // Phantasmal Flames booster pack: two $2.89-2.99 "packs" among ~$12 sales, and a $4.38 listing
        val q = LivePrice.compute(12.42, listOf(2.99, 2.89, 11.94, 11.98, 16.5), 4.38)!!
        assertEquals(11.98, q.price, 0.01)
        // Prismatic Evolutions booster pack: one $7.98 sale and listing among $13.69-13.72
        val p = LivePrice.compute(14.19, listOf(7.98, 13.69, 13.72, 13.72, 13.69), 7.98)!!
        assertEquals(13.71, p.price, 0.01)
    }

    @Test
    fun shippingOnCheapCardsIsNotAPrice() {
        assertEquals(null, LivePrice.compute(null, emptyList(), 1.3))
        // sales of 35-40 cents, and a $1.63 listing that's nearly all shipping
        assertEquals(0.4, LivePrice.compute(null, listOf(0.35, 0.4, 0.45), 1.63)!!.price, 0.001)
    }

    @Test
    fun fewSalesUseMarketCappedByListings() {
        assertEquals("listing", LivePrice.compute(50.0, listOf(48.0), 44.0)!!.basis)
        assertEquals(44.0, LivePrice.compute(50.0, listOf(48.0), 44.0)!!.price, 0.001)
        assertEquals(50.0, LivePrice.compute(50.0, emptyList(), 55.0)!!.price, 0.001)
        assertEquals(50.0, LivePrice.compute(50.0, emptyList(), 10.0)!!.price, 0.001)   // a $10 listing of a $50 card is suspect
    }
}
