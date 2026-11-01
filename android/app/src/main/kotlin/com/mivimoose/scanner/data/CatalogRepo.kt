package com.mivimoose.scanner.data

import android.content.Context
import android.util.Log
import com.mivimoose.core.catalog.BundleParser
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.catalog.CardSet
import com.mivimoose.core.catalog.Supplements
import com.mivimoose.core.net.TcgPlayer
import com.mivimoose.core.packs.PackModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The card database: the MivimooseRips site's data (bundled snapshot, refreshed from the
 * site), live prices from the site's server, and TCGplayer's catalog for prices per
 * printing and for sets/cards the site doesn't have yet.
 */
class CatalogRepo(private val ctx: Context, private val net: Net, private val tcg: TcgPlayer, private val scope: CoroutineScope) {
    private val _catalog = MutableStateFlow<Catalog?>(null)
    val catalog: StateFlow<Catalog?> = _catalog
    private val _status = MutableStateFlow("Loading cards…")
    val status: StateFlow<String> = _status
    /** Ticks whenever prices change in place. */
    private val _prices = MutableStateFlow(0L)
    val priceTick: StateFlow<Long> = _prices
    val packs = PackModel(com.mivimoose.core.packs.PullRates.load(runCatching { ctx.assets.open("data/pull_rates.json").bufferedReader().readText() }.getOrNull()))
    private val dir = File(ctx.filesDir, "data").apply { mkdirs() }
    private val lock = Mutex()
    private var groups: Map<String, JSONObject> = emptyMap()
    @Volatile var pricesUpdated: Long = 0L; private set

    fun start() = scope.launch(Dispatchers.IO) {
        lock.withLock {
            runCatching { publish(parseLocal()) }.onFailure { Log.e(TAG, "local parse", it); _status.value = "Card data failed to load" }
        }
        refresh(force = false)
    }

    private fun readText(name: String): String? {
        val f = File(dir, name)
        if (f.exists() && f.length() > 1000) return f.readText()
        // The build unpacks .gz assets and stores them under the plain name.
        return runCatching { ctx.assets.open("data/$name").bufferedReader().readText() }.getOrNull()
            ?: runCatching { GZIPInputStream(ctx.assets.open("data/$name.gz")).bufferedReader().readText() }.getOrNull()
    }

    private fun parseLocal(): Catalog {
        groups = runCatching {
            val o = JSONObject(ctx.assets.open("data/tcg_groups.json").bufferedReader().readText())
            o.keys().asSequence().associateWith { o.getJSONObject(it) }
        }.getOrDefault(emptyMap())
        val c = cachedOrParse()
        val extra = imported.values.filter { it.id !in c.sets }
        return withCodes(if (extra.isEmpty()) c else Catalog(c.sets.values + extra, c.updated))
    }

    /** The site data, from the binary cache when it matches the current download (fast start). */
    private fun cachedOrParse(): Catalog {
        val t0 = System.currentTimeMillis()
        val src = File(dir, "bundle.js")
        val stamp = "${if (src.exists()) src.lastModified() else 0}-${com.mivimoose.scanner.BuildConfig.VERSION_CODE}"
        val bin = File(dir, "catalog.bin"); val stampFile = File(dir, "catalog.stamp")
        if (bin.exists() && stampFile.exists() && stampFile.readText() == stamp) {
            com.mivimoose.core.catalog.CatalogCache.read(bin.inputStream())?.let {
                Log.i(TAG, "catalog from cache in ${System.currentTimeMillis() - t0} ms"); return it
            }
        }
        val c = BundleParser.parse(readText("bundle.js") ?: error("no card data"), readText("pack-art.js"))
        runCatching { com.mivimoose.core.catalog.CatalogCache.write(c.sets.values, c.updated, bin.outputStream()); stampFile.writeText(stamp) }
        Log.i(TAG, "catalog parsed in ${System.currentTimeMillis() - t0} ms")
        return c
    }

    /** Printed set codes and TCGplayer groups (shipped table; Asian sets use their own set codes). */
    private fun withCodes(c: Catalog): Catalog {
        for (s in c.sets.values) {
            groups[s.id]?.let { g ->
                s.tcgGroupId = g.optLong("g").takeIf { it > 0 }
                g.optString("c").takeIf { it.isNotBlank() }?.let { s.code = it }
                g.optString("n").takeIf { it.isNotBlank() }?.let { s.tcgName = it }
            }
            if (s.code == null && s.lang != "en") {
                val local = s.id.removePrefix(s.lang + "-")
                if (Regex("^[A-Za-z]{1,4}\\d{1,2}[a-zA-Z]?$").matches(local)) s.code = local
            }
        }
        return Catalog(c.sets.values.toList(), c.updated)
    }

    /** Puts the live prices back after slower sources (daily files, the site) wrote theirs. */
    @Volatile var reapplyLive: ((Catalog) -> Unit)? = null

    private fun publish(c: Catalog) {
        packs.invalidate()
        reapplyLive?.invoke(c)
        _catalog.value = c
        scope.launch(Dispatchers.Default) { c.warmSearch() }
        _status.value = "${c.sets.size} sets · ${"%,d".format(c.cardCount)} cards"
    }

    /** Re-download the site's data when it is older than 12 h (or forced), then prices and new sets. */
    fun refresh(force: Boolean) = scope.launch(Dispatchers.IO) {
        val bundle = File(dir, "bundle.js")
        val stale = force || !bundle.exists() || System.currentTimeMillis() - bundle.lastModified() > 12 * 3600_000L
        if (stale) {
            _status.value = "Updating card data…"
            runCatching {
                var changed = net.fetchTo("$SITE/data/bundle.js", bundle)
                changed = net.fetchTo("$SITE/data/pack-art.js", File(dir, "pack-art.js")) || changed
                if (changed || _catalog.value == null) lock.withLock {
                    publish(parseLocal())
                    setPricesAt.clear()                  // new card objects with the site's prices
                }
            }.onFailure { Log.w(TAG, "site data", it) }
        }
        // TCGplayer's own daily prices for the newest sets (what gets opened and scanned most);
        // the site's prices run well above what cards sell for, so TCGplayer's replace them
        _catalog.value?.let { c ->
            for (s in c.sets.values.filter { it.tcgGroupId != null && !it.firstEdition && it.lang in setOf("en", "ja") }.sortedByDescending { it.release }.take(12))
                runCatching { refreshSet(s.id, 720) }
        }
        if (imported.isEmpty()) runCatching { reloadImported() }.onFailure { Log.w(TAG, "imported sets", it) }
        runCatching { importNewSets() }.onFailure { Log.w(TAG, "new sets", it) }
        _catalog.value?.let { _status.value = "${it.sets.size} sets · ${"%,d".format(it.cardCount)} cards" }
    }

    fun tickPrices() { packs.invalidate(); _prices.value = _prices.value + 1; pricesUpdated = System.currentTimeMillis() }

    // ---------------------------------------------------------------- TCGplayer (tcgcsv)

    private val setPricesAt = HashMap<String, Long>()

    /** One set's TCGplayer prices per printing (incl. Poké Ball / Master Ball patterns) and
     *  any cards the site's list is missing. At most every `maxAgeMin` minutes per set. */
    suspend fun refreshSet(setId: String, maxAgeMin: Int = 360): Boolean = lock.withLock {
        val c = _catalog.value ?: return false
        val s = c.sets[setId] ?: return false
        val gid = s.tcgGroupId ?: return false
        if (System.currentTimeMillis() - (setPricesAt[setId] ?: 0) < maxAgeMin * 60_000L) return false
        val cat = if (s.lang == "ja") TcgPlayer.JP else TcgPlayer.EN
        val products = runCatching { tcg.products(cat, gid) }.getOrNull()
        val prices = runCatching { tcg.prices(cat, gid) }.getOrNull() ?: return false
        setPricesAt[setId] = System.currentTimeMillis()
        Supplements.applyPrices(c, products, prices)
        reapplyLive?.invoke(c)
        var added = 0
        if (products != null && s.lang == "en" && s.source != "tcgcsv") {
            val missing = Supplements.missingCards(s, products, prices)
            if (missing.isNotEmpty()) {
                s.cards += missing
                added = missing.size
            }
        }
        if (added > 0) publish(Catalog(c.sets.values.toList(), c.updated)) else tickPrices()
        true
    }

    /** English and Japanese sets on TCGplayer that the site doesn't have yet (new releases). */
    private suspend fun importNewSets() {
        val c = _catalog.value ?: return
        val last = File(dir, "groups.checked")
        if (last.exists() && System.currentTimeMillis() - last.lastModified() < 24 * 3600_000L) return
        val knownGroups = c.sets.values.mapNotNull { it.tcgGroupId }.toHashSet()
        val cutoff = java.time.LocalDate.now().minusDays(150).toString()
        val fresh = ArrayList<CardSet>()
        for (cat in listOf(TcgPlayer.EN, TcgPlayer.JP)) {
            val gs = runCatching { tcg.groups(cat) }.getOrNull() ?: continue
            for (g in gs) {
                if (g.id in knownGroups || g.supplemental || g.published < cutoff) continue
                if (Regex("Promo|Deck|Box|Collection|Kit|Bundle|Tin|Blister", RegexOption.IGNORE_CASE).containsMatchIn(g.name)) continue
                val products = runCatching { tcg.products(cat, g.id) }.getOrNull() ?: continue
                // Already in the site's data under another id? (cards share TCGplayer ids)
                val ids = products.filter { it.isCard }.map { it.id }
                if (ids.isEmpty() || ids.count { c.byTcgId.containsKey(it) } > ids.size / 10) continue
                val prices = runCatching { tcg.prices(cat, g.id) }.getOrDefault(emptyList())
                Supplements.importGroup(g, products, prices)?.let { fresh += it; imported[it.id] = it; saveImported(g, cat) }
            }
        }
        last.writeText(System.currentTimeMillis().toString())
        if (fresh.isNotEmpty()) lock.withLock {
            val cur = _catalog.value ?: return
            publish(withCodes(Catalog(cur.sets.values + fresh.filter { it.id !in cur.sets }, cur.updated)))
        }
    }

    private fun saveImported(g: TcgPlayer.Group, cat: Int) {
        val f = File(dir, "imported.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        o.put(g.id.toString(), JSONObject().put("cat", cat).put("name", g.name).put("abbr", g.abbreviation).put("pub", g.published))
        f.writeText(o.toString())
    }

    private val imported = java.util.concurrent.ConcurrentHashMap<String, CardSet>()

    /** Sets imported on an earlier run are re-read from TCGplayer after start-up. */
    private suspend fun reloadImported() {
        val f = File(dir, "imported.json")
        if (!f.exists()) return
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        for (k in o.keys()) {
            val j = o.getJSONObject(k)
            val g = TcgPlayer.Group(k.toLong(), j.getString("name"), j.optString("abbr"), false, j.optString("pub"), j.getInt("cat"))
            runCatching { Supplements.importGroup(g, tcg.products(g.category, g.id), tcg.prices(g.category, g.id)) }.getOrNull()?.let { imported[it.id] = it }
        }
        if (imported.isEmpty()) return
        lock.withLock {
            val cur = _catalog.value ?: return
            val add = imported.values.filter { it.id !in cur.sets }
            if (add.isNotEmpty()) publish(withCodes(Catalog(cur.sets.values + add, cur.updated)))
        }
    }

    companion object {
        const val SITE = "https://mivimooserips-production-e1c1.up.railway.app"
        private const val TAG = "Catalog"
    }
}
