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
import com.mivimoose.core.net.GemRate
import com.mivimoose.core.net.LivePrice
import java.util.concurrent.ConcurrentHashMap

/** One point of a market graph: day (yyyy-mm-dd), market price (USD), copies sold. */
data class PricePoint(val day: String, val price: Double, val sold: Int)

/** Graded data for a card: PSA/CGC population and graded prices (PriceCharting), plus the
 *  population across all graders from GemRate when there's a GemRate key. */
data class GradedInfo(
    val url: String,
    val psa: List<Int>?,
    val cgc: List<Int>?,
    val prices: Map<String, Double>,
    val history: Map<String, List<Pair<Long, Double>>>,
    val exact: Boolean,
    val fetched: Long,
    val universal: GemRate.Pop? = null,
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
    private val http: com.mivimoose.core.net.Http,
    private val store: Store,
    private val catalogs: CatalogRepo,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val histMem = ConcurrentHashMap<String, Pair<Long, List<PricePoint>>>()
    private val gradedMem = ConcurrentHashMap<String, GradedInfo>()
    private val gemTried: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pcLock = Mutex()
    private var lastPc = 0L
    private val _live = MutableStateFlow(LiveState(0L, false, ""))
    val live: StateFlow<LiveState> = _live
    private var loop: Job? = null

    data class LiveState(val updated: Long, val running: Boolean, val note: String)

    // ---------------------------------------------------------------- live prices

    /** A card's live prices: main printing, reverse holo, Poké Ball / Master Ball patterns. */
    class Live(val main: LivePrice.Quote?, val reverse: LivePrice.Quote?, val extras: Map<String, LivePrice.Quote>, val at: Long)

    private val lives = ConcurrentHashMap<String, Live>()
    private val packLives = ConcurrentHashMap<String, Pair<LivePrice.Quote, Long>>()

    init {
        restoreLive()
        catalogs.reapplyLive = { c -> reapply(c) }
    }

    /** The live quote behind a card's price for one printing, if there is one. */
    fun quote(card: Card, variant: String): LivePrice.Quote? {
        val l = lives[card.key]?.takeIf { fresh(it.at) } ?: return null
        return when (variant) {
            "reverse" -> l.reverse
            card.variants().first().id -> l.main
            else -> l.extras[variant]
        }
    }

    fun packQuote(setId: String): LivePrice.Quote? = packLives[setId]?.takeIf { fresh(it.second) }?.first

    /** Live prices count for a day; after that the daily prices are the newer ones. */
    private fun fresh(at: Long) = System.currentTimeMillis() - at < 24 * 3600_000L

    /** One product's live quotes per printing name, from its latest sales and cheapest listings. */
    private fun quotes(
        productId: Long, holoMain: Boolean, wantReverse: Boolean, japanese: Boolean, reverseMarket: Double? = null,
        condition: String = "Near Mint", single: Boolean = false,
    ): Map<String, LivePrice.Quote>? {
        val pts = runCatching { tcg.pricePoints(productId) }.getOrNull()
        val sales = runCatching { tcg.latestSales(productId, japanese = japanese) }.getOrNull()
        if (pts == null && sales == null) return null
        val all = ArrayList(sales ?: emptyList())
        // the newest five sales can all be one printing: ask for reverse holos separately
        if (wantReverse && all.count { it.variant == "Reverse Holofoil" } < 3) runCatching { tcg.latestSales(productId, listOf(77), japanese) }.getOrNull()?.let { all += it }
        val lows = runCatching { tcg.lowestListings(productId, condition, japanese) }.getOrNull() ?: emptyMap()
        val since = java.time.LocalDate.now().minusDays(30).toString()
        // the two requests can return the same sale: one per exact time
        val recent = all.filter { it.day >= since && it.condition.equals(condition, true) }.distinctBy { "${it.time}|${it.variant}|${it.price}" }
        val normal = pts?.firstOrNull { it.printing.equals("Normal", true) }?.market
        val foil = pts?.firstOrNull { it.printing.equals("Foil", true) }?.market
        val out = LinkedHashMap<String, LivePrice.Quote>()
        // TCGplayer's "Foil" market price is the holo of a holo card or a holo-only product
        // (modern rares), else the reverse holo
        for ((name, market) in listOf("Normal" to normal, "Holofoil" to foil.takeIf { holoMain || normal == null },
            "Reverse Holofoil" to (foil.takeIf { !holoMain && normal != null } ?: reverseMarket))) {
            val names = PRINTINGS[name]!!
            val q = LivePrice.compute(market, recent.filter { it.variant in names }.map { it.price }, names.mapNotNull { lows[it] }.minOrNull())
            if (q != null) out[name] = q
        }
        // a single-printing product (a pattern card, a pack): everything it sold is that printing
        if (out.isEmpty() || single) LivePrice.compute(normal ?: foil, recent.map { it.price }, lows.values.minOrNull())?.let { out["any"] = it }
        return out
    }

    /** A card's live price for each of its printings (see LivePrice). */
    suspend fun refreshCard(card: Card, maxAgeMs: Long = 5 * 60_000L): Boolean = withContext(Dispatchers.IO) {
        val id = card.tcgId ?: return@withContext false
        if (card.firstEdition) return@withContext false
        if (System.currentTimeMillis() - (lives[card.key]?.at ?: 0) < maxAgeMs) return@withContext false
        val variants = card.variants()
        val hasReverse = variants.any { it.id == "reverse" }
        val holoMain = variants.first().id == "holo"
        val japanese = card.lang == "ja"
        val q = quotes(id, holoMain, hasReverse, japanese, card.reversePrice.takeIf { card.reverseKnown && it > 0 }) ?: return@withContext false
        val main = (if (holoMain) q["Holofoil"] ?: q["Normal"] else q["Normal"] ?: q["Holofoil"]) ?: q["any"]
        val rev = if (hasReverse) q["Reverse Holofoil"] else null
        val extras = HashMap<String, LivePrice.Quote>()
        for (v in card.extraVariants) v.productId?.let { pid -> quotes(pid, true, false, japanese, single = true)?.let { m -> (m["any"] ?: m.values.firstOrNull())?.let { extras[v.id] = it } } }
        val live = Live(main, rev, extras, System.currentTimeMillis())
        lives[card.key] = live
        apply(card, live)
        // the card data may have been re-read while this was being fetched
        catalogs.catalog.value?.card(card.key)?.takeIf { it !== card }?.let { apply(it, live) }
        catalogs.tickPrices()
        saveLiveSoon()
        true
    }

    /** A set's booster pack at what it sells for now. */
    suspend fun refreshPack(set: com.mivimoose.core.catalog.CardSet, maxAgeMs: Long = 30 * 60_000L): LivePrice.Quote? = withContext(Dispatchers.IO) {
        val pid = set.packProductId ?: return@withContext null
        packLives[set.id]?.let { (q, at) -> if (System.currentTimeMillis() - at < maxAgeMs) return@withContext q }
        val q = quotes(pid, false, false, set.lang == "ja", condition = "Unopened", single = true)?.let { it["any"] ?: it["Normal"] } ?: return@withContext null
        packLives[set.id] = q to System.currentTimeMillis()
        set.sealedPrice = q.price
        catalogs.catalog.value?.set(set.id)?.sealedPrice = q.price
        catalogs.tickPrices()
        saveLiveSoon()
        q
    }

    private fun apply(card: Card, l: Live) {
        l.main?.let { card.price = it.price; card.priceKnown = true }
        l.reverse?.let { card.reversePrice = it.price; card.reverseKnown = true }
        for (v in card.extraVariants) l.extras[v.id]?.let { v.price = it.price }
    }

    /** Live prices younger than a day win over the slower sources. */
    private fun reapply(c: Catalog) {
        for ((k, l) in lives) if (fresh(l.at)) c.card(k)?.let { apply(it, l) }
        for ((id, p) in packLives) if (fresh(p.second)) c.set(id)?.sealedPrice = p.first.price
    }

    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** One write for a burst of updates, which includes all of them. */
    private fun saveLiveSoon() {
        if (!savePending.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(10_000)
            savePending.set(false)
            runCatching { store.cachePut("live-prices", writeLive()) }
        }
    }

    private fun qJson(q: LivePrice.Quote) = JSONObject().put("p", q.price).put("b", q.basis).put("s", JSONArray(q.sales))
        .put("l", q.lowest ?: -1.0).put("m", q.market ?: -1.0)

    private fun qParse(o: JSONObject) = LivePrice.Quote(o.getDouble("p"), o.optString("b"),
        o.optJSONArray("s")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList(),
        o.optDouble("l").takeIf { it >= 0 }, o.optDouble("m").takeIf { it >= 0 })

    private fun writeLive(): String = JSONObject().apply {
        put("cards", JSONObject().apply {
            for ((k, l) in lives) if (fresh(l.at)) put(k, JSONObject().put("at", l.at).apply {
                l.main?.let { put("main", qJson(it)) }; l.reverse?.let { put("rev", qJson(it)) }
                put("x", JSONObject().apply { l.extras.forEach { (v, q) -> put(v, qJson(q)) } })
            })
        })
        put("packs", JSONObject().apply { for ((id, p) in packLives) if (fresh(p.second)) put(id, qJson(p.first).put("at", p.second)) })
    }.toString()

    private fun restoreLive() = scope.launch(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(store.cacheGet("live-prices", 2 * 24 * 3600_000L) ?: return@runCatching)
            o.optJSONObject("cards")?.let { cs ->
                for (k in cs.keys()) {
                    val j = cs.getJSONObject(k)
                    val x = j.optJSONObject("x")
                    lives.putIfAbsent(k, Live(j.optJSONObject("main")?.let(::qParse), j.optJSONObject("rev")?.let(::qParse),
                        x?.keys()?.asSequence()?.associateWith { qParse(x.getJSONObject(it)) } ?: emptyMap(), j.getLong("at")))
                }
            }
            o.optJSONObject("packs")?.let { ps -> for (id in ps.keys()) ps.getJSONObject(id).let { packLives.putIfAbsent(id, qParse(it) to it.getLong("at")) } }
            catalogs.catalog.value?.let { reapply(it); catalogs.tickPrices() }
        }.onFailure { Log.w(TAG, "restore live prices", it) }
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
        val top = cards.take(25) + cards.drop(25).sortedBy { lives[it.key]?.at ?: 0 }.take(15)
        for (card in top) refreshCard(card, if (force) 0 else 10 * 60_000L)
        // packs of the sets being collected
        for (s in sets.take(6)) c.set(s)?.let { refreshPack(it, if (force) 0 else 30 * 60_000L) }
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
        val key = prefs.value.gemRateKey.trim()
        val setWords = (set?.tcgName ?: card.setName).substringAfter(": ").replace(Regex("^(SV|SWSH|SM|XY|ME|BW)\\d*[a-z]?:?\\s*"), "")
        fun allGraders() = if (key.isEmpty()) null else runCatching {
            GemRate(http, key).find(set?.year, setWords, card.name, card.number, card.lang == "ja")
        }.onFailure { Log.w(TAG, "gemrate ${card.key}", it) }.getOrNull()
        val cached = gradedMem[k] ?: store.cacheGet("graded|$k", 7 * 24 * 3600_000L)?.let { parse(it) }?.also { gradedMem[k] = it }
        if (cached != null) {
            // a GemRate key entered since this was fetched: add its population, once per run
            if (key.isEmpty() || cached.universal != null || !gemTried.add(k)) return@withContext cached
            val u = allGraders() ?: return@withContext cached
            return@withContext cached.copy(universal = u).also { gradedMem[k] = it; store.cachePut("graded|$k", write(it)) }
        }
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
        }
        val varPid = card.variants().firstOrNull { it.id == variant }?.productId
        val universal = allGraders()
        if (g == null && universal == null) return@withContext null
        val info = GradedInfo(g?.url ?: universal?.url ?: "", g?.psa?.toList(), g?.cgc?.toList(), g?.prices ?: emptyMap(), g?.history ?: emptyMap(),
            exact = g?.tcgId != null && (g.tcgId == card.tcgId || g.tcgId == varPid), fetched = System.currentTimeMillis(), universal = universal)
        gradedMem[k] = info
        // without PriceCharting's half, try again next time rather than keeping this for a week
        if (g != null) store.cachePut("graded|$k", write(info))
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
        g.universal?.let { u ->
            put("gemrate", JSONObject().apply {
                put("description", u.description); put("url", u.url ?: ""); put("total", u.total); put("gemTotal", u.gemTotal); put("gemRate", u.gemRate ?: -1.0)
                put("graders", JSONObject().apply {
                    u.graders.forEach { (k, v) -> put(k, JSONObject().apply {
                        put("total", v.total); put("gemTotal", v.gemTotal); put("gemRate", v.gemRate ?: -1.0); put("grades", JSONObject(v.grades as Map<*, *>))
                    }) }
                })
            })
        }
    }.toString()

    private fun parse(s: String): GradedInfo? = runCatching {
        val o = JSONObject(s)
        fun ints(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) } }
        val pr = o.getJSONObject("prices").let { p -> p.keys().asSequence().associateWith { p.getDouble(it) } }
        val h = o.optJSONObject("history")?.let { hh ->
            hh.keys().asSequence().associateWith { k -> hh.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getDouble(1) } } } }
        } ?: emptyMap()
        val u = o.optJSONObject("gemrate")?.let { j ->
            val gr = j.getJSONObject("graders")
            GemRate.Pop(j.optString("description"), j.optString("url").ifEmpty { null }, j.optInt("total"), j.optInt("gemTotal"),
                j.optDouble("gemRate").takeIf { it >= 0 }, gr.keys().asSequence().associateWith { k ->
                    val x = gr.getJSONObject(k); val g = x.getJSONObject("grades")
                    GemRate.GraderPop(x.optInt("total"), x.optInt("gemTotal"), x.optDouble("gemRate").takeIf { it >= 0 }, g.keys().asSequence().associateWith { g.getInt(it) })
                })
        }
        GradedInfo(o.getString("url"), ints("psa"), ints("cgc"), pr, h, o.optBoolean("exact"), o.optLong("fetched"), u)
    }.getOrNull()

    companion object {
        private const val TAG = "Prices"

        /** TCGplayer's printing names in sales and listings for each kind of printing. */
        private val PRINTINGS = mapOf(
            "Normal" to setOf("Normal", "Unlimited"),
            "Holofoil" to setOf("Holofoil", "Unlimited Holofoil"),
            "Reverse Holofoil" to setOf("Reverse Holofoil"),
        )
    }
}
