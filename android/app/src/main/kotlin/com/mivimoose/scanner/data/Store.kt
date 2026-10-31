package com.mivimoose.scanner.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** A card you own: one row per card + printing + condition + grade, with a count. */
data class Owned(
    val id: Long, val cardKey: String, val variant: String, val condition: String, val grade: String?,
    val qty: Int, val cost: Double?, val addedAt: Long,
)

data class ScanRow(
    val id: Long, val cardKey: String, val variant: String, val price: Double, val at: Long, val mode: String,
    val packId: Long?, val duplicate: Boolean, val added: Boolean,
)

data class PackRow(val id: Long, val setId: String, val openedAt: Long, val cost: Double?, val done: Boolean)

data class Snapshot(val day: String, val value: Double, val cost: Double, val cards: Int)

data class Watch(val cardKey: String, val variant: String, val target: Double, val above: Boolean, val created: Long, val notifiedAt: Long)

/**
 * The app's own data (SQLite): collection, scan history, packs, daily portfolio values,
 * watchlist, and a small key/value cache for downloaded extras. `version` ticks on every
 * write so screens can recompute.
 */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "mivimoose.db", null, 1) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    private fun changed() { _version.value = _version.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE owned(id INTEGER PRIMARY KEY AUTOINCREMENT, card_key TEXT NOT NULL, variant TEXT NOT NULL,
            condition TEXT NOT NULL DEFAULT 'NM', grade TEXT, qty INTEGER NOT NULL DEFAULT 1, cost REAL, added_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX owned_key ON owned(card_key)")
        db.execSQL("""CREATE TABLE scans(id INTEGER PRIMARY KEY AUTOINCREMENT, card_key TEXT NOT NULL, variant TEXT NOT NULL,
            price REAL NOT NULL, at INTEGER NOT NULL, mode TEXT NOT NULL, pack_id INTEGER, duplicate INTEGER NOT NULL, added INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE packs(id INTEGER PRIMARY KEY AUTOINCREMENT, set_id TEXT NOT NULL, opened_at INTEGER NOT NULL,
            cost REAL, done INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE TABLE snapshots(day TEXT PRIMARY KEY, value REAL NOT NULL, cost REAL NOT NULL, cards INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE watch(card_key TEXT PRIMARY KEY, variant TEXT NOT NULL, target REAL NOT NULL, above INTEGER NOT NULL,
            created INTEGER NOT NULL, notified_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY, v TEXT NOT NULL, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ------------------------------------------------------------------ collection

    fun owned(): List<Owned> = readableDatabase.rawQuery("SELECT * FROM owned ORDER BY added_at DESC", null).use { c ->
        c.map { Owned(long("id"), str("card_key"), str("variant"), str("condition"), strOrNull("grade"), int("qty"), dblOrNull("cost"), long("added_at")) }
    }

    fun ownedCount(cardKey: String): Int = readableDatabase.rawQuery("SELECT COALESCE(SUM(qty),0) FROM owned WHERE card_key=?", arrayOf(cardKey)).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    /** Add copies; merges into an existing row with the same card, printing, condition and grade. */
    fun add(cardKey: String, variant: String, condition: String = "NM", grade: String? = null, qty: Int = 1, cost: Double? = null): Long {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT id FROM owned WHERE card_key=? AND variant=? AND condition=? AND COALESCE(grade,'')=?",
            arrayOf(cardKey, variant, condition, grade ?: ""),
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        val id = if (existing != null) {
            db.execSQL("UPDATE owned SET qty=qty+?, cost=COALESCE(?,cost) WHERE id=?", arrayOf<Any?>(qty, cost, existing))
            existing
        } else db.insert("owned", null, ContentValues().apply {
            put("card_key", cardKey); put("variant", variant); put("condition", condition); put("grade", grade)
            put("qty", qty); put("cost", cost); put("added_at", System.currentTimeMillis())
        })
        changed()
        return id
    }

    /** Remove one copy (deletes the row at zero). */
    fun removeOne(cardKey: String, variant: String? = null) {
        val db = writableDatabase
        val row = db.rawQuery(
            if (variant != null) "SELECT id, qty FROM owned WHERE card_key=? AND variant=? ORDER BY added_at DESC LIMIT 1"
            else "SELECT id, qty FROM owned WHERE card_key=? ORDER BY added_at DESC LIMIT 1",
            if (variant != null) arrayOf(cardKey, variant) else arrayOf(cardKey),
        ).use { if (it.moveToFirst()) it.getLong(0) to it.getInt(1) else null } ?: return
        if (row.second > 1) db.execSQL("UPDATE owned SET qty=qty-1 WHERE id=?", arrayOf(row.first))
        else db.delete("owned", "id=?", arrayOf(row.first.toString()))
        changed()
    }

    fun updateOwned(o: Owned) {
        writableDatabase.update("owned", ContentValues().apply {
            put("variant", o.variant); put("condition", o.condition); put("grade", o.grade); put("qty", o.qty); put("cost", o.cost)
        }, "id=?", arrayOf(o.id.toString()))
        changed()
    }

    fun deleteOwned(id: Long) { writableDatabase.delete("owned", "id=?", arrayOf(id.toString())); changed() }

    // ------------------------------------------------------------------ scans

    fun addScan(cardKey: String, variant: String, price: Double, mode: String, packId: Long?, duplicate: Boolean, added: Boolean): Long {
        val id = writableDatabase.insert("scans", null, ContentValues().apply {
            put("card_key", cardKey); put("variant", variant); put("price", price); put("at", System.currentTimeMillis())
            put("mode", mode); put("pack_id", packId); put("duplicate", if (duplicate) 1 else 0); put("added", if (added) 1 else 0)
        })
        changed()
        return id
    }

    fun scans(limit: Int = 200): List<ScanRow> = readableDatabase.rawQuery("SELECT * FROM scans ORDER BY at DESC LIMIT $limit", null).use { c ->
        c.map { ScanRow(long("id"), str("card_key"), str("variant"), dbl("price"), long("at"), str("mode"), longOrNull("pack_id"), int("duplicate") == 1, int("added") == 1) }
    }

    fun scansOfPack(packId: Long): List<ScanRow> = readableDatabase.rawQuery("SELECT * FROM scans WHERE pack_id=? ORDER BY at", arrayOf(packId.toString())).use { c ->
        c.map { ScanRow(long("id"), str("card_key"), str("variant"), dbl("price"), long("at"), str("mode"), longOrNull("pack_id"), int("duplicate") == 1, int("added") == 1) }
    }

    fun setScanVariant(id: Long, variant: String, price: Double) {
        writableDatabase.execSQL("UPDATE scans SET variant=?, price=? WHERE id=?", arrayOf<Any>(variant, price, id)); changed()
    }

    fun setScanAdded(id: Long, added: Boolean) {
        writableDatabase.execSQL("UPDATE scans SET added=? WHERE id=?", arrayOf<Any>(if (added) 1 else 0, id)); changed()
    }

    fun deleteScan(id: Long) { writableDatabase.delete("scans", "id=?", arrayOf(id.toString())); changed() }
    fun clearScans() { writableDatabase.delete("scans", null, null); changed() }

    // ------------------------------------------------------------------ packs

    fun openPack(setId: String, cost: Double?): Long {
        val id = writableDatabase.insert("packs", null, ContentValues().apply {
            put("set_id", setId); put("opened_at", System.currentTimeMillis()); put("cost", cost); put("done", 0)
        })
        changed()
        return id
    }

    fun finishPack(id: Long) { writableDatabase.execSQL("UPDATE packs SET done=1 WHERE id=?", arrayOf(id)); changed() }

    fun packs(): List<PackRow> = readableDatabase.rawQuery("SELECT * FROM packs ORDER BY opened_at DESC", null).use { c ->
        c.map { PackRow(long("id"), str("set_id"), long("opened_at"), dblOrNull("cost"), int("done") == 1) }
    }

    fun deletePack(id: Long) {
        writableDatabase.delete("packs", "id=?", arrayOf(id.toString()))
        writableDatabase.execSQL("UPDATE scans SET pack_id=NULL WHERE pack_id=?", arrayOf(id))
        changed()
    }

    // ------------------------------------------------------------------ portfolio history

    fun putSnapshot(value: Double, cost: Double, cards: Int, day: String = LocalDate.now().toString()) {
        writableDatabase.insertWithOnConflict("snapshots", null, ContentValues().apply {
            put("day", day); put("value", value); put("cost", cost); put("cards", cards)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun snapshots(): List<Snapshot> = readableDatabase.rawQuery("SELECT * FROM snapshots ORDER BY day", null).use { c ->
        c.map { Snapshot(str("day"), dbl("value"), dbl("cost"), int("cards")) }
    }

    // ------------------------------------------------------------------ watchlist

    fun watches(): List<Watch> = readableDatabase.rawQuery("SELECT * FROM watch ORDER BY created DESC", null).use { c ->
        c.map { Watch(str("card_key"), str("variant"), dbl("target"), int("above") == 1, long("created"), long("notified_at")) }
    }

    fun putWatch(w: Watch) {
        writableDatabase.insertWithOnConflict("watch", null, ContentValues().apply {
            put("card_key", w.cardKey); put("variant", w.variant); put("target", w.target); put("above", if (w.above) 1 else 0)
            put("created", w.created); put("notified_at", w.notifiedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun deleteWatch(cardKey: String) { writableDatabase.delete("watch", "card_key=?", arrayOf(cardKey)); changed() }

    // ------------------------------------------------------------------ cache

    fun cacheGet(k: String, maxAgeMs: Long): String? = readableDatabase.rawQuery("SELECT v, at FROM cache WHERE k=?", arrayOf(k)).use {
        if (it.moveToFirst() && System.currentTimeMillis() - it.getLong(1) < maxAgeMs) it.getString(0) else null
    }

    fun cachePut(k: String, v: String) {
        writableDatabase.insertWithOnConflict("cache", null, ContentValues().apply {
            put("k", k); put("v", v); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun wipeAll() {
        val db = writableDatabase
        for (t in listOf("owned", "scans", "packs", "snapshots", "watch")) db.delete(t, null, null)
        changed()
    }
}

private inline fun <T> Cursor.map(f: Cursor.() -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += f()
    return out
}

private fun Cursor.str(c: String) = getString(getColumnIndexOrThrow(c))
private fun Cursor.strOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
private fun Cursor.int(c: String) = getInt(getColumnIndexOrThrow(c))
private fun Cursor.long(c: String) = getLong(getColumnIndexOrThrow(c))
private fun Cursor.longOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.dbl(c: String) = getDouble(getColumnIndexOrThrow(c))
private fun Cursor.dblOrNull(c: String) = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getDouble(it) }
