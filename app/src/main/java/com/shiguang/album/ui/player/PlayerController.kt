package com.shiguang.album.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.EffectType
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.PlayEndAction
import com.shiguang.album.data.model.PlayMode
import com.shiguang.album.data.model.PlayOrder
import com.shiguang.album.data.model.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 播放页的所有可见状态 */
data class PlayerUiState(
    val playlist: Playlist? = null,
    val entries: List<MediaEntry> = emptyList(),
    val index: Int = 0,
    val paused: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val currentEntry: MediaEntry? = null,
    /** 图片文件（已缓存到本地） */
    val currentFile: File? = null,
    /** 视频文件（已缓存到本地） */
    val videoFile: File? = null,
    /**
     * 下一张要显示的图片（已经落到本地缓存）。
     *
     * 播放页拿它提前解码成 Bitmap，真正切换时直接就位 —— 不然远程图要现场下载 + 解码，
     * 中间那段空白就是用户看到的"黑屏"。null 表示还没算出来（首帧、随机模式、或下一张是视频）。
     */
    val nextImageFile: File? = null,
    val isVideo: Boolean = false,
    val effect: EffectType = EffectType.FADE,
    val staySeconds: Int = 5,
    val playMode: PlayMode = PlayMode.AUTO,
    val order: PlayOrder = PlayOrder.SEQUENCE,
    /** 一遍播完之后怎么处理 */
    val endAction: PlayEndAction = PlayEndAction.LOOP,
    /** 已经自动播到末尾并按 [endAction] 处理过了（页面据此提示"已播完"） */
    val ended: Boolean = false,
    /** 每次切换 +1，用来驱动页码淡入 */
    val pageToken: Int = 0,
    /** 1 = 下一张，-1 = 上一张 */
    val direction: Int = 1,
    val musicOn: Boolean = false,
    val sourceLabel: String = "",
    /** 还在后台继续解析清单（第一屏已经能看了，剩下的在补） */
    val resolving: Boolean = false
) {
    val pageText: String
        get() = when {
            entries.isEmpty() -> ""
            resolving -> "${index + 1} / ${entries.size}+"
            else -> "${index + 1} / ${entries.size}"
        }
}

/**
 * 播放控制器：负责清单解析、逐张加载（含预加载下一张）、自动播放计时、背景音乐。
 *
 * 设计要点：
 * - 自动模式：每张停留 N 秒自动切换；手动模式：完全不自动切，只服遥控器
 * - 切换顺序：SEQUENCE 顺序到底回第一张 / SHUFFLE 随机 / LOOP 单张循环
 * - 单个文件加载失败自动跳过，不会卡死在坏文件上
 */
class PlayerController(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val playlistId: String
) {

    var ui by mutableStateOf(PlayerUiState())
        private set

    private val music = MusicController(context, scope)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var lastTick = System.currentTimeMillis()
    private var skipGuard = 0
    private var started = false

    /** 已经播到"目前加载的末尾"但清单还没解析完：等下一批到了自动前进 */
    private var pendingAdvance = false

    fun start() {
        if (started) return
        started = true
        loadJob = scope.launch {
            val pl = container.configStore.playlist(playlistId)
            if (pl == null) {
                ui = ui.copy(loading = false, error = "清单不存在或已被删除")
                return@launch
            }
            ui = ui.copy(
                playlist = pl,
                effect = pl.effect,
                staySeconds = pl.staySeconds,
                playMode = pl.playMode,
                order = pl.order,
                endAction = pl.endAction,
                loading = true,
                error = null,
                resolving = true
            )

            // 分批解析：第一批到手就开播，剩下的在后台接着列。
            // 7000 张照片的目录如果等全部列完，用户要盯着黑屏几十秒。
            var firstBatch = true
            container.repository.resolveBatches(pl, BATCH_SIZE) { batch ->
                withContext(Dispatchers.Main) {
                    if (firstBatch) {
                        firstBatch = false
                        ui = ui.copy(entries = batch, index = 0, loading = true)
                        show(0, 1)
                        startMusic(pl)
                        startTicker()
                    } else {
                        ui = ui.copy(entries = ui.entries + batch)
                        prefetchFrom(ui.index)
                        if (pendingAdvance) {
                            pendingAdvance = false
                            if (ui.index + 1 < ui.entries.size) next(false)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                ui = ui.copy(resolving = false)
                if (ui.entries.isEmpty()) {
                    ui = ui.copy(loading = false, error = "这个清单里没有找到照片或视频")
                }
            }
        }
    }

    /* ----------------------------- 加载 ----------------------------- */

    private fun show(index: Int, direction: Int) {
        val state = ui
        val entry = state.entries.getOrNull(index) ?: return
        ui = state.copy(
            index = index,
            direction = direction,
            currentEntry = entry,
            isVideo = entry.kind == MediaKind.VIDEO,
            currentFile = null,
            videoFile = null,
            // 切页先清掉上一张的预取结果，避免拿错文件；新的会在加载完成后补上
            nextImageFile = null,
            loading = true,
            // 换了画面就说明"播完了"这个状态已经翻篇
            ended = false,
            pageToken = state.pageToken + 1,
            sourceLabel = entry.sourceName
        )
        lastTick = System.currentTimeMillis()

        scope.launch {
            val file = runCatching { container.mediaCache.file(entry) }.getOrNull()
            if (ui.index != index) return@launch          // 用户已经切走，丢弃这次结果

            if (file == null) {
                skipBroken(index, direction)
                return@launch
            }

            ui = if (entry.kind == MediaKind.VIDEO) {
                ui.copy(videoFile = file, isVideo = true, loading = false)
            } else {
                ui.copy(currentFile = file, isVideo = false, loading = false)
            }
            skipGuard = 0
            prefetchFrom(index)
        }
    }

    /**
     * 预加载下一张。
     *
     * 这里只负责"把远程文件拉到本地缓存，并把 File 交给播放页"
     * （[PlayerUiState.nextImageFile]）。真正的解码由播放页在 IO 线程按屏幕尺寸做，
     * 所以这里不再顺手让 Coil 解一遍 —— 那个请求没指定 size，会解出整张原图，
     * 白白多占几十 MB，还和按屏解码的缓存 key 对不上。
     */
    private fun prefetchFrom(from: Int) {
        val state = ui
        if (state.entries.size < 2) return
        // 单张循环没有"下一张"；随机模式下一张不确定，预取大概率白费
        if (state.order == PlayOrder.LOOP || state.order == PlayOrder.SHUFFLE) return
        // 只往后看一张，且不绕回头部：清单还在陆续加载时不要瞎预取第 0 张
        val nextIndex = from + 1
        if (nextIndex >= state.entries.size) return
        val entry = state.entries.getOrNull(nextIndex) ?: return
        if (entry.kind == MediaKind.VIDEO) return          // 视频不做预下载，避免抢占带宽
        scope.launch {
            val file = runCatching { container.mediaCache.file(entry) }.getOrNull() ?: return@launch
            // 期间用户可能已经切走，别把旧结果塞回去
            if (ui.index == from && !ui.isVideo) ui = ui.copy(nextImageFile = file)
        }
    }

    private fun skipBroken(index: Int, direction: Int) {
        if (skipGuard > 5 || ui.entries.isEmpty()) {
            ui = ui.copy(error = "连续多个文件加载失败，请检查存储源连接", loading = false)
            return
        }
        skipGuard++
        val nextIndex = if (direction >= 0) (index + 1) % ui.entries.size else (index - 1 + ui.entries.size) % ui.entries.size
        if (nextIndex == index) {
            ui = ui.copy(error = "当前文件无法播放", loading = false)
            return
        }
        show(nextIndex, direction)
    }

    /* ----------------------------- 播放控制 ----------------------------- */

    fun next(userInitiated: Boolean = true) {
        val s = ui
        if (s.entries.isEmpty()) return
        if (userInitiated) skipGuard = 0
        if (s.order == PlayOrder.LOOP) {
            // 单张循环：重新载入当前这张，配合动效做「重播」
            show(s.index, 1)
            return
        }
        val nextIndex = s.index + 1
        if (nextIndex >= s.entries.size) {
            // 清单还在往后续：先记住"该往前走了"，等下一批到了自动接上
            if (s.resolving) {
                pendingAdvance = true
                return
            }
            // 手动按右键走到末尾：永远回到第一张。用户明确按了"下一张"，
            // 这里不能因为"播完停住"而毫无反应，那是遥控器失灵。
            if (userInitiated) {
                show(0, 1)
                return
            }
            when (s.endAction) {
                PlayEndAction.LOOP -> show(0, 1)
                // 停住：画面和音乐都保持，只是不再自动前进；OK 键可以重新从头放
                PlayEndAction.HOLD_LAST -> {
                    ui = s.copy(ended = true)
                    lastTick = System.currentTimeMillis()
                }
                // 交给界面层收尾（退出播放页），这里只把状态标出来
                PlayEndAction.BACK_HOME -> ui = s.copy(ended = true)
            }
            return
        }
        show(nextIndex, 1)
    }

    fun prev() {
        val s = ui
        if (s.entries.isEmpty()) return
        if (s.order == PlayOrder.LOOP) {
            show(s.index, -1)
            return
        }
        show((s.index - 1 + s.entries.size) % s.entries.size, -1)
    }

    fun togglePause() {
        val s = ui
        val pl = s.playlist ?: return
        // 播完之后按 OK = 从头再来一遍（停在最后一张时最自然的动作）
        if (s.ended) {
            ui = s.copy(ended = false, paused = false)
            show(0, 1)
            return
        }
        val nowPaused = !s.paused
        ui = s.copy(paused = nowPaused)
        lastTick = System.currentTimeMillis()
        if (nowPaused) music.pause(pl.music) else music.resume(pl.music)
    }

    /** 视频播完：自动模式继续，手动模式停在最后一帧 */
    fun onVideoEnded() {
        if (ui.playMode == PlayMode.AUTO) next(false) else ui = ui.copy(paused = true)
    }

    fun setEffect(effect: EffectType) {
        ui = ui.copy(effect = effect)
        persist { it.copy(effect = effect) }
    }

    fun cycleStay() {
        val options = listOf(3, 5, 8, 10, 15, 20, 30, 60)
        val current = ui.staySeconds
        val nextValue = options.firstOrNull { it > current } ?: options.first()
        ui = ui.copy(staySeconds = nextValue)
        lastTick = System.currentTimeMillis()
        persist { it.copy(staySeconds = nextValue) }
    }

    fun togglePlayMode() {
        val next = if (ui.playMode == PlayMode.AUTO) PlayMode.MANUAL else PlayMode.AUTO
        ui = ui.copy(playMode = next)
        lastTick = System.currentTimeMillis()
        persist { it.copy(playMode = next) }
    }

    fun setOrder(order: PlayOrder) {
        ui = ui.copy(order = order)
        persist { it.copy(order = order) }
    }

    fun setEndAction(action: PlayEndAction) {
        // 改了"播完怎么办"就等于重新开始计时：否则停在末尾时切回循环，还要等好几秒才动
        ui = ui.copy(endAction = action, ended = false)
        lastTick = System.currentTimeMillis()
        persist { it.copy(endAction = action) }
    }

    fun volumeUp() = adjustVolume(AudioManager.ADJUST_RAISE)

    fun volumeDown() = adjustVolume(AudioManager.ADJUST_LOWER)

    private fun adjustVolume(direction: Int) {
        runCatching {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun persist(block: (Playlist) -> Playlist) {
        val pl = ui.playlist ?: return
        scope.launch {
            val updated = block(pl)
            container.configStore.upsertPlaylist(updated)
            ui = ui.copy(playlist = updated)
        }
    }

    /* ----------------------------- 背景音乐 ----------------------------- */

    private data class ResolvedTrack(val file: File?, val url: String?)

    private var musicTracks: List<ResolvedTrack> = emptyList()
    private var musicIndex = 0

    private fun startMusic(pl: Playlist) {
        if (!pl.music.isPlayable) return
        scope.launch {
            // 逐首解析：直链直接用、在线歌曲现取流、文件走缓存下载
            val resolved = pl.music.allTracks.mapNotNull { track ->
                when {
                    track.url.isNotBlank() -> ResolvedTrack(null, track.url)
                    track.online != null -> runCatching { container.onlineMusic.streamUrl(track.online) }
                        .getOrNull()?.takeIf { it.isNotBlank() }?.let { ResolvedTrack(null, it) }
                    track.file != null -> {
                        val ref = track.file
                        val source = container.configStore.source(ref.sourceId) ?: return@mapNotNull null
                        val entry = MediaEntry(
                            sourceId = source.id,
                            sourceName = source.name,
                            path = ref.path,
                            name = ref.path.substringAfterLast('/'),
                            kind = MediaKind.AUDIO
                        )
                        runCatching { container.mediaCache.file(entry) }.getOrNull()
                            ?.let { ResolvedTrack(it, null) }
                    }
                    else -> null
                }
            }
            if (resolved.isEmpty()) return@launch
            musicTracks = resolved
            musicIndex = 0
            playMusicTrack(pl)
        }
    }

    private fun playMusicTrack(pl: Playlist) {
        val track = musicTracks.getOrNull(musicIndex) ?: return
        // 单首 + 循环交给 MediaPlayer 自己 loop（无缝）；多首则播完切下一首
        val multi = musicTracks.size > 1
        val callback: (() -> Unit)? =
            if (multi || pl.music.loop) ({ advanceMusic(pl) }) else null
        music.play(track.file, track.url, pl.music, callback)
        ui = ui.copy(musicOn = true)
    }

    private fun advanceMusic(pl: Playlist) {
        if (musicTracks.isEmpty()) return
        var next = musicIndex + 1
        if (next >= musicTracks.size) {
            if (!pl.music.loop) {
                ui = ui.copy(musicOn = false)
                return
            }
            next = 0
        }
        musicIndex = next
        playMusicTrack(pl)
    }

    /* ----------------------------- 计时器 ----------------------------- */

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(250)
                val s = ui
                val auto = s.playMode == PlayMode.AUTO
                if (s.paused || !auto || s.isVideo || s.loading || s.entries.isEmpty()) {
                    lastTick = System.currentTimeMillis()
                    continue
                }
                // 已经播到末尾并"停住/准备退出"：不要再反复触发 next()，否则 LOOP 一改就把画面拉回头
                if (s.ended) {
                    lastTick = System.currentTimeMillis()
                    continue
                }
                val elapsed = System.currentTimeMillis() - lastTick
                if (elapsed >= s.staySeconds * 1000L) {
                    lastTick = System.currentTimeMillis()
                    next(false)
                }
            }
        }
    }

    fun release() {
        loadJob?.cancel()
        tickerJob?.cancel()
        music.release()
    }

    private companion object {
        /** 每批解析多少张：24 张足够立刻出画面，又不会一次列太多拖慢首屏 */
        const val BATCH_SIZE = 24
    }
}
