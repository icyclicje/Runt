package com.mivimoose.scanner.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors

/** Camera preview plus frame analysis feeding the scan engine (portrait greyscale frames). */
@Composable
fun CameraFeed(engine: ScanEngine, torch: Boolean, modifier: Modifier = Modifier, onCamera: (Camera?) -> Unit = {}) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            runCatching {
                val p = future.get(); provider = p
                val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                val preview = Preview.Builder().setResolutionSelector(ratio.build()).build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(ratio.setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor) { img ->
                    try {
                        val m = yPlane(img)
                        engine.onFrame(m, img.imageInfo.timestamp / 1e9)
                        m.release()
                    } catch (e: Exception) {
                        Log.w("Camera", "frame", e)
                    } finally {
                        img.close()
                    }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                onCamera(camera)
            }.onFailure { Log.e("Camera", "bind", it) }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { provider?.unbindAll(); executor.shutdown(); onCamera(null) }
    }
    LaunchedEffect(torch, camera) { camera?.cameraControl?.enableTorch(torch) }
    AndroidView({ view }, modifier.pointerInput(Unit) {
        detectTapGestures { pt ->
            val c = camera ?: return@detectTapGestures
            val mp = view.meteringPointFactory.createPoint(pt.x, pt.y)
            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(mp).build())
        }
    })
}

/** The luma plane as an upright (portrait) greyscale Mat. */
fun yPlane(img: ImageProxy): Mat {
    val plane = img.planes[0]
    val w = img.width; val h = img.height
    val stride = plane.rowStride
    val buf = plane.buffer
    buf.rewind()
    val m = Mat(h, w, CvType.CV_8UC1)
    if (stride == w) {
        val b = ByteArray(w * h); buf.get(b, 0, minOf(b.size, buf.remaining())); m.put(0, 0, b)
    } else {
        val row = ByteArray(stride)
        val all = ByteArray(w * h)
        for (y in 0 until h) {
            val n = minOf(stride, buf.remaining())
            buf.get(row, 0, n)
            System.arraycopy(row, 0, all, y * w, w)
        }
        m.put(0, 0, all)
    }
    val rot = img.imageInfo.rotationDegrees
    if (rot == 0) return m
    val r = Mat()
    Core.rotate(m, r, when (rot) { 90 -> Core.ROTATE_90_CLOCKWISE; 180 -> Core.ROTATE_180; else -> Core.ROTATE_90_COUNTERCLOCKWISE })
    m.release()
    return r
}

/**
 * Test mode: plays a folder of JPEG frames (e.g. the synthetic sequences from
 * tests/export_android_bench.py pushed to the device) through the engine at ~12 fps.
 */
@Composable
fun FileFeed(engine: ScanEngine, dir: String, modifier: Modifier = Modifier) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    if (File(dir).listFiles()?.any { File(it, "truth.json").exists() } == true) {
        LaunchedEffect(dir) { bench(engine, File(dir)) { bmp = it } }
        bmp?.let { Image(it.asImageBitmap(), null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        return
    }
    LaunchedEffect(dir) {
        val files = File(dir).walkTopDown().filter { it.isFile && it.name.endsWith(".jpg") }.sortedBy { it.path }.toList()
        var t = 0.0
        while (isActive) {
            for (f in files) {
                val b = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } ?: continue
                bmp = b
                withContext(Dispatchers.Default) {
                    val rgba = Mat(); val gray = Mat()
                    Utils.bitmapToMat(b, rgba)
                    Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                    engine.onFrame(gray, t)
                    rgba.release(); gray.release()
                }
                t += 1 / 12.0
                delay(40)
            }
            if (files.isEmpty()) delay(1000)
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
}

/**
 * Benchmark mode: every sub-folder with a truth.json is one sequence (a card brought up,
 * held and taken away). Each is played once in real time from a cold start, in price-check
 * mode, and what the scanner confirmed is logged ("BENCH" in logcat).
 */
private suspend fun bench(engine: ScanEngine, root: File, show: (Bitmap) -> Unit) {
    val prefs = com.mivimoose.scanner.Graph.prefs
    val oldMode = prefs.value.scanMode
    prefs.update { it.copy(scanMode = "check") }
    com.mivimoose.scanner.Graph.store.clearScans()
    engine.updatePool()
    val seqs = root.listFiles()!!.filter { File(it, "truth.json").exists() }.sortedBy { it.name }
    var ok = 0; var wrong = 0; var n = 0
    val delays = ArrayList<Int>()
    for (d in seqs) {
        val truth = org.json.JSONObject(File(d, "truth.json").readText())
        val key = truth.getString("key")
        val held = truth.getJSONArray("held")
        val firstHeld = (0 until held.length()).first { held.getBoolean(it) }
        engine.resetForBench()
        val got = ArrayList<Pair<String, Int>>()
        var frame = 0
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { engine.results.collect { got += it.card.key to (frame - firstHeld) } }
        val files = d.listFiles { f -> f.name.endsWith(".jpg") }!!.sortedBy { it.name }
        var t = 0.0
        for ((i, f) in files.withIndex()) {
            frame = i
            val b = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } ?: continue
            show(b)
            val t0 = System.nanoTime()
            withContext(Dispatchers.Default) {
                val rgba = Mat(); val gray = Mat()
                Utils.bitmapToMat(b, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                engine.onFrame(gray, t); rgba.release(); gray.release()
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            t += maxOf(ms, 66L) / 1000.0
            delay(maxOf(0L, 66L - ms))
        }
        delay(300)
        job.cancel()
        n++
        val mine = got.filter { it.first == key }
        val others = got.filter { it.first != key }
        if (mine.isNotEmpty()) { ok++; delays += mine.first().second }
        wrong += others.size
        Log.i("BENCH", "${d.name} ${truth.getString("name")} #${truth.getString("number")}: " +
            (if (mine.isNotEmpty()) "ok +${mine.first().second}f" else "MISSED") + (if (others.isNotEmpty()) " FALSE ${others.map { it.first }}" else ""))
    }
    delays.sort()
    Log.i("BENCH", "SUMMARY ${root.name}: scanned $ok/$n, false $wrong, median delay ${delays.getOrNull(delays.size / 2)} frames")
    prefs.update { it.copy(scanMode = oldMode) }
}
