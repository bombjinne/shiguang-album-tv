package com.shiguang.album.data.store

import android.content.Context
import com.shiguang.album.data.model.AppState
import com.shiguang.album.data.model.AppSettings
import com.shiguang.album.data.model.FolderRef
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.data.security.SecretCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 全局配置的唯一数据源（存储源 + 播放清单 + 设置）。
 *
 * 数据量很小（几 KB～几十 KB），直接用单文件 JSON 持久化：
 * 读写简单、无数据库迁移成本、手机配置页和电视端共享同一份内存状态。
 * 每次修改走 [update]，写盘用「临时文件 + rename」保证原子性，掉电不会写坏配置。
 */
class ConfigStore(private val context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    private val _state = MutableStateFlow(loadFromDisk())

    init {
        // 「电视本机」是内置源，任何版本升级、配置被清空、用户误删之后都会自动补回来。
        // 用户装完 App 就能直接建清单，不需要先"添加存储源"。
        // 注意：清单相反 —— 绝不自动创建，一个空清单也不给，全部交给用户。
        val st = _state.value
        val next = fixAccentAlpha(
            ensurePlaylistIds(dropLegacyPlaylists(ensureAerialDir(ensureBuiltinLocal(st))))
        )
        if (next !== st) {
            _state.value = next
            persist(next)
        }
    }

    /**
     * 把「6 位 RGB」的强调色补成不透明的 8 位 ARGB。
     *
     * 旧版手机配置页保存的是 `parseInt('F43F5E', 16)` = `0xF43F5E`，高 8 位为 0。
     * 电视端 `Color(0xF43F5E)` 读到 alpha=0，整块强调色是透明的 ——
     * 表现就是「设置里选中的那一格看不见字」。升级时一次性补齐，数据从此干净。
     */
    private fun fixAccentAlpha(st: AppState): AppState {
        val c = st.settings.accentColor
        if (c == 0L || (c ushr 24) and 0xFFL != 0L) return st
        return st.copy(settings = st.settings.copy(accentColor = c or 0xFF000000L))
    }

    /**
     * 给历史遗留的「空 id 清单」补一个新 id。
     *
     * 旧版手机配置页新建清单时提交的是 `id: ""`，落盘之后这条清单就永久占用了空串；
     * 之后再新建还会命中它、把它覆盖。升级时一次性补齐，之后就再也不会撞车。
     * 名字、文件夹、音乐全部原样保留，只换 id。
     */
    private fun ensurePlaylistIds(st: AppState): AppState {
        if (st.playlists.none { it.id.isBlank() }) return st
        return st.copy(
            playlists = st.playlists.map {
                if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it
            }
        )
    }

    /** 保证内置本地源永远在第一项 */
    private fun ensureBuiltinLocal(st: AppState): AppState =
        if (st.sources.any { it.id == BUILTIN_LOCAL_ID }) st
        else st.copy(sources = listOf(builtinLocalSource()) + st.sources, initialized = true)

    /**
     * 把 Aerial 桌面导出的视频目录并进「电视本机」源。
     *
     * Aerial（包名 app.orchard.tv）把用户上传的壁纸视频存在自己的私有目录
     * `/data/data/app.orchard.tv/files/screensaver-videos/<uuid>/video.mp4`，
     * 权限是 `drwx------ u0_a55`，我们是 u0_a54 —— 按 Android 规则**一个字节都读不到**，
     * 也没有任何公开接口可以拿。所以由桌面端一次性 root 导出到
     * `/sdcard/Movies/Aerial/<原文件名>.mp4`，这里只负责「目录存在就把它纳入本地源」。
     *
     * 目录不存在（没装 Aerial，或还没导出）时原样返回，不会污染配置。
     */
    private fun ensureAerialDir(st: AppState): AppState {
        val root = runCatching {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        }.getOrNull() ?: return st
        val dir = "$root/Movies/Aerial"
        if (!runCatching { File(dir).isDirectory }.getOrDefault(false)) return st
        val src = st.sources.firstOrNull { it.id == BUILTIN_LOCAL_ID } ?: return st
        if (dir in src.localPaths) return st
        return st.copy(sources = st.sources.map {
            if (it.id == BUILTIN_LOCAL_ID) it.copy(localPaths = it.localPaths + dir) else it
        })
    }

    /**
     * 清掉早期版本自动塞进去的空示例清单。
     *
     * 早期为了让首页不空，第一次启动会生成一个「我的相册」之类的空清单；
     * 现在约定清单完全由用户创建，所以升级时按「名字命中 + 一个文件夹都没有」精确删除，
     * 用户自己建的同名清单（已经有文件夹）不会被误删。只跑一次，之后由 migrated 记住。
     */
    private fun dropLegacyPlaylists(st: AppState): AppState {
        if (st.migrated) return st
        val legacyNames = setOf("我的相册", "默认清单", "示例清单", "我的播放清单", "全家福轮播")
        val kept = st.playlists.filterNot { it.folders.isEmpty() && it.name.trim() in legacyNames }
        return st.copy(playlists = kept, migrated = true)
    }

    /** 配置快照，供 Compose 直接订阅 */
    val state: StateFlow<AppState> = _state

    /** 版本号：每次改动 +1，WebSocket 用它判断是否需要推送刷新 */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    val current: AppState get() = _state.value

    /* ----------------------------- 读写 ----------------------------- */

    private fun loadFromDisk(): AppState = runCatching {
        if (file.exists()) json.decodeFromString<AppState>(file.readText()) else AppState()
    }.getOrElse { AppState() }

    suspend fun update(block: (AppState) -> AppState) {
        mutex.withLock {
            val next = block(_state.value)
            _state.value = next
            persist(next)
            _revision.value = _revision.value + 1
        }
    }

    private fun persist(state: AppState) {
        runCatching {
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(json.encodeToString(state))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败时兜底直接写（极端情况，比如跨分区）
                file.writeText(json.encodeToString(state))
                tmp.delete()
            }
        }
    }

    /* --------------------------- 密码工具 --------------------------- */

    fun encryptPassword(plain: String): String =
        if (plain.isBlank()) "" else SecretCrypto.encrypt(context, plain)

    fun decryptPassword(enc: String): String =
        if (enc.isBlank()) "" else SecretCrypto.decrypt(context, enc)

    /* --------------------------- 查询助手 --------------------------- */

    fun source(id: String): StorageSource? = _state.value.sources.firstOrNull { it.id == id }

    fun playlist(id: String): Playlist? = _state.value.playlists.firstOrNull { it.id == id }

    fun sourcesOf(ids: Collection<String>): List<StorageSource> =
        _state.value.sources.filter { it.id in ids }

    /* --------------------------- 存储源 --------------------------- */

    suspend fun upsertSource(source: StorageSource) = update { st ->
        val exists = st.sources.any { it.id == source.id }
        val list = if (exists) st.sources.map { if (it.id == source.id) source else it }
        else st.sources + source
        st.copy(sources = list)
    }

    suspend fun deleteSource(id: String) = update { st ->
        // 内置本地源不允许删除，否则用户又会回到"没有存储源"的死角
        if (id == BUILTIN_LOCAL_ID) return@update st
        // 删除存储源时，顺手把清单里引用它的文件夹摘掉，避免留下死引用
        st.copy(
            sources = st.sources.filterNot { it.id == id },
            playlists = st.playlists.map { p ->
                p.copy(folders = p.folders.filterNot { it.sourceId == id })
            }
        )
    }

    /* --------------------------- 播放清单 --------------------------- */

    suspend fun upsertPlaylist(playlist: Playlist) = update { st ->
        /*
         * 空 id 一律当成「新建」，必须换一个新 UUID 再去比对。
         *
         * 之前手机配置页新建清单提交的是 `id: ""`，服务端原样存了下来；于是第二次新建时
         * `any { it.id == "" }` 命中了那一条，走了「替换」分支 —— 表现就是"已有 3 个清单，
         * 再添加会覆盖之前的"。这里是最内层的兜底：只要 id 是空的，就一定分配新值，
         * 绝不拿空串去匹配已有清单。
         */
        val id = playlist.id.ifBlank { UUID.randomUUID().toString() }
        val item = playlist.copy(id = id)
        val exists = st.playlists.any { it.id == id }
        val list = if (exists) st.playlists.map { if (it.id == id) item else it }
        else st.playlists + item.copy(sortIndex = st.playlists.size)
        st.copy(playlists = list.sortedBy { it.sortIndex })
    }

    suspend fun deletePlaylist(id: String) = update { st ->
        st.copy(playlists = st.playlists.filterNot { it.id == id })
    }

    suspend fun duplicatePlaylist(id: String): Playlist? {
        var created: Playlist? = null
        update { st ->
            val src = st.playlists.firstOrNull { it.id == id } ?: return@update st
            val copy = src.copy(
                id = UUID.randomUUID().toString(),
                name = "${src.name} 副本",
                sortIndex = st.playlists.size
            )
            created = copy
            st.copy(playlists = st.playlists + copy)
        }
        return created
    }

    /** 手机端拖拽排序后回传顺序 */
    suspend fun reorderPlaylists(idsInOrder: List<String>) = update { st ->
        val map = st.playlists.associateBy { it.id }
        val ordered = idsInOrder.mapNotNull { map[it] }.mapIndexed { i, p -> p.copy(sortIndex = i) }
        val rest = st.playlists.filterNot { it.id in idsInOrder }
            .mapIndexed { i, p -> p.copy(sortIndex = ordered.size + i) }
        st.copy(playlists = ordered + rest)
    }

    /** 清单快速改名（电视端长按菜单用） */
    suspend fun renamePlaylist(id: String, name: String) = update { st ->
        st.copy(playlists = st.playlists.map { if (it.id == id) it.copy(name = name) else it })
    }

    /* ---------------------------- 设置 ---------------------------- */

    suspend fun updateSettings(block: (AppSettings) -> AppSettings) = update { st ->
        st.copy(settings = block(st.settings))
    }

    companion object {
        /** 内置「电视本机」源的固定 id：不落盘也能识别，删不掉、重启自动补回 */
        const val BUILTIN_LOCAL_ID = "builtin-local"

        /** 生成一个「文件夹引用」的展示名 */
        fun folderLabel(ref: FolderRef): String =
            ref.path.substringAfterLast('/', ref.path).ifBlank { "/" }

        /**
         * 内置本地源：指向电视上真实存在的系统相册/视频目录。
         * 这样用户装完 App 直接就能建清单，不必先去"添加存储源"。
         */
        fun builtinLocalSource(): StorageSource {
            val root = runCatching {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            }.getOrNull() ?: "/storage/emulated/0"

            val preferred = listOf(
                "$root/DCIM", "$root/Pictures", "$root/Movies", "$root/Movies/Aerial",
                "$root/Download"
            )
            val existing = preferred.filter {
                runCatching { File(it).isDirectory }.getOrDefault(false)
            }
            return StorageSource(
                id = BUILTIN_LOCAL_ID,
                name = "电视本机",
                type = SourceType.LOCAL,
                // 一个都没探测到时也留一个占位，用户可在手机上改成实际目录
                localPaths = if (existing.isEmpty()) listOf("$root/Pictures") else existing
            )
        }
    }
}
