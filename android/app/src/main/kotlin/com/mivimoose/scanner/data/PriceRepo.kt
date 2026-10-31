package com.mivimoose.scanner.data

import android.util.Log
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.net.PriceCharting
import com.mivimoose.core.net.TcgPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One point of a market graph: day (yyyy-mm-dd), market price (USD), copies sold. */
data class PricePoint(val day: String, val price: Double, val sold: Int)

/** Graded data for a card: PSA/CGC population and graded prices (PriceCharting). */
data class GradedInfo(
    val url: String,
    val psa: List<Int>?,
    val cgc: List<Int>?,
    val prices: Map<String, Double>,
    val history: Map<String, List<Pair<Long, Double>>>,
    val exact: Boolean,
    val fetched: Long,
) {
    val psaTotal get() = psa?.sum() ?: 0
    val gemRate: Double? get() = psa?.let { if (it.sum() > 0) it[9].toDouble() / it.sum() else null }
    val psa10 get() = prices["PSA 10"]
    val psa9 get() = prices["Grade 9"]
}

/**
 * Prices that move: TCGplayer's live market price per card, daily per-set prices for
 * everything in the collection, sales history for market graphs, and graded data.
 * While the app is open the collection's prices refresh every few minutes.
 */
class PriceRepo(
    private val tcg: TcgPlayer,
    private val pc: PriceCharting,
    private val store: Store,
    private val catalogs: CatalogRepo,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val histMem = ConcurrentHashMap<String, Pair<Long, List<PricePoint>>>()
    private val liveAt = ConcurrentHashMap<String, Long>()
    private val gradedMem = ConcurrentHashMap<String, GradedInfo>()
    private val pcLock = Mutex()
    private var lastPc = 0L
    private val _live = MutableStateFlow(LiveState(0L, false, ""))
    val live: StateFlow<LiveState> = _live
    private var loop: Job? = null

    data class LiveState(val updated: Long, val running: Boolean, val note: String)

    // ---------------------------------------------------------------- live prices

    /** TCGplayer's current market price for one card's printings. */
    suspend fun refreshCard(card: Card, maxAgeMs: Long = 5 * 60_000L): Boolean = withContext(Dispatchers.IO) {
        val id = card.tcgId ?: return@withContext false
        if (System.currentTimeMillis() - (liveAt[card.key] ?: 0) < maxAgeMs) return@withContext false
        val pts = runCatching { tcg.pricePoints(id) }.getOrNull() ?: return@withContext false
        liveAt[card.key] = System.currentTimeMillis()
        val normal = pts.firstOrNull { it.printing.equals("Normal", true) }?.market
        val foil = pts.firstOrNull { it.printing.equals("Foil", true) }?.market
        val holoMain = card.variants().first().id == "holo"
        when {
            card.firstEdition -> {}
            normal != null && foil != null && !holoMain -> { card.price = normal; card.reversePrice = foil; card.reverseKnown = true }
            normal != null && foil != null -> { card.price = foil; card.reversePrice = foil; card.reverseKnown = true }
            foil != null -> card.price = foil
            normal != null -> card.price = normal
            else -> return@withContext false
        }
        card.priceKnown = true
        for (v in card.extraVariants) v.productId?.let { pid ->
            runCatching { tcg.pricePoints(pid) }.getOrNull()?.firstOrNull { it.market != null }?.let { v.price = it.market }
        }
        catalogs.tickPrices()
        true
    }

    /** Keep the collection's prices fresh while the app is in the foreground. */
    fun startLive() {
        if (loop?.isActive == true) return
        loop = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { refreshCollection() }.onFailure { Log.w(TAG, "refresh", it) }
                delay(prefs.value.refreshMinutes.coerceAtLeast(5) * 60_000L)
            }
        }
    }

    fun stopLive() { loop?.cancel(); loop = null; _live.value = _live.value.copy(running = false) }

    suspend fun refreshCollection(force: Boolean = false) {
        val c = catalogs.catalog.value ?: return
        _live.value = _live.value.copy(running = true, note = "Updating prices…")
        val owned = store.owned()
        val sets = owned.mapNotNull { c.card(it.cardKey)?.setId }.distinct()
        for (s in sets) catalogs.refreshSet(s, if (force) 0 else 360)
        // TCGplayer's live price for the most valuable cards (rotating through the rest)
        val cards = owned.mapNotNull { c.card(it.cardKey) }.distinctBy { it.key }.sortedByDescending { it.price }
        val top = cards.take(25) + cards.drop(25).sortedBy { liveAt[it.key] ?: 0 }.take(15)
        for (card in top) refreshCard(card, if (force) 0 else 10 * 60_000L)
        checkWatches(c)
        Portfolio.snapshot(store, c)
        _live.value = LiveState(System.currentTimeMillis(), false, "")
    }

    private fun checkWatches(c: Catalog) {
        for (w in store.watches()) {
            val card = c.card(w.cardKey) ?: continue
            val p = card.priceFor(w.variant)
            val hit = if (w.above) p >= w.target else p <= w.target
            if (hit && System.currentTimeMillis() - w.notifiedAt > 24 * 3600_000L) {
                Notifier.priceAlert(card, w, p)
                store.putWatch(w.copy(notifiedAt = System.currentTimeMillis()))
            }
        }
    }

    // ---------------------------------------------------------------- history

    /** Market graph for one printing: range month, quarter, semi-annual, annual. */
    suspend fun history(card: Card, variant: String, range: String): List<PricePoint>? = withContext(Dispatchers.IO) {
        val v = card.variants().firstOrNull { it.id == variant } ?: card.variants().first()
        val pid = v.productId ?: card.tcgId ?: return@withContext null
        val k = "$pid|$range"
        histMem[k]?.let { if (System.currentTimeMillis() - it.first < 30 * 60_000L) return@withContext pick(it.second, v.id) }
        val series = runCatching { tcg.history(pid, range) }.getOrNull() ?: return@withContext null
        val want = when (v.id) {
            "normal" -> listOf("Normal"); "holo" -> listOf("Holofoil", "Normal"); "reverse" -> listOf("Reverse Holofoil")
            "first" -> listOf("1st Edition Holofoil", "1st Edition", "1st Edition Normal"); else -> listOf("Holofoil", "Reverse Holofoil", "Normal")
        }
        val nm = series.filter { it.condition.equals("Near Mint", true) }
        val chosen = want.firstNotNullOfOrNull { w -> nm.firstOrNull { it.variant.equals(w, true) } }
            ?: nm.maxByOrNull { it.totalSold } ?: series.maxByOrNull { it.totalSold } ?: return@withContext emptyList()
        val pts = chosen.buckets.map { PricePoint(it.date, it.market, it.sold) }
        histMem[k] = System.currentTimeMillis() to pts
        pts
    }

    private fun pick(p: List<PricePoint>, @Suppress("UNUSED_PARAMETER") v: String) = p

    // ---------------------------------------------------------------- graded (PSA population)

    fun cachedGraded(card: Card, variant: String): GradedInfo? = gradedMem["${card.key}|$variant"]

    /** PSA/CGC population and graded prices, cached for a week. */
    suspend fun graded(card: Card, variant: String, set: com.mivimoose.core.catalog.CardSet?): GradedInfo? = withContext(Dispatchers.IO) {
        val k = "${card.key}|$variant"
        gradedMem[k]?.let { return@withContext it }
        store.cacheGet("graded|$k", 7 * 24 * 3600_000L)?.let { parse(it) }?.let { gradedMem[k] = it; return@withContext it }
        val setWords = (set?.tcgName ?: card.setName).substringAfter(": ").replace(Regex("^(SV|SWSH|SM|XY|ME|BW)\\d*[a-z]?:?\\s*"), "")
        val q = PriceCharting.query(setWords, card.lang, card.name, card.number, variant)
        val g = pcLock.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastPc)
            if (wait > 0) delay(wait)
            lastPc = System.currentTimeMillis()
            runCatching {
                val (url, links) = pc.search(q)
                val target = url ?: pickLink(links, card, variant) ?: return@runCatching null
                delay(400)
                pc.card(target)
            }.onFailure { Log.w(TAG, "graded $q", it) }.getOrNull()
        } ?: return@withContext null
        val varPid = card.variants().firstOrNull { it.id == variant }?.productId
        val info = GradedInfo(g.url, g.psa?.toList(), g.cgc?.toList(), g.prices, g.history,
            exact = g.tcgId != null && (g.tcgId == card.tcgId || g.tcgId == varPid), fetched = System.currentTimeMillis())
        gradedMem[k] = info
        store.cachePut("graded|$k", write(info))
        info
    }

    private fun pickLink(links: List<String>, card: Card, variant: String): String? {
        val num = card.number.trimStart('0').lowercase()
        val nameWords = com.mivimoose.core.catalog.Catalog.fold(card.name).split(' ').filter { it.length > 1 }
        val varWord = when (variant) { "reverse" -> "reverse-holo"; "first" -> "1st-edition"; "pokeball" -> "poke-ball"; "masterball" -> "master-ball"; else -> null }
        return links.map { l ->
            val slug = l.substringAfterLast('/').lowercase()
            var s = 0
            if (slug.endsWith("-$num")) s += 5
            s += nameWords.count { slug.contains(it) }
            if (varWord != null && slug.contains(varWord)) s += 3
            if (varWord == null && Regex("reverse|1st-edition|poke-ball|master-ball").containsMatchIn(slug)) s -= 3
            if (card.lang == "ja" == l.contains("japanese")) s += 2
            l to s
        }.filter { it.second >= 5 }.maxByOrNull { it.second }?.first
    }

    private fun write(g: GradedInfo) = JSONObject().apply {
        put("url", g.url); put("exact", g.exact); put("fetched", g.fetched)
        g.psa?.let { put("psa", JSONArray(it)) }; g.cgc?.let { put("cgc", JSONArray(it)) }
        put("prices", JSONObject(g.prices as Map<*, *>))
        put("history", JSONObject().apply { g.history.forEach { (k, v) -> put(k, JSONArray(v.map { JSONArray(listOf(it.first, it.second)) })) } })
    }.toString()

    private fun parse(s: String): GradedInfo? = runCatching {
        val o = JSONObject(s)
        fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        val pr = o.getJSONObject("prices").let { p -> p.keys().asSequence().associateWith { p.getDouble(it) } }
        val h = o.optJSONObject("history")?.let { hh ->
            hh.keys().asSequence().associateWith { k -> hh.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getDouble(1) } } } }
        } ?: emptyMap()
        GradedInfo(o.getString("url"), ints("psa"), ints("cgc"), pr, h, o.optBoolean("exact"), o.optLong("fetched"))
    }.getOrNull()

    companion object {
        private const val TAG = "Prices"
    }
}
