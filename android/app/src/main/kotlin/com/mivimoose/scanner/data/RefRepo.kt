package com.mivimoose.scanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.mivimoose.core.catalog.BundleParser
import com.mivimoose.core.catalog.Card
import com.mivimoose.core.catalog.Catalog
import com.mivimoose.core.vision.CardIndex
import com.mivimoose.core.vision.Fingerprint
import com.mivimoose.core.vision.RefCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Reference cards for recognition: each card's scan is downloaded once, turned into a
 * fingerprint and artwork keypoints, and cached on disk per set. The recogniser searches a
 * "pool": the sets you are opening or scanned recently, plus single cards the text reader
 * suggested.
 */
class RefRepo(ctx: Context, private val net: Net, private val scope: CoroutineScope) {
    private val dir = File(ctx.filesDir, "refs").apply { mkdirs() }
    private val mem = ConcurrentHashMap<String, RefCard>()
    private val setLoaded = ConcurrentHashMap<String, Boolean>()
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val attempts = ConcurrentHashMap<String, Int>()
    private val fileLock = Mutex()
    val index = CardIndex()
    private val poolSets = LinkedHashSet<String>()
    private val poolExtra = LinkedHashSet<String>()

    data class Progress(val setId: String, val done: Int, val total: Int)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private val _poolVersion = MutableStateFlow(0)
    val poolVersion: StateFlow<Int> = _poolVersion
    val poolSetIds: List<String> get() = synchronized(poolSets) { poolSets.toList() }

    private val downloads = Semaphore(6)

    /** Make these sets (and extra single cards) the recognition pool. */
    fun setPool(catalog: Catalog, setIds: List<String>, extraKeys: Collection<String> = emptyList()) {
        synchronized(poolSets) {
            if (poolSets.toList() == setIds && poolExtra.containsAll(extraKeys) && setIds.all { setLoaded[it] == true }) return
            poolSets.clear(); poolSets += setIds
            poolExtra += extraKeys
            while (poolExtra.size > 120) poolExtra.remove(poolExtra.first())
        }
        scope.launch(Dispatchers.IO) {
            rebuild(catalog)                                 // what's already in memory, right away
            for (s in setIds) if (setLoaded[s] != true) { ensureSet(catalog, s) { rebuild(catalog) }; rebuild(catalog) }
        }
    }

    /** Add single cards (e.g. what the text on a card suggested) to the pool. */
    fun addCards(catalog: Catalog, keys: Collection<String>) = scope.launch(Dispatchers.IO) {
        val todo = keys.filter { it !in index.keys }
        if (todo.isEmpty()) return@launch
        synchronized(poolSets) { poolExtra += todo }
        todo.mapNotNull { catalog.card(it) }.map { c -> async { load(c) } }.awaitAll()
        rebuild(catalog)
    }

    private fun rebuild(catalog: Catalog) {
        val keys = synchronized(poolSets) {
            poolSets.flatMap { s -> catalog.set(s)?.cards?.map { it.key } ?: emptyList() } + poolExtra
        }.distinct()
        val refs = keys.mapNotNull { mem[it] }
        index.load(refs)
        // Scanning a mixed binder loads set after set; they're on disk if they're needed again.
        if (mem.size > MAX_IN_MEMORY) {
            mem.keys.retainAll(keys.toHashSet())
            setLoaded.keys.retainAll(poolSetIds.toSet())
        }
        _poolVersion.value = _poolVersion.value + 1
        Log.i(TAG, "pool: ${refs.size} cards from ${poolSetIds}")
    }

    /** Download (or read from disk) every card of a set; `partial` runs after each batch, so
     *  the cards already downloaded can be recognised while the rest still load. */
    suspend fun ensureSet(catalog: Catalog, setId: String, partial: (() -> Unit)? = null) {
        if (setLoaded[setId] == true) return
        val set = catalog.set(setId) ?: return
        if (!loading.add(setId)) return                  // already on its way
        try { loadSet(set, setId, partial) } finally { loading.remove(setId) }
    }

    private suspend fun loadSet(set: com.mivimoose.core.catalog.CardSet, setId: String, partial: (() -> Unit)?) {
        val file = File(dir, "${setId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.bin")
        fileLock.withLock { if (file.exists()) readFile(file) }
        // rare cards first: they're the ones worth scanning quickly
        val todo = set.cards.filter { !it.noImage && !mem.containsKey(it.key) }.sortedByDescending { it.price }
        if (todo.isNotEmpty()) {
            var done = set.cards.size - todo.size
            _progress.value = Progress(setId, done, set.cards.size)
            coroutineScopeIO {
                for (batch in todo.chunked(40)) {
                    batch.map { c -> async { load(c, save = false); done++; _progress.value = Progress(setId, done, set.cards.size) } }.awaitAll()
                    partial?.invoke()
                }
            }
            fileLock.withLock { writeFile(file, set.cards.mapNotNull { mem[it.key] }) }
            _progress.value = null
        }
        // downloads can fail on a bad connection: try again next time, but not for ever
        val complete = set.cards.none { !it.noImage && !mem.containsKey(it.key) }
        if (complete || attempts.merge(setId, 1, Int::plus)!! >= 3) setLoaded[setId] = true
    }

    private suspend fun <T> coroutineScopeIO(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun load(c: Card, save: Boolean = true): RefCard? {
        mem[c.key]?.let { return it }
        val urls = listOfNotNull(c.img.takeIf { it.isNotEmpty() }, c.tcgId?.let { BundleParser.tcgImage(it) })
        for (u in urls) {
            val bytes = downloads.withPermit { runCatching { net.bytes(u) }.getOrNull() } ?: continue
            val ref = runCatching { build(c.key, bytes) }.getOrNull() ?: continue
            mem[c.key] = ref
            if (save) fileLock.withLock { appendFile(File(dir, "single.bin"), ref) }
            return ref
        }
        return null
    }

    /** A downloaded scan -> reference card (alpha corners flattened onto grey, like the desktop app). */
    fun build(key: String, bytes: ByteArray): RefCard? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.rgb(200, 200, 200)); drawBitmap(bmp, 0f, 0f, null) }
        bmp.recycle()
        val rgba = Mat(); val gray = Mat()
        try {
            Utils.bitmapToMat(flat, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            return RefCard.build(key, gray)
        } finally {
            rgba.release(); gray.release(); flat.recycle()
        }
    }

    fun loadSingles() = scope.launch(Dispatchers.IO) { fileLock.withLock { File(dir, "single.bin").takeIf { it.exists() }?.let { readFile(it) } } }

    fun has(key: String) = mem.containsKey(key)

    // ---------------------------------------------------------------- disk format

    private fun writeRef(o: DataOutputStream, r: RefCard) {
        o.writeUTF(r.key)
        o.writeInt(r.desc.size); r.desc.forEach { o.writeFloat(it) }
        o.writeInt(r.canon.size); o.write(r.canon)
        val p = r.kpPts ?: FloatArray(0); val d = r.kpDes ?: ByteArray(0)
        o.writeInt(p.size); p.forEach { o.writeFloat(it) }
        o.writeInt(d.size); o.write(d)
    }

    private fun writeFile(f: File, refs: List<RefCard>) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(VERSION)
            refs.forEach { writeRef(o, it) }
        }
        tmp.renameTo(f)
    }

    private fun appendFile(f: File, r: RefCard) {
        val fresh = !f.exists()
        DataOutputStream(java.io.FileOutputStream(f, true).buffered()).use { o ->
            if (fresh) o.writeInt(VERSION)
            writeRef(o, r)
        }
    }

    private fun readFile(f: File) {
        runCatching {
            DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
                if (i.readInt() != VERSION) { f.delete(); return }
                while (i.available() > 0) {
                    val key = i.readUTF()
                    val desc = FloatArray(i.readInt()) { i.readFloat() }
                    val canon = ByteArray(i.readInt()).also { i.readFully(it) }
                    val pts = FloatArray(i.readInt()) { i.readFloat() }
                    val des = ByteArray(i.readInt()).also { i.readFully(it) }
                    if (desc.size == Fingerprint.DIM) mem[key] = RefCard(key, desc, canon, pts.takeIf { it.isNotEmpty() }, des.takeIf { it.isNotEmpty() })
                }
            }
        }.onFailure { Log.w(TAG, "bad ref file $f", it) }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        mem.clear(); setLoaded.clear(); attempts.clear()
        synchronized(poolSets) { poolSets.clear(); poolExtra.clear() }
        index.load(emptyList())
        _poolVersion.value = _poolVersion.value + 1
    }

    fun diskBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0

    companion object {
        private const val TAG = "Refs"
        /** Reference images kept in memory (about 70 KB each). */
        private const val MAX_IN_MEMORY = 3000
        private const val VERSION = 3
    }
}
