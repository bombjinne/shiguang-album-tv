package com.shiguang.album.data.source

import com.shiguang.album.data.model.OnlineSong
import com.shiguang.album.data.store.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线音乐客户端。
 *
 * 一、默认：内置直连（不需要任何服务端）
 *   网易云：搜索走 /api/search/get/web，取流走 /song/media/outer/url（免费曲直接给 mp3 地址）
 *   酷我　：搜索走 search.kuwo.cn/r.s，取流走 antiserver.kuwo.cn/anti.s
 *   两者都在 App 内直接完成"搜 → 播"，装完就能用，和 go-music-dl 的
 *   "Android 版把服务内嵌在本机"是同一个思路。
 *
 * 二、可选：填一个自建聚合服务地址（go-music-dl / music-lib）
 *   这类项目本身就是一个自托管服务（官方没有公网实例），好处是一次覆盖
 *   QQ/酷狗/咪咕/B站 等 13 个音源。填了就用它，不填也不影响听歌。
 *
 * 播放地址不落盘：多数平台地址带时效签名，存下来过期就播不了，每次播放现取。
 */
class OnlineMusicService(private val store: ConfigStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 取流专用：需要跟随 302 拿到最终地址，超时给足一点 */
    private val streamHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /* --------------------------- 配置 --------------------------- */

    /** 自建聚合服务地址，空 = 只用内置直连 */
    fun baseUrl(): String {
        var raw = store.current.settings.musicServerUrl.trim()
        if (raw.isEmpty()) return ""
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) raw = "http://$raw"
        return raw.trimEnd('/')
    }

    fun isServerConfigured(): Boolean = baseUrl().isNotEmpty()

    /** 内置直连永远可用，所以这里恒为 true（"能不能听歌"不再取决于有没有搭服务） */
    fun isAvailable(): Boolean = true

    fun modeText(): String =
        if (isServerConfigured()) "已接入自建聚合服务（13 个音源）" else "内置直连：网易云 + 酷我"

    /* ---------------------------- 搜索 ---------------------------- */

    suspend fun search(keyword: String, sources: String = ""): Result<List<OnlineSong>> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext Result.success(emptyList())

            // 首选自建服务（音源多），失败或没配就回落到内置直连
            val base = baseUrl()
            if (base.isNotEmpty()) {
                val viaServer = runCatching { serverSearch(base, keyword, sources) }
                if (viaServer.isSuccess && viaServer.getOrThrow().isNotEmpty()) {
                    return@withContext viaServer
                }
            }
            runCatching { builtinSearch(keyword) }
        }

    /* ------------------------- 播放地址 ------------------------- */

    /**
     * 音频流地址。顺序：平台直连 > 自建服务。
     * 直连对 VIP 曲会拿到 404，这时如果配了服务就用服务的代理流再试一次。
     */
    suspend fun streamUrl(song: OnlineSong): String = withContext(Dispatchers.IO) {
        val direct = when (song.source.lowercase()) {
            "netease" -> neteaseStream(song.id)
            "kuwo" -> kuwoStream(song.id)
            else -> ""
        }
        if (direct.isNotBlank()) return@withContext direct

        val base = baseUrl()
        if (base.isEmpty()) return@withContext ""
        serverStreamUrl(base, song)
    }

    /**
     * 「这首歌到底能不能播」——选歌 / 试听之前先真实请求一次，
     * 别等进了播放器、过了两分钟才发现放不出来。
     *
     * 只读前 2KB（Range），不会把整首歌拉下来；同时校验 Content-Type，
     * 因为有的平台"取流失败"会返回一个 HTML 错误页而不是 404。
     */
    suspend fun resolvePlayable(song: OnlineSong): Result<String> = withContext(Dispatchers.IO) {
        val url = runCatching { streamUrl(song) }.getOrElse { return@withContext Result.failure(it) }
        if (url.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (song.vip) "VIP 曲目拿不到音频，换一首免费的吧" else "拿不到播放地址，可能已下架"
                )
            )
        }

        runCatching {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA_DESKTOP)
                .header("Range", "bytes=0-2047")
            // 网易云 CDN 偶尔校验来源页，带上 Referer 才不会误判成"放不了"
            if (url.contains("126.net") || url.contains("163.com")) {
                builder.header("Referer", "$NETEASE/")
            }
            streamHttp.newCall(builder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) error("音频地址不可访问（HTTP ${resp.code}）")
                val type = resp.header("Content-Type").orEmpty().lowercase()
                if (type.contains("html") || type.contains("json") || type.contains("xml")) {
                    error("返回的不是音频")
                }
                val buf = ByteArray(2048)
                val n = resp.body?.byteStream()?.use { it.read(buf) } ?: -1
                if (n <= 0) error("音频是空的")
            }
            url
        }
    }

    /* ======================= 内置直连：网易云 ======================= */

    private fun neteaseSearch(keyword: String): List<OnlineSong> {
        val req = Request.Builder()
            .url(
                "https://music.163.com/api/search/get/web?csrf_token=&type=1&offset=0&total=true&limit=30" +
                    "&s=" + encode(keyword)
            )
            .header("User-Agent", UA_DESKTOP)
            .header("Referer", "$NETEASE/")
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        return parseNetease(body)
    }

    private fun parseNetease(body: String): List<OnlineSong> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val songs = runCatching {
            root.jsonObject["result"]?.jsonObject?.get("songs")?.jsonArray
        }.getOrNull() ?: return emptyList()

        val list = songs.mapNotNull { el ->
            val o: JsonObject = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            // 两个版本的接口一个用 artists/album，一个用 ar/al，都兼容
            val artists = (o["artists"] ?: o["ar"])?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("/")
                .orEmpty()
            val albumObj = (o["album"] ?: o["al"])?.jsonObject
            val album = albumObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            val cover = albumObj?.get("picUrl")?.jsonPrimitive?.contentOrNull.orEmpty()
            val durationMs = o["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: o["dt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val fee = o["fee"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

            OnlineSong(
                id = id,
                source = "netease",
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = artists,
                album = album,
                durationSec = (durationMs / 1000).toInt(),
                cover = cover,
                vip = fee != 0
            )
        }
        // 免费曲排前面：直连能直接播，体验最好
        return list.sortedBy { if (it.vip) 1 else 0 }
    }

    private fun neteaseStream(id: String): String {
        if (id.isBlank()) return ""
        return runCatching {
            val req = Request.Builder()
                .url("$NETEASE/song/media/outer/url?id=$id.mp3")
                .header("User-Agent", UA_DESKTOP)
                .header("Referer", "$NETEASE/")
                .header("Range", "bytes=0-1")
                .get()
                .build()
            streamHttp.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                // 付费/下架会 302 到 /404
                if (finalUrl.contains("/404") || !resp.isSuccessful) "" else finalUrl
            }
        }.getOrDefault("")
    }

    /* ======================== 内置直连：酷我 ======================== */

    private fun kuwoSearch(keyword: String): List<OnlineSong> {
        val url = "$KUWO_SEARCH?all=${encode(keyword)}" +
            "&ft=music&itemset=web_2013&client=kt&pn=0&rn=20&rformat=json&encoding=utf8"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA_DESKTOP)
            .get()
            .build()

        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }

        // 酷我返回的是"单引号 JSON"，用正则逐条抠字段比补齐成 JSON 更稳
        val blocks = Regex("""\{'AARTIST'[^{}]*}""").findAll(body).map { it.value }
        return blocks.mapNotNull { b ->
            val id = kuwoField(b, "DC_TARGETID").ifBlank { kuwoField(b, "MUSICRID").removePrefix("MUSIC_") }
            val name = kuwoField(b, "NAME")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            OnlineSong(
                id = id,
                source = "kuwo",
                name = name,
                artist = kuwoField(b, "ARTIST"),
                album = kuwoField(b, "ALBUM"),
                durationSec = kuwoField(b, "DURATION").toIntOrNull() ?: 0,
                vip = kuwoField(b, "PAY") != "0"
            )
        }.sortedBy { if (it.vip) 1 else 0 }.toList()
    }

    private fun kuwoStream(id: String): String {
        if (id.isBlank()) return ""
        val rid = if (id.startsWith("MUSIC_")) id else "MUSIC_$id"
        return runCatching {
            val req = Request.Builder()
                .url("$KUWO_PLAY?type=convert_url&format=mp3&response=url&rid=$rid")
                .header("User-Agent", UA_DESKTOP)
                .get()
                .build()
            val body = streamHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
            }.trim()
            if (body.startsWith("http")) body else ""
        }.getOrDefault("")
    }

    private fun kuwoField(block: String, key: String): String {
        val m = Regex("""'$key':'(.*?)'(?=,|})""").find(block) ?: return ""
        return m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
    }

    /* ---------------------------- 内置聚合 ---------------------------- */

    private suspend fun builtinSearch(keyword: String): List<OnlineSong> = coroutineScope {
        val netease = async { runCatching { neteaseSearch(keyword) }.getOrDefault(emptyList()) }
        val kuwo = async { runCatching { kuwoSearch(keyword) }.getOrDefault(emptyList()) }
        // 两个平台交错合并，界面上能一眼看出"同一首歌多个来源"
        val a = netease.await()
        val b = kuwo.await()
        val merged = mutableListOf<OnlineSong>()
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let { merged += it }
            b.getOrNull(i)?.let { merged += it }
        }
        if (merged.isEmpty()) error("没有搜到结果，检查一下网络或换个关键词")
        merged
    }

    /* ======================= 自建聚合服务（可选） ======================= */

    suspend fun test(rawUrl: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val base = (rawUrl ?: baseUrl()).let {
            var v = it.trim()
            if (v.isNotEmpty() && !v.startsWith("http")) "http://$v" else v
        }.trimEnd('/')
        if (base.isEmpty()) return@withContext Result.success("未填写地址，使用内置直连（网易云 + 酷我）")

        runCatching {
            val req = Request.Builder().url("$base/healthz").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("服务返回 HTTP ${resp.code}")
                if (!body.contains("go-music-dl") && !body.contains("ok")) error("返回内容不像音乐服务")
            }
            "连接正常"
        }.recoverCatching {
            val req = Request.Builder().url("$base/search?q=test&type=song").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("连接失败：HTTP ${resp.code}")
            }
            "连接正常（healthz 不可用，已用搜索页验证）"
        }
    }

    private fun serverSearch(base: String, keyword: String, sources: String): List<OnlineSong> {
        val url = buildString {
            append(base).append("/search?q=").append(encode(keyword)).append("&type=song")
            sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                append("&sources=").append(encode(it))
            }
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ShiGuangAlbum/1.1")
            .get()
            .build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("搜索失败：HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        return parseServerSongs(html)
    }

    private fun serverStreamUrl(base: String, song: OnlineSong): String = buildString {
        append(base).append("/download")
        append("?id=").append(encode(song.id))
        append("&source=").append(encode(song.source))
        append("&name=").append(encode(song.name))
        append("&artist=").append(encode(song.artist))
        append("&album=").append(encode(song.album))
        if (song.cover.isNotBlank()) append("&cover=").append(encode(song.cover))
        if (song.extraJson.isNotBlank()) append("&extra=").append(encode(song.extraJson))
        append("&stream=1")
    }

    /**
     * 从服务端搜索页里抠歌曲：Go html/template 每首歌是一个 `<li class="song-card" ...>`，
     * 关键字段都在 data-* 上，正则足够且对模板小改动有容忍度。
     */
    private fun parseServerSongs(html: String): List<OnlineSong> {
        val out = mutableListOf<OnlineSong>()
        Regex("""<li class="song-card"([^>]*)>""").findAll(html).forEach { match ->
            val block = match.groupValues[1]
            val id = attr(block, "data-id")
            val source = attr(block, "data-source")
            if (id.isBlank() || source.isBlank()) return@forEach
            if (source == "local" || source == "local-file") return@forEach

            out += OnlineSong(
                id = id,
                source = source,
                name = attr(block, "data-name"),
                artist = attr(block, "data-artist"),
                album = attr(block, "data-album"),
                durationSec = attr(block, "data-duration").toIntOrNull() ?: 0,
                cover = attr(block, "data-cover"),
                extraJson = singleQuotedAttr(block, "data-extra").let {
                    if (it == "null" || it == "{}") "" else it
                }
            )
        }
        return out.distinctBy { it.source + "|" + it.id }
    }

    private fun attr(block: String, name: String): String {
        val m = Regex("""$name="([^"]*)"""").find(block) ?: return ""
        return unescape(m.groupValues[1])
    }

    private fun singleQuotedAttr(block: String, name: String): String {
        val m = Regex("""$name='([^']*)'""").find(block) ?: return ""
        return unescape(m.groupValues[1])
    }

    /** Go html/template 会把引号转成 &#34; / &#39;，需要还原才能用 */
    private fun unescape(s: String): String = s
        .replace("&#34;", "\"")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        const val NETEASE = "https://music.163.com"
        const val KUWO_SEARCH = "http://search.kuwo.cn/r.s"
        const val KUWO_PLAY = "http://antiserver.kuwo.cn/anti.s"
        const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
