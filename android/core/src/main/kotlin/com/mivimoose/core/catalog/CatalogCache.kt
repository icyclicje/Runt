package com.mivimoose.core.catalog

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The parsed site data in a compact binary form: reading it back is several times faster
 * than parsing the site's 6.5 MB of JavaScript again on every start.
 */
object CatalogCache {
    private const val VERSION = 3

    fun write(sets: Collection<CardSet>, updated: String, out: OutputStream) {
        DataOutputStream(out.buffered(1 shl 16)).use { o ->
            o.writeInt(VERSION)
            o.writeUTF(updated)
            o.writeInt(sets.size)
            for (s in sets) {
                o.writeUTF(s.id); o.writeUTF(s.name); o.optStr(s.nameLocal); o.writeUTF(s.series); o.writeUTF(s.release)
                o.writeInt(s.printed); o.optStr(s.logo); o.optStr(s.symbol); o.writeUTF(s.lang); o.optStr(s.source)
                o.writeInt(s.packSizeData ?: -1); o.writeBoolean(s.estimated); o.writeDouble(s.sealedPrice ?: -1.0)
                o.writeBoolean(s.firstEdition); o.optStr(s.baseId); o.writeBoolean(s.incomplete); o.writeLong(s.packProductId ?: -1L)
                o.writeInt(s.cards.size)
                for (c in s.cards) {
                    o.writeUTF(c.key); o.writeUTF(c.number); o.writeUTF(c.name); o.optStr(c.nameLocal); o.writeUTF(c.rarity)
                    o.writeUTF(c.kind); o.writeUTF(c.subtypes.joinToString("|")); o.writeDouble(c.price); o.writeBoolean(c.priceKnown)
                    o.writeDouble(c.reversePrice); o.writeBoolean(c.reverseKnown); o.writeDouble(c.firstEdPrice ?: -1.0)
                    o.writeLong(c.tcgId ?: -1L); o.writeUTF(c.img); o.writeUTF(c.imgLarge); o.writeBoolean(c.noImage)
                    o.writeBoolean(c.firstEdition); o.writeBoolean(c.mainSet); o.writeInt(c.order); o.writeBoolean(c.gallery)
                }
            }
        }
    }

    /** Null when the cache is from another version or damaged. */
    fun read(inp: InputStream): Catalog? = runCatching {
        DataInputStream(inp.buffered(1 shl 16)).use { i ->
            if (i.readInt() != VERSION) return null
            val updated = i.readUTF()
            val nSets = i.readInt()
            val sets = ArrayList<CardSet>(nSets)
            repeat(nSets) {
                val s = CardSet(
                    id = i.readUTF(), name = i.readUTF(), nameLocal = i.optStr(), series = i.readUTF(), release = i.readUTF(),
                    printed = i.readInt(), logo = i.optStr(), symbol = i.optStr(), lang = i.readUTF(), source = i.optStr(),
                    packSizeData = i.readInt().takeIf { it >= 0 }, estimated = i.readBoolean(),
                )
                s.sealedPrice = i.readDouble().takeIf { it >= 0 }
                s.firstEdition = i.readBoolean(); s.baseId = i.optStr(); s.incomplete = i.readBoolean(); s.packProductId = i.readLong().takeIf { it > 0 }
                repeat(i.readInt()) {
                    val key = i.readUTF(); val number = i.readUTF(); val name = i.readUTF(); val nameLocal = i.optStr(); val rarity = i.readUTF()
                    val kind = i.readUTF(); val subtypes = i.readUTF().split('|').filter { it.isNotEmpty() }; val price = i.readDouble()
                    val priceKnown = i.readBoolean(); val reversePrice = i.readDouble(); val reverseKnown = i.readBoolean()
                    val firstEd = i.readDouble().takeIf { it >= 0 }; val tcgId = i.readLong().takeIf { it >= 0 }
                    val img = i.readUTF(); val imgLarge = i.readUTF(); val noImage = i.readBoolean(); val firstEdition = i.readBoolean()
                    val mainSet = i.readBoolean(); val order = i.readInt(); val gallery = i.readBoolean()
                    s.cards += Card(key, s.id, s.name, s.lang, number, name, nameLocal, rarity, kind, subtypes, price, priceKnown,
                        reversePrice, firstEd, tcgId, img, imgLarge, noImage, firstEdition, mainSet, order, gallery).also { it.reverseKnown = reverseKnown }
                }
                sets += s
            }
            Catalog(sets, updated)
        }
    }.getOrNull()

    private fun DataOutputStream.optStr(s: String?) { writeBoolean(s != null); if (s != null) writeUTF(s) }
    private fun DataInputStream.optStr(): String? = if (readBoolean()) readUTF() else null
}
