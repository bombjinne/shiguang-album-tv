package com.shiguang.album.server

import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.model.StorageSource
import kotlinx.serialization.Serializable

/**
 * 手机配置页使用的传输对象。
 * 关键点：StorageSource 里的 passwordEnc 绝不下发，只给一个 hasPassword 布尔值。
 */
@Serializable
data class SourceDto(
    val id: String = "",
    val name: String = "",
    val type: String = "LOCAL",
    val localPaths: List<String> = emptyList(),
    val host: String = "",
    val port: Int = 0,
    val shareName: String = "",
    val username: String = "",
    /** 仅上行：留空表示「不改密码」 */
    val password: String = "",
    val hasPassword: Boolean = false,
    val secure: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
data class StateDto(
    val revision: Long = 0,
    val url: String = "",
    val pinRequired: Boolean = false,
    val version: String = "1.0.0",
    val sources: List<SourceDto> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** 外观设置，手机端「外观」页据此回填当前值 */
    val appearance: AppearanceStateDto = AppearanceStateDto(),
    /** 在线音乐聚合服务地址 */
    val musicServerUrl: String = ""
)

/** 外观快照（下行） */
@Serializable
data class AppearanceStateDto(
    val themeMode: String = "DARK",
    val accentColor: Long = 0xFFFF6900,
    val showModeBadge: Boolean = true,
    val wallpaperKind: String = "GRADIENT",
    val wallpaperPreset: Int = 0,
    val wallpaperDim: Float = 0.45f,
    val wallpaperBlur: Boolean = false,
    val wallpaperHasImage: Boolean = false,
    val wallpaperImageName: String = "",
    /** 图片壁纸的来源，手机端用来拼 /api/thumb 预览整张图 */
    val wallpaperSourceId: String = "",
    val wallpaperPath: String = "",
    /** 视频壁纸：段数与轮播开关 */
    val wallpaperVideoCount: Int = 0,
    val wallpaperVideoLoop: Boolean = true
)

/** 外观修改（上行，字段留空/负数 = 不改） */
@Serializable
data class AppearanceDto(
    val themeMode: String = "",
    val accentColor: Long = 0L,
    val showModeBadge: Boolean? = null,
    val wallpaperKind: String = "",
    val wallpaperPreset: Int = -1,
    val wallpaperDim: Float = -1f,
    val wallpaperBlur: Boolean? = null,
    /** 指定图片来源（存储源 + 路径），为空表示不改图片 */
    val wallpaperSourceId: String = "",
    val wallpaperPath: String = "",
    /** 显式清掉图片壁纸 */
    val clearWallpaperImage: Boolean = false,
    /** 视频壁纸是否轮播；null = 不改 */
    val wallpaperVideoLoop: Boolean? = null
)

/* ---------------------------- 视频壁纸 ---------------------------- */

@Serializable
data class VideoClipDto(
    val name: String = "",
    val title: String = "",
    val size: Long = 0,
    val uploaded: Boolean = false
)

@Serializable
data class VideoListDto(
    val clips: List<VideoClipDto> = emptyList(),
    val total: Long = 0,
    /** 当前背景是不是视频（手机页决定高亮哪个 tab） */
    val active: Boolean = false
)

@Serializable
data class VideoNameDto(val name: String = "")

@Serializable
data class VideoReorderDto(val names: List<String> = emptyList())

/** 从存储源追加一段视频 */
@Serializable
data class VideoSourceDto(val sourceId: String = "", val path: String = "")

/** 本机文件系统浏览（手机端选目录用） */
@Serializable
data class FsDirDto(val name: String, val path: String)

@Serializable
data class FsDto(
    val path: String = "",
    val parent: String = "",
    /** 上一层是否可回退 */
    val canUp: Boolean = false,
    val exists: Boolean = false,
    val readable: Boolean = false,
    val dirs: List<FsDirDto> = emptyList(),
    /** 快捷入口：系统相册、视频、U 盘等常见位置 */
    val shortcuts: List<FsDirDto> = emptyList(),
    val error: String = ""
)


@Serializable
data class BrowseEntryDto(
    val name: String,
    val path: String,
    val dir: Boolean,
    val kind: String
)

@Serializable
data class BrowseDto(
    val path: String = "",
    val entries: List<BrowseEntryDto> = emptyList()
)

@Serializable
data class TestDto(val ok: Boolean, val state: String, val message: String)

@Serializable
data class ReorderDto(val ids: List<String> = emptyList())

@Serializable
data class SettingsDto(
    /** 空字符串 = 不修改密码 */
    val pin: String = "",
    /** 显式清除密码 */
    val clearPin: Boolean = false,
    /** 0 = 不修改端口 */
    val port: Int = 0,
    /** null = 不修改；空字符串 = 清空在线音乐服务 */
    val musicServerUrl: String? = null
)

@Serializable
data class MsgDto(val ok: Boolean = true, val message: String = "")

/* ------------------------------ 映射 ------------------------------ */

fun StorageSource.toDto(): SourceDto = SourceDto(    id = id,
    name = name,
    type = type.name,
    localPaths = localPaths,
    host = host,
    port = port,
    shareName = shareName,
    username = username,
    password = "",
    hasPassword = passwordEnc.isNotBlank(),
    secure = secure,
    enabled = enabled
)

fun SourceDto.toModel(existing: StorageSource?, encrypt: (String) -> String): StorageSource {
    val passwordEnc = when {
        password.isNotBlank() -> encrypt(password)
        existing != null -> existing.passwordEnc
        else -> ""
    }
    return StorageSource(
        id = existing?.id ?: id.ifBlank { java.util.UUID.randomUUID().toString() },
        name = name.ifBlank { host.ifBlank { "未命名源" } },
        type = runCatching { SourceType.valueOf(type) }.getOrDefault(SourceType.LOCAL),
        localPaths = localPaths.filter { it.isNotBlank() }.distinct(),
        host = host.trim(),
        port = port,
        shareName = shareName.trim(),
        username = username.trim(),
        passwordEnc = passwordEnc,
        secure = secure,
        enabled = enabled
    )
}

/* ---------------------------- 外观映射 ---------------------------- */

fun com.shiguang.album.data.model.AppSettings.toAppearanceDto(): AppearanceStateDto =
    AppearanceStateDto(
        themeMode = themeMode.name,
        accentColor = accentColor,
        showModeBadge = showModeBadge,
        wallpaperKind = wallpaper.kind.name,
        wallpaperPreset = wallpaper.preset,
        wallpaperDim = wallpaper.dim,
        wallpaperBlur = wallpaper.blur,
        wallpaperHasImage = wallpaper.file?.path?.isNotBlank() == true,
        wallpaperImageName = wallpaper.file?.path?.substringAfterLast('/').orEmpty(),
        wallpaperSourceId = wallpaper.file?.sourceId.orEmpty(),
        wallpaperPath = wallpaper.file?.path.orEmpty(),
        wallpaperVideoLoop = wallpaper.videoLoop
    )
