package com.mivimoose.core.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class HttpException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Minimal HTTP access, so the data code runs the same on the phone (OkHttp) and in desktop tests. */
interface Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = String(bytes(url, headers), Charsets.UTF_8)
    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray
    /** Follows redirects and returns the final URL with the body. */
    fun getFollow(url: String, headers: Map<String, String> = emptyMap()): Pair<String, String>
    /** POST a JSON body, returning the response body. */
    fun post(url: String, json: String, headers: Map<String, String> = emptyMap()): String
}

/** java.net implementation (desktop tests and fallback). */
open class JavaHttp(private val userAgent: String = "MivimooseRips/3.0 (Android)") : Http {
    override fun bytes(url: String, headers: Map<String, String>): ByteArray = open(url, headers).let { c ->
        try { read(c) } finally { c.disconnect() }
    }

    override fun getFollow(url: String, headers: Map<String, String>): Pair<String, String> {
        val c = open(url, headers)
        try {
            val body = String(read(c), Charsets.UTF_8)
            return c.url.toString() to body
        } finally { c.disconnect() }
    }

    override fun post(url: String, json: String, headers: Map<String, String>): String {
        val c = open(url, headers)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return String(read(c), Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept-Encoding", "gzip")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        if (code !in 200..299) throw HttpException(code, c.url.toString())
        val s = if ("gzip".equals(c.contentEncoding, true)) GZIPInputStream(c.inputStream) else c.inputStream
        return s.use { it.readBytes() }
    }
}
