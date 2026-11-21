package com.shiguang.album.data.source

import android.net.Uri
import android.util.Xml
import com.shiguang.album.data.model.ConnectionResult
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.StorageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端：只依赖 PROPFIND + GET，兼容群晖 / 坚果云 / Nextcloud / Apache mod_dav 等。
 *
 * path 约定：相对于 source.shareName 的路径，以 "/" 开头，例如 "/2023/旅行"。
 */
class WebDavMediaSource(
    override val source: StorageSource,
    private val password: String
) : MediaSource {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val authHeader: String? =
        if (source.username.isBlank()) null else Credentials.basic(source.username, password)

    private val baseUrl: String = run {
        val host = source.host.trim()
            .removePrefix("http://").removePrefix("https://")
            .removePrefix("HTTP://").removePrefix("HTTPS://")
            .trimEnd('/')
        val scheme = if (source.secure) "https" else "http"
        val portPart = if (source.port > 0) ":${source.port}" else ""
        val basePath = normalizeBasePath(source.shareName)
        "$scheme://$host$portPart$basePath"
    }

    private val basePath: String = Uri.parse(baseUrl).path.orEmpty().trimEnd('/')

    private fun normalizeBasePath(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return when {
            t.isEmpty() || t == "/" -> ""
            t.startsWith("/") -> t
            else -> "/$t"
        }
    }

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) seg else Uri.encode(seg)
        }

    private fun urlFor(path: String, directory: Boolean = false): String {
        val clean = path.trim()
        val encoded = encodePath(if (clean.startsWith("/")) clean else "/$clean")
        val url = baseUrl + encoded
        return if (directory && !url.endsWith("/")) "$url/" else url
    }

    private fun requestBuilder(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        authHeader?.let { b.header("Authorization", it) }
        b.header("User-Agent", "TvAlbumPlayer/1.0")
        return b
    }

    override suspend fun test(): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
                |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
                .trimMargin()
            val req = requestBuilder(urlFor(""))
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "0")
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> online("WebDAV 可访问（${resp.code}）")
                    resp.code == 401 || resp.code == 403 -> authFailed("认证失败（HTTP ${resp.code}）")
                    resp.code == 404 -> offline("路径不存在：${basePath.ifEmpty { "/" }}")
                    else -> offline("HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            offline(t.message ?: "无法连接")
        }
    }

    override suspend fun list(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:">
            |  <d:prop>
            |    <d:resourcetype/>
            |    <d:getcontentlength/>
            |    <d:getlastmodified/>
            |  </d:prop>
            |</d:propfind>""".trimMargin()

        val req = requestBuilder(urlFor(path, directory = true))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            throw SourceException(t.message ?: "WebDAV 请求失败")
        }

        try {
            if (resp.code == 401 || resp.code == 403) throw SourceException("认证失败")
            if (!resp.isSuccessful) throw SourceException("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: return@withContext emptyList()
            parseMultiStatus(stream).mapNotNull { item ->
                val decoded = decodeHref(item.href)
                var rel = if (decoded.startsWith(basePath)) decoded.removePrefix(basePath) else decoded
                rel = rel.trimEnd('/')
                if (rel.isBlank()) return@mapNotNull null          // 目录自身
                val name = rel.substringAfterLast('/')
                if (name.startsWith(".")) return@mapNotNull null   // 隐藏文件
                if (!item.isDir && !MediaTypes.isMedia(name)) return@mapNotNull null
                entryOf(source, rel, name, item.isDir, item.size, item.modified)
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } finally {
            resp.close()
        }
    }

    override suspend fun openStream(entry: MediaEntry): InputStream = withContext(Dispatchers.IO) {
        val req = requestBuilder(urlFor(entry.path)).get().build()
        val resp = try {
            client.newCall(req).execute()
        } catch (t: Throwable) {
            throw SourceException(t.message ?: "WebDAV 下载失败")
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw SourceException("HTTP ${resp.code}")
        }
        val body = resp.body ?: run { resp.close(); throw SourceException("响应为空") }
        object : FilterInputStream(body.byteStream()) {
            override fun close() {
                runCatching { super.close() }
                resp.close()
            }
        }
    }

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    /* ----------------------------- XML 解析 ----------------------------- */

    private class DavItem {
        var href: String = ""
        var isDir: Boolean = false
        var size: Long = 0L
        var modified: Long = 0L
    }

    private fun local(name: String?): String =
        name?.substringAfter(':')?.lowercase(Locale.ROOT).orEmpty()

    private fun parseMultiStatus(input: InputStream): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(input, null)

        var cur: DavItem? = null
        var capture: String? = null
        val sb = StringBuilder()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser.name)) {
                    "response" -> { cur = DavItem(); capture = null }
                    "href" -> { capture = "href"; sb.setLength(0) }
                    "collection" -> cur?.isDir = true
                    "getcontentlength" -> { capture = "size"; sb.setLength(0) }
                    "getlastmodified" -> { capture = "mod"; sb.setLength(0) }
                }

                XmlPullParser.TEXT -> if (capture != null) sb.append(parser.text.orEmpty())

                XmlPullParser.END_TAG -> when (local(parser.name)) {
                    "href" -> if (capture == "href") {
                        cur?.href = sb.toString().trim(); capture = null
                    }
                    "getcontentlength" -> if (capture == "size") {
                        cur?.size = sb.toString().trim().toLongOrNull() ?: 0L; capture = null
                    }
                    "getlastmodified" -> if (capture == "mod") {
                        cur?.modified = parseHttpDate(sb.toString().trim()); capture = null
                    }
                    "response" -> { cur?.let { items.add(it) }; cur = null }
                }
            }
            // 解析到尾部时 XmlPullParser 会抛异常，正常结束即可；用 try/catch 而非 inline lambda，
            // 避免依赖实验性的「inline lambda 内 break」特性
            event = try {
                parser.next()
            } catch (e: Exception) {
                break
            }
        }
        return items
    }

    private fun decodeHref(href: String): String {
        if (href.isBlank()) return ""
        return if (href.startsWith("http", ignoreCase = true)) {
            Uri.parse(href).path.orEmpty()
        } else {
            Uri.decode(href)
        }
    }

    private fun parseHttpDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            runCatching {
                val f = SimpleDateFormat(p, Locale.US)
                f.timeZone = java.util.TimeZone.getTimeZone("GMT")
                return f.parse(raw)?.time ?: 0L
            }
        }
        return 0L
    }
}
