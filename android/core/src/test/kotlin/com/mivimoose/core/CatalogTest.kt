package com.mivimoose.core

import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.packs.PackModel
import com.mivimoose.core.text.OcrLine
import com.mivimoose.core.text.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CatalogTest {
    private val cat get() = TestData.catalog

    @Test
    fun parsesTheSiteBundle() {
        assertTrue("sets: ${cat.sets.size}", cat.sets.size > 600)
        val s = cat.set("sv3pt5")!!
        assertEquals("151", s.name)
        assertEquals(207, s.cards.size)
        val zard = s.cards.first { it.number == "199" }
        assertEquals("Charizard EX", zard.name)
        assertTrue(zard.price > 50)
        assertTrue(cat.set("base2-1st")!!.firstEdition)
        assertTrue(cat.set("swsh12pt5")!!.cards.any { it.gallery && it.number.startsWith("GG") })
        println("catalog: ${cat.sets.size} sets, ${cat.cardCount} cards, languages ${cat.languages()}")
    }

    @Test
    fun normalisesNumbers() {
        assertEquals("1", Catalog.normNumber("001"))
        assertEquals("TG5", Catalog.normNumber("TG05"))
        assertEquals("SV13", Catalog.normNumber("sv013"))
        assertEquals("199", Catalog.normNumber("199"))
    }

    @Test
    fun packModelMatchesTheSite() {
        val pm = PackModel()
        val s = cat.set("sv3pt5")!!
        val ev = pm.expectedValue(s)
        assertTrue("EV $ev", ev in 1.0..20.0)
        val (pulls, _) = pm.open(s, Random(1))
        assertEquals(10, pulls.size)
        val sir = s.cards.first { it.rarity == "Special Illustration Rare" }
        val odds = pm.cardOdds(s, sir)
        assertTrue("SIR odds 1 in ${1 / odds}", 1 / odds in 150.0..3000.0)
        assertEquals("documented", pm.rateSource(s))
        val table = pm.rarityTable(s)
        println("151 EV per pack $%.2f; rarities: %s".format(ev, table.joinToString { "%s 1/%.0f".format(it.rarity, 1 / it.odds.coerceAtLeast(1e-9)) }))
        for (id in listOf("ja-SV2a", "me55", "swsh12pt5", "base1", "zsv10pt5")) {
            val set = cat.set(id) ?: continue
            println("$id: ${pm.packSize(set)} cards, EV %.2f, source %s".format(pm.expectedValue(set), pm.rateSource(set)))
        }
    }

    private fun line(text: String, y0: Float, y1: Float) = OcrLine(text, floatArrayOf(0.05f, y0, 0.9f, y1))

    @Test
    fun readsCardText() {
        val clues = TextMatch.clues(listOf(
            line("Charizard ex", 0.03f, 0.08f), line("HP 330", 0.03f, 0.07f),
            line("Illus. PLANETA Mochizuki", 0.93f, 0.95f), line("MEW EN 199/165 *", 0.95f, 0.97f),
        ))
        assertEquals("199", clues.numbers.first().number)
        assertEquals(330, clues.hp)
        val c = TextMatch.candidates(clues, cat)
        println("names=${clues.names} codes=${clues.codes} top: " + c.take(4).joinToString { "${it.card.key} %.2f (${it.why})".format(it.score) })
        assertTrue(c.isNotEmpty())
        assertEquals("sv3pt5|199|Charizard EX", c.first().card.key)
        println("top: " + c.take(3).joinToString { "${it.card.key} %.2f (${it.why})".format(it.score) })

        val jp = TextMatch.clues(listOf(line("リザードンex", 0.03f, 0.08f), line("SV2a 201/165 SAR", 0.95f, 0.97f)))
        assertEquals("ja", jp.script)
        val cj = TextMatch.candidates(jp, cat)
        println("jp top: " + cj.take(3).joinToString { "${it.card.key} %.2f (${it.why})".format(it.score) })
        assertEquals("ja-SV2a", cj.first().card.setId)
    }
}
