package com.mivimoose.scanner

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mivimoose.core.net.PriceCharting
import com.mivimoose.core.net.TcgPlayer
import com.mivimoose.scanner.data.CatalogRepo
import com.mivimoose.scanner.data.Money
import com.mivimoose.scanner.data.Net
import com.mivimoose.scanner.data.Notifier
import com.mivimoose.scanner.data.Prefs
import com.mivimoose.scanner.data.PriceRepo
import com.mivimoose.scanner.data.RefRepo
import com.mivimoose.scanner.data.Sounds
import com.mivimoose.scanner.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

/** The app's single instances of everything (a small service locator). */
object Graph {
    lateinit var app: App
    lateinit var net: Net
    lateinit var prefs: Prefs
    lateinit var store: Store
    lateinit var catalog: CatalogRepo
    lateinit var refs: RefRepo
    lateinit var prices: PriceRepo
    lateinit var money: Money
    lateinit var sounds: Sounds
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var openCvReady = false
}

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Graph.app = this
        Graph.openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrElse { Log.e("App", "OpenCV", it); false }
        val net = Net(this)
        Graph.net = net
        Graph.prefs = Prefs(this)
        Graph.store = Store(this)
        val tcg = TcgPlayer(net)
        Graph.catalog = CatalogRepo(this, net, tcg, Graph.scope)
        Graph.refs = RefRepo(this, net, Graph.scope)
        Graph.money = Money(this, net).also { it.currency = Graph.prefs.value.currency }
        Graph.prices = PriceRepo(tcg, PriceCharting(net), Graph.store, Graph.catalog, Graph.prefs, Graph.scope)
        Graph.sounds = Sounds(this, Graph.prefs)
        Notifier.init(this, Graph.money)
        Graph.catalog.start()
        Graph.refs.loadSingles()
        Graph.scope.launch(Dispatchers.IO) { Graph.money.refresh() }
        PriceWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Graph.net.client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(400L * 1024 * 1024).build() }
        .crossfade(true)
        .build()
}
