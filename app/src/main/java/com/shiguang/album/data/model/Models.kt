package com.shiguang.album.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/* ============================ 存储源 ============================ */

@Serializable
enum class SourceType { LOCAL, WEBDAV, SMB }

/**
 * 一个存储源。密码永远以密文（passwordEnc）落盘，绝不明文出现在配置文件或手机配置页里。
 */
@Serializable
data class StorageSource(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: SourceType = SourceType.LOCAL,
    /** LOCAL：被纳管的根目录（可多选） */
    val localPaths: List<String> = emptyList(),
    /** WEBDAV / SMB：主机地址（不含协议），如 192.168.1.10 */
    val host: String = "",
    /** WEBDAV：端口，0 表示按 scheme 推断；SMB：一般 445 */
    val port: Int = 0,
    /** WEBDAV：基础路径，如 /dav/photos ；SMB：共享名，如 photos */
    val shareName: String = "",
    val username: String = "",
    val passwordEnc: String = "",
    /** WEBDAV 是否走 https */
    val secure: Boolean = false,
    val enabled: Boolean = true
)

/* ============================ 播放清单 ============================ */

/** 指向某个存储源里的一个文件夹（或文件） */
@Serializable
data class FolderRef(
    val sourceId: String = "",
    val path: String = ""
)

@Serializable
data class FileRef(
    val sourceId: String = "",
    val path: String = ""
)

@Serializable
enum class EffectType {
    FADE,      // 淡入淡出
    SLIDE_H,   // 左右滑动
    SLIDE_V,   // 上下滑动
    ZOOM,      // 缩放推进
    ROTATE,    // 旋转翻页
    MOSAIC,    // 马赛克 / 柔化溶解
    RANDOM     // 每次随机
}

@Serializable
enum class PlayOrder {
    SEQUENCE,  // 顺序播放，播完回到第一张
    SHUFFLE,   // 随机顺序
    LOOP       // 单张循环
}

@Serializable
enum class PlayMode { AUTO, MANUAL }

/**
 * 一整遍播完之后做什么。
 *
 * 之前是写死的「播完回到第一张」，睡前只想看一遍也停不下来。
 * 只在**自动前进**走到末尾时生效：手动按右键到末尾仍然回到第一张（遥控器不能失灵）。
 */
@Serializable
enum class PlayEndAction {
    /** 播完回到第一张接着放（默认，等于"一直放着"） */
    LOOP,
    /** 停在最后一张不再自动前进，音乐继续 */
    HOLD_LAST,
    /** 播完自动退出播放页回首页 */
    BACK_HOME
}

/**
 * 在线音乐条目：来自聚合音乐服务（go-music-dl / music-lib）的搜索结果。
 *
 * 只存"如何再取一次流"所需的最小信息，不存临时播放地址：
 * 播放地址一般带签名或有时效，存下来过期就播不了，每次播放现取。
 */
@Serializable
data class OnlineSong(
    /** 平台内的歌曲 ID */
    val id: String = "",
    /** 平台标识：netease / qq / kugou / kuwo / migu / bilibili ... */
    val source: String = "",
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    /** 时长（秒），未知为 0 */
    val durationSec: Int = 0,
    /** 封面地址（部分平台不提供，界面会退化成一个音符图标） */
    val cover: String = "",
    /** 是否需要会员才能拿到完整音频：界面会标一个 VIP，直连失败时自动改用适配服务 */
    val vip: Boolean = false,
    /** 部分平台的取流需要额外参数，原样透传服务端返回的 JSON 字符串 */
    val extraJson: String = ""
) {
    val displayName: String
        get() = if (artist.isBlank()) name else "$name - $artist"
}

/** 背景音乐里的一首：文件（本地/NAS）、直链、在线搜索三选一 */
@Serializable
data class MusicTrack(
    val file: FileRef? = null,
    /** http(s) 直链 */
    val url: String = "",
    /** 在线音乐服务搜到的歌曲 */
    val online: OnlineSong? = null
) {
    val isPlayable: Boolean
        get() = url.isNotBlank() || online != null || (file != null && file.path.isNotBlank())

    val title: String
        get() = when {
            online != null -> online.displayName
            url.isNotBlank() -> url.substringAfterLast('/').ifBlank { url }
            file != null -> file.path.substringAfterLast('/').ifBlank { file.path }
            else -> "（空）"
        }

    val kindLabel: String
        get() = when {
            online != null -> "在线"
            url.isNotBlank() -> "直链"
            else -> "文件"
        }
}

/**
 * 背景音乐配置：可以放一整个歌单，循环播放。
 *
 * [file] / [customUrl] / [online] 是 V1.3.x 及更早的单曲字段，保留只为兼容老配置和手机配置页；
 * 真正的播放列表是 [tracks]（非空时以它为准）。读取时统一走 [allTracks]。
 */
@Serializable
data class MusicConfig(
    val enabled: Boolean = false,
    /** 旧字段：单个音乐文件 */
    val file: FileRef? = null,
    /** 旧字段：单个直链 */
    val customUrl: String = "",
    /** 旧字段：单首在线歌曲 */
    val online: OnlineSong? = null,
    /** 音乐列表，按顺序循环播放 */
    val tracks: List<MusicTrack> = emptyList(),
    /** 0f..1f，独立于系统音量 */
    val volume: Float = 0.7f,
    /** 淡入淡出时长（毫秒） */
    val fadeMs: Int = 1500,
    val loop: Boolean = true
) {
    /** 实际要播的曲目：新列表优先，老配置折算成一首 */
    val allTracks: List<MusicTrack>
        get() {
            val list = tracks.filter { it.isPlayable }
            if (list.isNotEmpty()) return list
            val legacy = when {
                online != null -> MusicTrack(online = online)
                customUrl.isNotBlank() -> MusicTrack(url = customUrl)
                file != null && file.path.isNotBlank() -> MusicTrack(file = file)
                else -> null
            }
            return listOfNotNull(legacy)
        }

    val isPlayable: Boolean
        get() = enabled && allTracks.isNotEmpty()
}

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "未命名清单",
    /** 清单由多个文件夹组成 */
    val folders: List<FolderRef> = emptyList(),
    /** 封面：默认取第一个文件夹内第一张图；也可手动指定某个文件 */
    val coverFile: FileRef? = null,
    /**
     * 电视端算出来的「这个清单的第一张图」。
     *
     * 手机配置页的清单列表要显示缩略图，但手机页自己没法遍历 WebDAV/SMB 目录
     * （那要一整套认证 + 列表分页）。首页每次都会算封面，顺手把结果写回这里，
     * 手机页直接拿来当缩略图 —— 不用再发一轮请求。
     * 只在为空时写一次，之后固定下来。
     */
    val autoCover: FileRef? = null,
    val music: MusicConfig = MusicConfig(),
    val effect: EffectType = EffectType.FADE,
    val order: PlayOrder = PlayOrder.SEQUENCE,
    /** 一遍播完之后怎么处理（老配置没有这个字段，默认为循环，行为和以前一致） */
    val endAction: PlayEndAction = PlayEndAction.LOOP,
    /** 每张停留秒数 */
    val staySeconds: Int = 5,
    val includeSubFolders: Boolean = false,
    val playMode: PlayMode = PlayMode.AUTO,
    /** 首页排序 */
    val sortIndex: Int = 0,
    /** 切换时轻微音效反馈 */
    val soundFeedback: Boolean = false,
    /** 已解析到的文件数量缓存（仅内存，不落盘） */
    @Transient val cachedCount: Int? = null
)

/* ============================ 媒体条目 ============================ */

enum class MediaKind { IMAGE, VIDEO, AUDIO }

/** 一个真实的媒体文件（从哪里来、路径是什么、多大） */
data class MediaEntry(
    val sourceId: String,
    val sourceName: String,
    val path: String,
    val name: String,
    val isDirectory: Boolean = false,
    val kind: MediaKind? = null,
    val size: Long = 0L,
    val modified: Long = 0L
) {
    /** 唯一键：同一个文件跨源不冲突 */
    val key: String get() = "$sourceId|$path"
}

/* ============================ 连接状态 ============================ */

enum class ConnectionState { UNKNOWN, ONLINE, OFFLINE, AUTH_FAILED }

data class ConnectionResult(
    val ok: Boolean,
    val state: ConnectionState,
    val message: String
)

/* ============================ 外观 ============================ */

@Serializable
enum class ThemeMode {
    DARK,    // 深色（默认，电视暗环境友好）
    LIGHT,   // 浅色
    AUTO     // 跟随系统
}

@Serializable
enum class WallpaperKind {
    NONE,      // 纯色背景
    GRADIENT,  // 内置渐变预设
    IMAGE,     // 用户选的一张图
    VIDEO      // 用户选的一段视频（静音循环，参照 aerial-tv 的 VideoWallpaper）
}

/** 首页/页面背景壁纸 */
@Serializable
data class Wallpaper(
    val kind: WallpaperKind = WallpaperKind.GRADIENT,
    /** 渐变预设序号（0..5），见 ui/theme/Gradients.kt */
    val preset: Int = 0,
    /** 图片/视频壁纸：来自任一存储源的一个文件 */
    val file: FileRef? = null,
    /** 压暗程度 0f..0.85f，保证前景文字可读 */
    val dim: Float = 0.45f,
    /** 背景模糊（Android 12+ 生效，低版本自动忽略） */
    val blur: Boolean = false,
    /**
     * 视频壁纸遇到底层解码失败时自动降级成这张静帧（渐变预设序号），
     * 避免老盒子上出现"一块黑"看上去像 App 崩了。
     */
    val fallbackPreset: Int = 0,
    /**
     * 多段视频时是否轮播。
     * true（默认）= 播完最后一段回到第一段接着放；
     * false = 播完最后一段停在最后一帧（睡前看一遍用）。
     */
    val videoLoop: Boolean = true
) {
    val isImage: Boolean get() = kind == WallpaperKind.IMAGE && file != null && file.path.isNotBlank()

    val isVideo: Boolean get() = kind == WallpaperKind.VIDEO && file != null && file.path.isNotBlank()
}

/** 全局配置 */

@Serializable
data class AppSettings(
    /** 内嵌 HTTP 配置服务的端口 */
    val port: Int = 8080,
    /** 配置页可选访问密码（密文存储，空 = 不设密码） */
    val pinEnc: String = "",
    /** 主题明暗模式 */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** 主题强调色，默认小米橙；0 = 用默认 */
    val accentColor: Long = 0xFFFF6900,
    /** 首页壁纸 */
    val wallpaper: Wallpaper = Wallpaper(),
    /** 首页卡片是否显示"手动/自动"角标 */
    val showModeBadge: Boolean = true,
    /** 切换相片时是否播放轻微音效（全局开关） */
    val soundFeedback: Boolean = false,
    /**
     * 在线音乐聚合服务地址，例如 http://192.168.31.10:8080/music
     * 指向自建的 go-music-dl / music-lib 服务；留空表示只播本地与在线直链。
     */
    val musicServerUrl: String = ""
)

@Serializable
data class AppState(
    val sources: List<StorageSource> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val settings: AppSettings = AppSettings(),
    /** 是否已完成首次初始化（用于「默认带一个本地源」，避免用户删光后重启又冒出来） */
    val initialized: Boolean = false,
    /** 旧版本遗留数据是否已清理（清掉当年自动塞进去的空示例清单） */
    val migrated: Boolean = false
)
