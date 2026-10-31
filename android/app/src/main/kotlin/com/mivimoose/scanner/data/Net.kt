package com.mivimoose.scanner.data

import android.content.Context
import com.mivimoose.core.net.Http
import com.mivimoose.core.net.HttpException
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One OkHttp client for the app (disk cache for images and data), adapted to core's Http. */
class Net(ctx: Context) : Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(ctx.cacheDir, "http"), 80L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    override fun bytes(url: String, headers: Map<String, String>): ByteArray =
        client.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, url)
            r.body.bytes()
        }

    override fun getFollow(url: String, headers: Map<String, String>): Pair<String, String> =
        client.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, url)
            r.request.url.toString() to r.body.string()
        }

    /** Download to a file with ETag revalidation. Returns true when the file changed. */
    fun fetchTo(url: String, file: File): Boolean {
        val etagFile = File(file.path + ".etag")
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (file.exists() && etagFile.exists()) etagFile.readText().trim().takeIf { it.isNotEmpty() }?.let { b.header("If-None-Match", it) }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 304) { file.setLastModified(System.currentTimeMillis()); return false }
            if (!r.isSuccessful) throw HttpException(r.code, url)
            val tmp = File(file.path + ".tmp")
            r.body.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            etagFile.writeText(r.header("ETag") ?: "")
            return true
        }
    }

    companion object {
        const val UA = "MivimooseRips/3.0 (Android)"
    }
}
