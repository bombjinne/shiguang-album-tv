package com.shiguang.album.data.source

import com.shiguang.album.data.model.ConnectionResult
import com.shiguang.album.data.model.ConnectionState
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.StorageSource
import java.io.InputStream

/** 存储源统一抽象：本地 / WebDAV / SMB 都实现这一套，上层完全不关心协议差异 */
interface MediaSource {
    val source: StorageSource

    /** 测试连接：返回在线 / 离线 / 认证失败 */
    suspend fun test(): ConnectionResult

    /** 列目录：返回子目录 + 媒体文件（图片/视频/音频），其他文件忽略 */
    suspend fun list(path: String): List<MediaEntry>

    /** 打开文件流（调用方负责 close） */
    suspend fun openStream(entry: MediaEntry): InputStream

    /** 释放底层连接资源 */
    fun close()
}

class SourceException(message: String) : Exception(message)

/** 扩展名 → 媒体类型 */
object MediaTypes {

    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif", "avif", "jfif")
    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "ts", "m4v", "webm", "flv", "mpg", "mpeg", "3gp")
    private val AUDIO = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "wma", "ape", "amr")

    fun kindOf(name: String): MediaKind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE -> MediaKind.IMAGE
            ext in VIDEO -> MediaKind.VIDEO
            ext in AUDIO -> MediaKind.AUDIO
            else -> null
        }
    }

    fun isMedia(name: String): Boolean = kindOf(name) != null

    /**
     * 这张图在本机能不能真的解码出来。
     *
     * HEIC/HEIF 是 iPhone 的默认格式，Android 8.0（API 26）之前系统里根本没有 HEVC 静态图解码器，
     * BitmapFactory / Coil 都会直接失败 —— 表现就是「封面一片黑」、「相册播到它就跳」。
     * AVIF 更晚（API 31 才有）。这类文件在低版本机器上就从播放列表和封面临选里剔除，
     * 让用户看到的是"能放的都在"，而不是一堆黑屏。
     */
    fun isDecodableImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "heic" || ext == "heif" -> android.os.Build.VERSION.SDK_INT >= 28
            ext == "avif" -> android.os.Build.VERSION.SDK_INT >= 31
            else -> kindOf(name) == MediaKind.IMAGE
        }
    }

    /** 给界面用的一句话解释，null 表示没问题 */
    fun unsupportedReason(name: String): String? {
        if (kindOf(name) != MediaKind.IMAGE || isDecodableImage(name)) return null
        val ext = name.substringAfterLast('.', "").uppercase()
        return "这台电视解不了 $ext 图片（需要 Android 9 以上），播放时会自动跳过"
    }

    /** 用于 HTTP 响应 / 播放判断的简化 MIME */
    fun mimeOf(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "jfif" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "aac", "m4a" -> "audio/aac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}

/** 构造 MediaEntry 的小工具，统一 name/kind 的推导逻辑 */
internal fun entryOf(
    source: StorageSource,
    path: String,
    name: String,
    isDir: Boolean,
    size: Long = 0,
    modified: Long = 0
): MediaEntry = MediaEntry(
    sourceId = source.id,
    sourceName = source.name,
    path = path,
    name = name,
    isDirectory = isDir,
    kind = if (isDir) null else MediaTypes.kindOf(name),
    size = size,
    modified = modified
)

internal fun offline(message: String) = ConnectionResult(false, ConnectionState.OFFLINE, message)
internal fun online(message: String = "连接正常") = ConnectionResult(true, ConnectionState.ONLINE, message)
internal fun authFailed(message: String = "用户名或密码错误") =
    ConnectionResult(false, ConnectionState.AUTH_FAILED, message)
