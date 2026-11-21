package com.shiguang.album.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.ConnectionState
import com.shiguang.album.data.model.FileRef
import com.shiguang.album.data.model.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** 首页一张卡片需要的数据 */
data class PlaylistCardUi(
    val playlist: Playlist,
    val coverFile: File? = null,
    val count: Int? = null,
    /** 统计进行中（用于区分「还没数」和「数出来是 0」） */
    val counting: Boolean = false
)

/**
 * 首页状态机：
 * - 读取清单列表
 * - 封面：先查本地缩略图缓存（命中就秒出），没有再下载 + 生成缩略图
 * - 文件数量：走 [com.shiguang.album.data.store.PlaylistStatStore] 落盘缓存，
 *   同一个清单只在「文件夹变了」或「缓存超过一天」时才重新数，来回切页面一律直接出数
 * - 后台每 30 秒巡检一次存储源连接状态，喂给底部状态栏
 */
class HomeState(
    private val scope: CoroutineScope,
    private val container: AppContainer
) {

    var cards by mutableStateOf<List<PlaylistCardUi>>(emptyList())
        private set

    var loading by mutableStateOf(true)
        private set

    var statuses by mutableStateOf<Map<String, ConnectionState>>(emptyMap())
        private set

    var serverUrl by mutableStateOf("")
        private set

    private val coverMemo = mutableMapOf<String, File>()
    /** 记住每个清单当前封面指向的文件，换了封面要重新生成缩略图 */
    private val coverKeyMemo = mutableMapOf<String, String>()
    private var refreshJob: Job? = null
    private var statusJob: Job? = null

    private val stats get() = container.statStore

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(150)   // 防抖：连续改配置时只跑最后一次
            val playlists = container.configStore.current.playlists.sortedBy { it.sortIndex }

            // 第一帧：全部用缓存，一个网络请求都不发。有缓存时首页是"瞬间完整"的。
            cards = playlists.map { pl ->
                PlaylistCardUi(
                    playlist = pl,
                    coverFile = coverMemo[pl.id],
                    count = stats.cachedCount(pl)
                )
            }
            loading = false
            serverUrl = if (container.server.isRunning) container.server.configUrl() else ""
            startStatusLoop()

            playlists.forEach { pl ->
                // 1) 封面
                val current = cards.firstOrNull { it.playlist.id == pl.id }
                if (current?.coverFile == null) {
                    val entry = runCatching { container.repository.coverOf(pl) }.getOrNull()
                    if (entry != null) {
                        val file = runCatching { container.mediaCache.cover(entry) }.getOrNull()
                        if (file != null) {
                            coverMemo[pl.id] = file
                            coverKeyMemo[pl.id] = entry.key
                            updateCard(pl.id) { it.copy(coverFile = file) }
                        }
                        // 顺手把「首图」记进配置：手机配置页的清单列表要拿它当缩略图，
                        // 而手机页没有能力自己去 WebDAV/SMB 里翻第一张图。
                        // 只写一次，之后 autoCover 就固定下来，不会每次启动都落盘。
                        if (pl.autoCover == null) {
                            val ref = FileRef(entry.sourceId, entry.path)
                            runCatching {
                                container.configStore.update { st ->
                                    st.copy(
                                        playlists = st.playlists.map {
                                            if (it.id == pl.id && it.autoCover == null) {
                                                it.copy(autoCover = ref)
                                            } else it
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 2) 文件数量
                val cached = stats.cachedCount(pl)
                if (cached == null) {
                    updateCard(pl.id) { it.copy(count = null, counting = true) }
                    val count = countOf(pl)
                    stats.put(pl, count)
                    updateCard(pl.id) { it.copy(count = count, counting = false) }
                } else if (cached == 0 && pl.folders.isNotEmpty()) {
                    // 缓存里是 0 但清单明明有文件夹：十有八九上次是 NAS 断线数的 0。
                    // 0 是最不可信的值，先显示「统计中…」然后后台复核一遍。
                    updateCard(pl.id) { it.copy(count = null, counting = true) }
                    delay(800)
                    val count = countOf(pl)
                    stats.put(pl, count)
                    updateCard(pl.id) { it.copy(count = count, counting = false) }
                } else if (stats.isStale(pl)) {
                    // 有缓存但过期：先显示旧值，安静地在后台重数一遍，不阻塞任何界面
                    delay(2500)
                    val count = countOf(pl)
                    stats.put(pl, count)
                    if (count != cached) updateCard(pl.id) { it.copy(count = count) }
                }
                delay(40)
            }
        }
    }

    /** 手动重算某个清单（首页长按菜单「重新统计」） */
    fun recount(playlistId: String) {
        val pl = container.configStore.playlist(playlistId) ?: return
        stats.invalidate(playlistId)
        updateCard(playlistId) { it.copy(count = null, counting = true) }
        scope.launch {
            val count = countOf(pl)
            stats.put(pl, count)
            updateCard(playlistId) { it.copy(count = count, counting = false) }
        }
    }

    /**
     * 数文件数，0 的时候再试一次。
     * NAS 偶发 connect timeout 会让整趟遍历直接拿到 0，用户明明有一屋子照片却显示"空清单"。
     */
    private suspend fun countOf(pl: Playlist): Int {
        var count = runCatching { container.repository.countOf(pl) }.getOrDefault(0)
        if (count == 0 && pl.folders.isNotEmpty()) {
            delay(1800)
            count = runCatching { container.repository.countOf(pl) }.getOrDefault(0)
        }
        return count
    }

    private fun updateCard(id: String, block: (PlaylistCardUi) -> PlaylistCardUi) {
        cards = cards.map { if (it.playlist.id == id) block(it) else it }
    }

    /** 后台巡检：启动时先测一次所有源，之后每 30 秒刷新一次底部状态栏 */
    private fun startStatusLoop() {
        if (statusJob != null) return
        statusJob = scope.launch {
            while (isActive) {
                runCatching { container.repository.testAll() }
                statuses = container.repository.status.value
                delay(30_000)
            }
        }
    }

    fun testSource(id: String, onResult: (String) -> Unit) {
        scope.launch {
            val r = container.repository.testSource(id)
            statuses = container.repository.status.value
            onResult(r.message)
        }
    }

    fun stop() {
        refreshJob?.cancel()
        statusJob?.cancel()
        statusJob = null
    }

    fun invalidate(playlistId: String) {
        coverMemo.remove(playlistId)
        coverKeyMemo.remove(playlistId)
        stats.remove(playlistId)
    }
}
