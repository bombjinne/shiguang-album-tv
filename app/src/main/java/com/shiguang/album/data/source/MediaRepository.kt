package com.shiguang.album.data.source

import com.shiguang.album.data.model.ConnectionResult
import com.shiguang.album.data.model.ConnectionState
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.data.store.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 媒体仓库：把「播放清单」翻译成「一串真实文件」，并负责：
 * - 目录递归展开（可选包含子文件夹）
 * - 单个目录失败时跳过，不影响整场播放（稳定性要求）
 * - 连接状态缓存（首页底部状态栏 / 手机配置页展示）
 */
class MediaRepository(
    private val store: ConfigStore,
    private val factory: MediaSourceFactory
) {

    private val _status = MutableStateFlow<Map<String, ConnectionState>>(emptyMap())
    val status: StateFlow<Map<String, ConnectionState>> = _status

    private val _latency = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusDetail: StateFlow<Map<String, String>> = _latency

    fun sourceOf(id: String): StorageSource? = store.source(id)

    /* --------------------------- 连接测试 --------------------------- */

    suspend fun testSource(id: String): ConnectionResult {
        val source = store.source(id) ?: return ConnectionResult(
            false, ConnectionState.OFFLINE, "存储源不存在"
        )
        val result = runCatching { factory.get(source).test() }
            .getOrElse { ConnectionResult(false, ConnectionState.OFFLINE, it.message ?: "测试失败") }
        setStatus(id, result)
        if (result.state == ConnectionState.AUTH_FAILED) factory.invalidate(id)
        return result
    }

    private fun setStatus(id: String, result: ConnectionResult) {
        _status.value = _status.value + (id to result.state)
        _latency.value = _latency.value + (id to result.message)
    }

    suspend fun testAll() {
        store.current.sources.filter { it.enabled }.forEach { testSource(it.id) }
    }

    /* --------------------------- 目录浏览 --------------------------- */

    /** 手机配置页的文件夹树 / 文件选择都走这里 */
    suspend fun list(sourceId: String, path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        val source = store.source(sourceId) ?: return@withContext emptyList()
        try {
            factory.get(source).list(path)
        } catch (first: Throwable) {
            // 断线重连：丢掉旧连接重试一次
            factory.invalidate(sourceId)
            try {
                factory.get(source).list(path)
            } catch (second: Throwable) {
                setStatus(sourceId, ConnectionResult(false, ConnectionState.OFFLINE, second.message ?: "列目录失败"))
                throw second
            }
        }
    }

    /* --------------------------- 清单解析 --------------------------- */

    /**
     * 把一个播放清单解析为待播放文件列表。
     * 顺序规则在这里生效：SEQUENCE 按目录顺序、SHUFFLE 打散、LOOP 交给播放器单张循环。
     */
    suspend fun resolve(playlist: Playlist): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        resolveBatches(playlist, Int.MAX_VALUE) { out.addAll(it) }
        return out
    }

    /**
     * 分批解析：每攒够 [batchSize] 条就回调一次，最后一定会有一次收尾回调。
     *
     * 7000 张照片的 NAS 目录，一次性列完要几十秒 —— 用户按下播放会盯着黑屏等。
     * 改成第一批（几十张）到手就能出画面，剩下的边放边补。
     */
    suspend fun resolveBatches(
        playlist: Playlist,
        batchSize: Int = 24,
        onBatch: suspend (List<MediaEntry>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val seen = LinkedHashSet<String>()
        val buffer = ArrayList<MediaEntry>(if (batchSize < 0) 0 else minOf(batchSize, 4096))
        val shuffle = playlist.order == com.shiguang.album.data.model.PlayOrder.SHUFFLE

        suspend fun flush() {
            if (buffer.isEmpty()) return
            val batch = if (shuffle) buffer.shuffled() else buffer.toList()
            buffer.clear()
            onBatch(batch)
        }

        for (ref in playlist.folders) {
            val source = store.source(ref.sourceId) ?: continue
            // 单个目录出错不至于让整个清单变空：断线重连后重试一次再放弃。
            // （NAS 偶发 connect timeout 时，第一批直接归零会让用户看到"空清单"）
            var ok = false
            for (attempt in 0..1) {
                ok = runCatching {
                    walkEach(source, ref.path, playlist.includeSubFolders, 0) { entry ->
                        if (entry.kind != MediaKind.IMAGE && entry.kind != MediaKind.VIDEO) return@walkEach
                        // 本机解不了的图片（低版本 Android 上的 HEIC 等）根本没画面，直接不入列
                        if (entry.kind == MediaKind.IMAGE && !MediaTypes.isDecodableImage(entry.name)) return@walkEach
                        if (!seen.add(entry.key)) return@walkEach
                        buffer.add(entry)
                        if (buffer.size >= batchSize) flush()
                    }
                }.isSuccess
                if (ok) break
                if (attempt == 0) {
                    factory.invalidate(ref.sourceId)
                    kotlinx.coroutines.delay(400)
                }
            }
        }
        flush()
    }

    /** 深度优先遍历目录，文件逐个交给 [onFile]（suspend，方便边遍历边回推批次） */
    private suspend fun walkEach(
        source: StorageSource,
        path: String,
        recursive: Boolean,
        depth: Int,
        onFile: suspend (MediaEntry) -> Unit
    ) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { factory.get(source).list(path) }.getOrElse {
            if (depth == 0) throw it else return
        }
        for (child in children) {
            if (child.isDirectory) {
                if (recursive) walkEach(source, child.path, true, depth + 1, onFile)
            } else {
                onFile(child)
            }
        }
    }

    /** 首页卡片上的文件数量 */
    suspend fun countOf(playlist: Playlist): Int = runCatching { resolve(playlist).size }.getOrDefault(0)

    /**
     * 封面：优先用手动指定的文件；否则取第一个文件夹里第一张图（没有图就取第一个视频，交给 Coil 抽帧）。
     *
     * 手动封面如果是本机解不了的格式（典型：iPhone 的 HEIC 在 Android 8 以下），
     * 展示出来就是一块黑，所以这里直接当作"没设封面"回落，宁可显示默认第一张图。
     */
    suspend fun coverOf(playlist: Playlist): MediaEntry? = withContext(Dispatchers.IO) {
        playlist.coverFile?.takeIf { it.path.isNotBlank() }?.let { ref ->
            val source = store.source(ref.sourceId) ?: return@withContext null
            val name = ref.path.substringAfterLast('/')
            val kind = MediaTypes.kindOf(ref.path) ?: MediaKind.IMAGE
            if (kind == MediaKind.IMAGE && !MediaTypes.isDecodableImage(name)) {
                // 落到下面的自动封面
            } else {
                return@withContext MediaEntry(
                    sourceId = source.id,
                    sourceName = source.name,
                    path = ref.path,
                    name = name,
                    kind = kind
                )
            }
        }

        val ref = playlist.folders.firstOrNull() ?: return@withContext null
        val source = store.source(ref.sourceId) ?: return@withContext null
        val children = runCatching { factory.get(source).list(ref.path) }.getOrNull()
            ?: return@withContext null
        val images = children.filter { !it.isDirectory && it.kind == MediaKind.IMAGE }

        images.firstOrNull { MediaTypes.isDecodableImage(it.name) }
            ?: children.firstOrNull { !it.isDirectory && it.kind == MediaKind.VIDEO }
    }

    /* --------------------------- 文件读取 --------------------------- */

    suspend fun openStream(entry: MediaEntry): InputStream = withContext(Dispatchers.IO) {
        val source = store.source(entry.sourceId) ?: throw SourceException("存储源已被删除：${entry.sourceName}")
        factory.get(source).openStream(entry)
    }

    fun release() = factory.closeAll()

    companion object {
        private const val MAX_DEPTH = 12
    }
}
