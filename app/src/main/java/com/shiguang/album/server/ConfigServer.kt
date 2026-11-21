package com.shiguang.album.server

import android.content.Context
import com.shiguang.album.data.cache.MediaCache
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.ThemeMode
import com.shiguang.album.data.model.WallpaperKind
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.source.MediaRepository
import com.shiguang.album.data.source.MediaTypes
import com.shiguang.album.data.source.OnlineMusicService
import com.shiguang.album.data.store.ConfigStore
import com.shiguang.album.data.store.VideoWallpaperStore
import com.shiguang.album.util.NetworkUtil
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections

/**
 * 内嵌配置服务：电视上跑一个轻量 HTTP 服务，手机浏览器打开就能改配置，不用装 App。
 *
 * - GET  /            配置页（assets/config/index.html，单文件，CSS/JS 全内联）
 * - REST /api/xxx     存储源 / 清单增删改查、目录浏览、缩略图
 * - WS   /api/ws      配置变更实时推送
 *
 * 手机端改完，电视端 ConfigStore 立刻更新，首页会自动刷新，无需重启。
 */
class ConfigServer(
    private val context: Context,
    private val store: ConfigStore,
    private val repo: MediaRepository,
    private val cache: MediaCache,
    private val music: OnlineMusicService,
    private val video: VideoWallpaperStore
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private companion object {
        /** 手机上传允许的容器格式 */
        val VIDEO_UPLOAD_EXT = setOf("mp4", "mkv", "mov", "webm", "m4v", "avi", "3gp")

        /** 单个文件上限：电视盒子存储一般不大，500MB 足够放一段几分钟的 1080p */
        const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024

        /** 缩略图可以缓存：URL 里带了 path，换图就换 URL。但同路径覆盖更新时 URL 不变，
         *  所以只给 1 小时，别让手机端拿着旧图一个星期。 */
        const val CACHE_HEADER = "Cache-Control"
        const val CACHE_VALUE = "public, max-age=3600"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets: MutableSet<DefaultWebSocketServerSession> =
        Collections.synchronizedSet(mutableSetOf())

    private var engine: ApplicationEngine? = null
    private var watcher: Job? = null

    /** 实际监听端口（8080 被占用时会自动顺延） */
    var actualPort: Int = 0
        private set

    /** 启动失败的原因（给 UI 提示用，成功时为 null） */
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = engine != null

    fun baseUrl(): String = "http://${NetworkUtil.lanIpAddress()}:${if (actualPort > 0) actualPort else store.current.settings.port}/"

    /** 配置页地址；设置了访问密码时带上 pin 参数，扫码即进 */
    fun configUrl(): String {
        val pin = store.decryptPassword(store.current.settings.pinEnc)
        val base = baseUrl()
        return if (pin.isBlank()) base else "$base?pin=$pin"
    }

    fun start(): Boolean {
        if (engine != null) return true

        // 端口要自己先验一遍再交给 Ktor：
        // CIO 引擎的 bind 发生在它自己的协程里，一旦端口被占用，抛出的 BindException
        // 不属于任何调用栈，会变成「未捕获异常」把整个进程干掉 —— 电视上一开就闪退就是这个原因
        // （实测某电视盒子 8080、8081 被系统投屏服务占着，IPv6 dual-stack 也会抢 IPv4 的同一个端口）。
        val preferred = store.current.settings.port.coerceIn(1024, 65535)
        val candidates = ((preferred..(preferred + 20)) + (8080..8099) + (18080..18090))
            .filter { it in 1024..65535 }
            .distinct()

        for (port in candidates) {
            if (!isPortFree(port)) continue
            val started = runCatching {
                embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }
                    .also { it.start(wait = false) }
            }.getOrNull() ?: continue

            // start(wait = false) 立刻返回，这里等它真正接管端口；
            // 没接管上（还有别的竞态）就停掉换下一个，绝不让它留个半死引擎
            if (!waitUntilBound(port, 1500)) {
                runCatching { started.stop(200, 600) }
                continue
            }
            engine = started
            actualPort = port
            lastError = null
            startWatcher()
            return true
        }

        actualPort = 0
        lastError = "8080-8099 端口都被占用了，手机配置暂时不可用（电视播放不受影响）"
        return false
    }

    /**
     * 真的能绑才算空闲。
     * 绑 0.0.0.0 与 Ktor 保持一致 —— 电视上很多服务监听在 [::]:8080，
     * 它是 dual-stack，会同时占住 IPv4 的 8080，只看 IPv4 列表会误判为空闲。
     */
    private fun isPortFree(port: Int): Boolean = runCatching {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(java.net.InetSocketAddress("0.0.0.0", port), 1)
        }
        true
    }.getOrDefault(false)

    /** 端口被别人（也就是刚启动的 Ktor）占住 = 绑定成功 */
    private fun waitUntilBound(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isPortFree(port)) return true
            runCatching { Thread.sleep(60) }
        }
        return false
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        sockets.clear()
        runCatching { engine?.stop(500, 1500) }
        engine = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    /* --------------------------- 变更推送 --------------------------- */

    private fun startWatcher() {
        watcher = scope.launch {
            store.revision.collect { rev ->
                val payload = """{"type":"state","revision":$rev}"""
                val snapshot = synchronized(sockets) { sockets.toList() }
                snapshot.forEach { session ->
                    scope.launch { runCatching { session.send(Frame.Text(payload)) } }
                }
            }
        }
    }

    /* ----------------------------- 路由 ----------------------------- */

    private fun io.ktor.server.application.Application.module() {
        install(WebSockets)

        routing {
            // 页面本身绝不缓存：改完 JS 手机上还揣着旧页面，会出现「明明修了还是不好用」
            get("/") {
                call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
                call.respondText(configHtml(), ContentType.Text.Html, HttpStatusCode.OK)
            }

            get("/favicon.ico") { call.respondText("", ContentType.Text.Plain) }

            route("/api") {

                // 配置快照
                get("/state") {
                    if (!call.guard()) return@get
                    val st = store.current
                    val dto = StateDto(
                        revision = store.revision.value,
                        url = configUrl(),
                        pinRequired = st.settings.pinEnc.isNotBlank(),
                        sources = st.sources.map { it.toDto() },
                        playlists = st.playlists.sortedBy { it.sortIndex },
                        appearance = st.settings.toAppearanceDto()
                            .copy(wallpaperVideoCount = video.clips().size),
                        musicServerUrl = st.settings.musicServerUrl
                    )
                    call.json(json.encodeToString(dto))
                }

                // 目录 / 文件浏览（文件夹树、音乐选择都用它）
                get("/browse") {
                    if (!call.guard()) return@get
                    val sourceId = call.request.queryParameters["sourceId"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    if (sourceId.isBlank()) {
                        call.json("""{"path":"","entries":[]}""")
                        return@get
                    }
                    val entries = runCatching { repo.list(sourceId, path) }.getOrDefault(emptyList())
                    val dto = BrowseDto(
                        path = path,
                        entries = entries.map {
                            BrowseEntryDto(
                                name = it.name,
                                path = it.path,
                                dir = it.isDirectory,
                                kind = when {
                                    it.isDirectory -> "dir"
                                    it.kind == MediaKind.VIDEO -> "video"
                                    it.kind == MediaKind.AUDIO -> "audio"
                                    else -> "image"
                                }
                            )
                        }
                    )
                    call.json(json.encodeToString(dto))
                }

                // 缩略图 / 封面预览
                //
                // 手机配置页只显示 64~72px 的小图，以前这里直接把原图（几 MB、8000px）吐回去，
                // 4G 下转半天还是白块。现在照片一律走 MediaCache.cover()：
                // 电视端采样解码成 maxPx 的 JPEG（质量 82）+ 落盘缓存，手机再来直接吃这张小图。
                get("/thumb") {
                    if (!call.guard()) return@get
                    val sourceId = call.request.queryParameters["sourceId"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    if (sourceId.isBlank() || path.isBlank()) {
                        call.respondText("", ContentType.Text.Plain, HttpStatusCode.BadRequest); return@get
                    }
                    val source = store.source(sourceId)
                    if (source == null) {
                        call.respondText("", ContentType.Text.Plain, HttpStatusCode.NotFound); return@get
                    }
                    val entry = com.shiguang.album.data.model.MediaEntry(
                        sourceId = source.id,
                        sourceName = source.name,
                        path = path,
                        name = path.substringAfterLast('/'),
                        kind = MediaTypes.kindOf(path)
                    )
                    // 手机页可传 size=180/320/720；不给就按 480 长边，够卡片和预览用了
                    val maxPx = call.request.queryParameters["size"]?.toIntOrNull()
                        ?.coerceIn(96, 1440) ?: 480

                    if (entry.kind == com.shiguang.album.data.model.MediaKind.IMAGE) {
                        val thumb = runCatching { cache.cover(entry, maxPx) }.getOrNull()
                        if (thumb != null && thumb.length() > 0) {
                            call.response.headers.append(CACHE_HEADER, CACHE_VALUE)
                            // 用 kotlin.io 的 readBytes，别用 java.nio.file.Files（API 26+，本项目 minSdk 21）
                            call.respondBytes(thumb.readBytes(), ContentType.Image.JPEG)
                            return@get
                        }
                        // 解不出来（HEIC/RAW 之类）就退回原图，让浏览器自己试试
                    }

                    val bytes = runCatching { cache.bytes(entry, 8L * 1024 * 1024) }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        call.respondText("", ContentType.Text.Plain, HttpStatusCode.NotFound)
                    } else {
                        call.response.headers.append(CACHE_HEADER, CACHE_VALUE)
                        call.respondBytes(bytes, ContentType.parse(MediaTypes.mimeOf(entry.name)))
                    }
                }

                // 新增存储源
                post("/sources") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<SourceDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    val model = dto.toModel(null) { store.encryptPassword(it) }
                    store.upsertSource(model)
                    call.json(json.encodeToString(model.toDto()))
                }

                // 修改存储源
                put("/sources/{id}") {
                    if (!call.guard()) return@put
                    val id = call.parameters["id"].orEmpty()
                    val existing = store.source(id)
                        ?: return@put call.json("""{"error":"源不存在"}""", HttpStatusCode.NotFound)
                    val dto = runCatching { json.decodeFromString<SourceDto>(call.receiveText()) }.getOrNull()
                        ?: return@put call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    val model = dto.toModel(existing) { store.encryptPassword(it) }
                    store.upsertSource(model)
                    call.json(json.encodeToString(model.toDto()))
                }

                delete("/sources/{id}") {
                    if (!call.guard()) return@delete
                    val id = call.parameters["id"].orEmpty()
                    store.deleteSource(id)
                    call.json(json.encodeToString(MsgDto(true, "已删除")))
                }

                // 测试连接
                post("/sources/{id}/test") {
                    if (!call.guard()) return@post
                    val id = call.parameters["id"].orEmpty()
                    val result = repo.testSource(id)
                    call.json(json.encodeToString(TestDto(result.ok, result.state.name, result.message)))
                }

                /* ------------------------ 播放清单 ------------------------ */

                post("/playlists") {
                    if (!call.guard()) return@post
                    val raw = runCatching { json.decodeFromString<Playlist>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    /*
                     * 新建接口必须给新 id，且要在 store 之外先补好 —— 这样返回给手机端的就是
                     * 真正落盘的那一条，前端不用再猜。
                     *
                     * 手机页以前提交的是 `id: ""`，服务端照单全收，于是第二次新建命中 id="" 那条，
                     * 变成「覆盖上一个清单」。这里对空串做兜底，老版本页面也能正常新增。
                     */
                    val pl = if (raw.id.isBlank()) raw.copy(id = java.util.UUID.randomUUID().toString()) else raw
                    store.upsertPlaylist(pl)
                    call.json(json.encodeToString(pl))
                }

                put("/playlists/{id}") {
                    if (!call.guard()) return@put
                    val id = call.parameters["id"].orEmpty()
                    if (store.playlist(id) == null) {
                        return@put call.json("""{"error":"清单不存在"}""", HttpStatusCode.NotFound)
                    }
                    val pl = runCatching { json.decodeFromString<Playlist>(call.receiveText()) }.getOrNull()
                        ?: return@put call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    store.upsertPlaylist(pl.copy(id = id))
                    call.json(json.encodeToString(pl.copy(id = id)))
                }

                delete("/playlists/{id}") {
                    if (!call.guard()) return@delete
                    store.deletePlaylist(call.parameters["id"].orEmpty())
                    call.json(json.encodeToString(MsgDto(true, "已删除")))
                }

                post("/playlists/{id}/duplicate") {
                    if (!call.guard()) return@post
                    val copy = store.duplicatePlaylist(call.parameters["id"].orEmpty())
                        ?: return@post call.json("""{"error":"清单不存在"}""", HttpStatusCode.NotFound)
                    call.json(json.encodeToString(copy))
                }

                post("/playlists/reorder") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<ReorderDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    store.reorderPlaylists(dto.ids)
                    call.json(json.encodeToString(MsgDto(true, "已排序")))
                }

                /* --------------------------- 外观 --------------------------- */

                post("/appearance") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<AppearanceDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)

                    store.updateSettings { st ->
                        val wp = st.wallpaper
                        val nextWallpaper = wp.copy(
                            kind = when (dto.wallpaperKind) {
                                "NONE" -> WallpaperKind.NONE
                                "GRADIENT" -> WallpaperKind.GRADIENT
                                "IMAGE" -> WallpaperKind.IMAGE
                                "VIDEO" -> WallpaperKind.VIDEO
                                else -> wp.kind
                            },
                            preset = if (dto.wallpaperPreset >= 0) dto.wallpaperPreset else wp.preset,
                            dim = if (dto.wallpaperDim >= 0f) dto.wallpaperDim.coerceIn(0f, 0.85f) else wp.dim,
                            blur = dto.wallpaperBlur ?: wp.blur,
                            videoLoop = dto.wallpaperVideoLoop ?: wp.videoLoop,
                            file = when {
                                dto.clearWallpaperImage -> null
                                dto.wallpaperSourceId.isNotBlank() && dto.wallpaperPath.isNotBlank() ->
                                    com.shiguang.album.data.model.FileRef(dto.wallpaperSourceId, dto.wallpaperPath)
                                else -> wp.file
                            }
                        )
                        st.copy(
                            themeMode = when (dto.themeMode) {
                                "DARK" -> ThemeMode.DARK
                                "LIGHT" -> ThemeMode.LIGHT
                                "AUTO" -> ThemeMode.AUTO
                                else -> st.themeMode
                            },
                            accentColor = if (dto.accentColor != 0L) {
                                // 老页面提交的是 6 位 RGB，补上不透明位，否则电视端拿到的是透明色
                                val c = dto.accentColor
                                if ((c ushr 24) and 0xFFL == 0L) c or 0xFF000000L else c
                            } else st.accentColor,
                            showModeBadge = dto.showModeBadge ?: st.showModeBadge,
                            wallpaper = nextWallpaper
                        )
                    }
                    call.json(json.encodeToString(MsgDto(true, "已更新外观")))
                }

                /* --------------------------- 视频壁纸 --------------------------- */

                // 当前视频清单（手机页「外观 → 视频」用）
                get("/wallpaper/videos") {
                    if (!call.guard()) return@get
                    val clips = video.clips()
                    val dto = VideoListDto(
                        clips = clips.map {
                            VideoClipDto(
                                name = it.file.name,
                                title = it.title,
                                size = it.sizeBytes,
                                uploaded = it.uploaded
                            )
                        },
                        total = clips.sumOf { it.sizeBytes },
                        active = store.current.settings.wallpaper.kind == WallpaperKind.VIDEO
                    )
                    call.json(json.encodeToString(dto))
                }

                /**
                 * 手机扫码上传视频。
                 *
                 * 刻意不用 multipart：手机端直接把文件字节作为请求体 POST（`fetch(url, {body: file})`），
                 * 文件名走 `?name=`。这样服务端只要一个 `receiveStream()`，
                 * 手机上也不用拼 FormData，多选就循环发几次，每次都能单独报进度。
                 *
                 * 大小上限 500MB：电视盒子存储通常不大，够放一段几分钟的 1080p。
                 */
                post("/wallpaper/upload") {
                    if (!call.guard()) return@post
                    val original = call.request.queryParameters["name"]
                        ?.takeIf { it.isNotBlank() }
                        ?.substringAfterLast('/')
                        ?.substringAfterLast('\\')
                        ?: "video.mp4"
                    val ext = original.substringAfterLast('.', "").lowercase()
                    if (ext !in VIDEO_UPLOAD_EXT) {
                        return@post call.json(
                            json.encodeToString(MsgDto(false, "只支持 ${VIDEO_UPLOAD_EXT.joinToString(" / ")} 格式")),
                            HttpStatusCode.BadRequest
                        )
                    }

                    val tmp = File(
                        this@ConfigServer.context.cacheDir,
                        "upload-${System.currentTimeMillis()}.$ext"
                    )
                    val ok = try {
                        call.receiveStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output, 1024 * 64) }
                        }
                        tmp.length() in 1..MAX_UPLOAD_BYTES
                    } catch (_: Exception) {
                        false
                    }

                    if (!ok) {
                        tmp.delete()
                        return@post call.json(
                            json.encodeToString(
                                MsgDto(false, "上传失败或超过 ${MAX_UPLOAD_BYTES / 1024 / 1024} MB")
                            )
                        )
                    }

                    val clip = video.addUpload(tmp, original)
                    tmp.delete()
                    if (clip == null) {
                        return@post call.json(json.encodeToString(MsgDto(false, "写入本机失败，检查电视存储空间")))
                    }

                    // 传完直接切到视频壁纸：用户点上传就是想立刻看到
                    store.updateSettings { st ->
                        st.copy(
                            wallpaper = st.wallpaper.copy(
                                kind = WallpaperKind.VIDEO,
                                dim = if (st.wallpaper.dim <= 0f) 0.5f else st.wallpaper.dim
                            )
                        )
                    }
                    call.json(json.encodeToString(MsgDto(true, "已上传「$original」")))
                }

                // 从存储源追加一段（手机端「从存储源选」）
                post("/wallpaper/videos/add") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<VideoSourceDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    if (dto.sourceId.isBlank() || dto.path.isBlank()) {
                        return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    }
                    val clip = video.addSource(
                        com.shiguang.album.data.model.FileRef(dto.sourceId, dto.path)
                    )
                    if (clip == null) {
                        return@post call.json(json.encodeToString(MsgDto(false, "复制失败：源不可达或格式不支持")))
                    }
                    store.updateSettings { st ->
                        st.copy(
                            wallpaper = st.wallpaper.copy(
                                kind = WallpaperKind.VIDEO,
                                dim = if (st.wallpaper.dim <= 0f) 0.5f else st.wallpaper.dim
                            )
                        )
                    }
                    call.json(json.encodeToString(MsgDto(true, "已添加「${clip.title}」")))
                }

                // 删除某一段
                post("/wallpaper/videos/remove") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<VideoNameDto>(call.receiveText()) }.getOrNull()
                    val name = dto?.name.orEmpty()
                    if (name.isBlank()) return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    val ok = video.remove(name)
                    // 删空了自己退回渐变，避免首页留一块占位
                    if (ok && video.isEmpty()) {
                        store.updateSettings { st ->
                            st.copy(
                                wallpaper = st.wallpaper.copy(
                                    kind = WallpaperKind.GRADIENT,
                                    file = null
                                )
                            )
                        }
                    }
                    call.json(json.encodeToString(MsgDto(ok, if (ok) "已删除" else "没找到这段视频")))
                }

                // 调整轮播顺序
                post("/wallpaper/videos/reorder") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<VideoReorderDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    video.reorder(dto.names)
                    call.json(json.encodeToString(MsgDto(true, "顺序已更新")))
                }

                /* --------------------------- 在线音乐 --------------------------- */
                // 手机端不做跨域直连，统一走电视这台手机器：内置直连和自建服务都能用同一套接口
                get("/music/search") {
                    if (!call.guard()) return@get
                    val q = call.request.queryParameters["q"].orEmpty()
                    val result = runCatching { music.search(q) }
                    val songs = result.getOrNull()?.getOrNull().orEmpty()
                    val err = result.exceptionOrNull()?.message
                        ?: result.getOrNull()?.exceptionOrNull()?.message
                    val payload = buildString {
                        append("""{"songs":""")
                        append(json.encodeToString(songs))
                        append(""","mode":"""").append(if (music.isServerConfigured()) "server" else "builtin")
                        append("""","error":"""").append(err.orEmpty().replace("\"", "'"))
                        append(""""}""")
                    }
                    call.json(payload)
                }

                // 手机端点「试听 / 选用」前先来这里验证一次：电视负责解析，手机只管放
                get("/music/probe") {
                    if (!call.guard()) return@get
                    val p = call.request.queryParameters
                    val song = com.shiguang.album.data.model.OnlineSong(
                        id = p["id"].orEmpty(),
                        source = p["source"].orEmpty(),
                        name = p["name"].orEmpty(),
                        artist = p["artist"].orEmpty(),
                        album = p["album"].orEmpty(),
                        cover = p["cover"].orEmpty(),
                        vip = p["vip"] == "1",
                        extraJson = p["extra"].orEmpty()
                    )
                    val result = runCatching { music.resolvePlayable(song) }
                    val url = result.getOrNull()?.getOrNull().orEmpty()
                    val err = result.exceptionOrNull()?.message
                        ?: result.getOrNull()?.exceptionOrNull()?.message
                    val payload = buildString {
                        append("""{"ok":""").append(url.isNotBlank())
                        append(""","url":""").append(json.encodeToString(url))
                        append(""","error":""").append(json.encodeToString(err.orEmpty()))
                        append("""}""")
                    }
                    call.json(payload)
                }

                /* ----------------------- 本机文件系统浏览 ----------------------- */
                // 手机端选目录用：直接列电视上的真实目录，避免让用户手打绝对路径

                get("/fs") {
                    if (!call.guard()) return@get
                    val requested = call.request.queryParameters["path"].orEmpty()
                    val root = File("/storage/emulated/0").takeIf { it.isDirectory }
                        ?: File("/storage")
                    val target = if (requested.isBlank()) root else File(requested)

                    val shortcuts = listOf(
                        "$root/Pictures", "$root/DCIM", "$root/DCIM/Camera",
                        "$root/Movies", "$root/Movies/Aerial", "$root/Download", "$root/Android/media"
                    ).filter { runCatching { File(it).isDirectory }.getOrDefault(false) }
                        .map { FsDirDto(File(it).name, it) }

                    val readable = runCatching { target.isDirectory && target.canRead() }.getOrDefault(false)
                    val dirs = if (readable) {
                        runCatching {
                            target.listFiles()
                                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                                ?.sortedBy { it.name.lowercase() }
                                ?.map { FsDirDto(it.name, it.absolutePath) }
                                ?: emptyList()
                        }.getOrDefault(emptyList())
                    } else emptyList()

                    val parent = target.parentFile?.absolutePath.orEmpty()
                    val dto = FsDto(
                        path = target.absolutePath,
                        parent = parent,
                        canUp = parent.isNotBlank() && target.absolutePath != root.absolutePath,
                        exists = target.exists(),
                        readable = readable,
                        dirs = dirs,
                        shortcuts = shortcuts,
                        error = if (!readable) "这个目录读不到，换一个试试（Android 11+ 对部分目录有限制）" else ""
                    )
                    call.json(json.encodeToString(dto))
                }

                /* ------------------------- 系统设置 ------------------------- */

                post("/settings") {
                    if (!call.guard()) return@post
                    val dto = runCatching { json.decodeFromString<SettingsDto>(call.receiveText()) }.getOrNull()
                        ?: return@post call.json("""{"error":"参数错误"}""", HttpStatusCode.BadRequest)
                    store.updateSettings { st ->
                        st.copy(
                            pinEnc = when {
                                dto.clearPin -> ""
                                dto.pin.isNotBlank() -> store.encryptPassword(dto.pin)
                                else -> st.pinEnc          // 留空 = 不改密码
                            },
                            port = if (dto.port in 1024..65535) dto.port else st.port,
                            musicServerUrl = dto.musicServerUrl?.trim() ?: st.musicServerUrl
                        )
                    }
                    call.json(json.encodeToString(MsgDto(true, "已保存，改端口需重启应用生效")))
                }

                get("/system") {
                    if (!call.guard()) return@get
                    call.json(
                        """{"ip":"${NetworkUtil.lanIpAddress()}","port":$actualPort,"cacheMB":${cache.sizeBytes() / 1024 / 1024}}"""
                    )
                }

                // 实时同步：配置一改就推一条消息，手机端收到后重新拉 /api/state
                webSocket("/ws") {
                    sockets.add(this)
                    runCatching { send(Frame.Text("""{"type":"hello","revision":${store.revision.value}}""")) }
                    try {
                        for (frame in incoming) {
                            // 手机端只收不发，这里保持连接即可
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        sockets.remove(this)
                    }
                }
            }
        }
    }

    /* ---------------------------- 辅助 ---------------------------- */

    private fun ApplicationCall.allowed(): Boolean {
        val enc = store.current.settings.pinEnc
        if (enc.isBlank()) return true
        val given = request.queryParameters["pin"] ?: request.headers["X-Pin"]
        return !given.isNullOrBlank() && given == store.decryptPassword(enc)
    }

    private suspend fun ApplicationCall.guard(): Boolean {
        if (allowed()) return true
        respondText("""{"error":"需要访问密码"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        return false
    }

    private suspend fun ApplicationCall.json(payload: String, status: HttpStatusCode = HttpStatusCode.OK) {
        respondText(payload, ContentType.Application.Json, status)
    }

    private fun configHtml(): String = runCatching {
        context.assets.open("config/index.html").bufferedReader().use { it.readText() }
    }.getOrElse {
        "<html><body style='background:#121212;color:#fff;font-family:sans-serif;padding:24px'>" +
            "<h3>配置页资源缺失</h3><p>请确认 app/src/main/assets/config/index.html 存在。</p></body></html>"
    }
}
