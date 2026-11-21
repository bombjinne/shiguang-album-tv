package com.shiguang.album.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.source.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 媒体本地缓存。
 *
 * - 本地源：直接用原文件，不复制。
 * - 远程源（WebDAV / SMB）：首次访问下载到 cacheDir/media，之后直接命中本地文件。
 *   这样 Coil 解码大图、ExoPlayer 播放视频都不用关心协议差异，也顺带满足「远程缩略图本地缓存」。
 * - 封面额外落一层 covers/：原图动辄几 MB、分辨率 8000px，每次进首页都重新下载 + 全尺寸解码，
 *   电视上就是「转圈半天然后黑一下」。这里解码成 720px 的 JPEG 存下来，之后首页瞬间出图。
 *
 * 缓存键 = SHA1(源ID + 文件路径)，跟文件真实扩展名一起组成文件名。
 */
class MediaCache(
    private val context: Context,
    private val repo: MediaRepository
) {

    private val root = File(context.cacheDir, "media").apply { if (!exists()) mkdirs() }
    private val coverRoot = File(context.cacheDir, "covers").apply { if (!exists()) mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun localFileOf(entry: MediaEntry): File? {
        val source = repo.sourceOf(entry.sourceId) ?: return null
        if (source.type != SourceType.LOCAL) return null
        val f = File(entry.path)
        return if (f.isFile) f else null
    }

    fun cachedFileOf(entry: MediaEntry): File? {
        localFileOf(entry)?.let { return it }
        val f = fileFor(entry)
        return if (f.isFile && f.length() > 0) f else null
    }

    /**
     * 确保文件在本地可用。[onProgress] 回调 0f..1f（总大小未知时回调 -1）。
     */
    suspend fun file(entry: MediaEntry, onProgress: ((Float) -> Unit)? = null): File {
        localFileOf(entry)?.let { return it }

        val target = fileFor(entry)
        if (target.isFile && target.length() > 0) return target

        val lock = locks.getOrPut(entry.key) { Mutex() }
        return lock.withLock {
            if (target.isFile && target.length() > 0) return@withLock target

            withContext(Dispatchers.IO) {
                val part = File(target.absolutePath + ".part")
                runCatching { if (part.exists()) part.delete() }
                repo.openStream(entry).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var written = 0L
                        val total = entry.size
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            written += n
                            onProgress?.invoke(if (total > 0) (written.toFloat() / total) else -1f)
                        }
                        output.flush()
                    }
                }
                if (part.length() <= 0) {
                    part.delete()
                    throw IllegalStateException("下载失败：${entry.name}")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                target
            }
        }
    }

    /** 小文件直接读成字节（HTTP 配置页预览用） */
    suspend fun bytes(entry: MediaEntry, maxBytes: Long = 24L * 1024 * 1024): ByteArray? {
        val f = runCatching { file(entry) }.getOrNull() ?: return null
        if (f.length() > maxBytes) return null
        return withContext(Dispatchers.IO) { runCatching { f.readBytes() }.getOrNull() }
    }

    /* ---------------------------- 封面缓存 ---------------------------- */

    /** 已经生成好的封面缩略图（同步查，用来做首帧直出） */
    fun cachedCover(entry: MediaEntry, maxPx: Int = COVER_PX): File? =
        coverTarget(entry, maxPx).takeIf { it.isFile && it.length() > 0 }

    /**
     * 封面缩略图：原文件（远程先下载）→ 按 [maxPx] 采样解码 → 存成 JPEG。
     * 同一张封面只会算一次，之后首页直接读小图，不再下载、不再解大图。
     */
    suspend fun cover(entry: MediaEntry, maxPx: Int = COVER_PX): File? {
        val target = coverTarget(entry, maxPx)
        if (target.isFile && target.length() > 0) return target

        val lock = locks.getOrPut("cover|${entry.key}|$maxPx") { Mutex() }
        return lock.withLock {
            if (target.isFile && target.length() > 0) return@withLock target
            withContext(Dispatchers.IO) {
                val source = runCatching { file(entry) }.getOrNull() ?: return@withContext null
                val bitmap = runCatching { decodeSampled(source, maxPx) }.getOrNull()
                    ?: return@withContext null
                val tmp = File(target.absolutePath + ".part")
                val ok = runCatching {
                    tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                }.isSuccess
                runCatching { bitmap.recycle() }
                if (!ok || tmp.length() <= 0) {
                    tmp.delete()
                    return@withContext null
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target
            }
        }
    }

    /** 预览/封面点「清除」或换图后，把旧缩略图丢掉 */
    fun dropCover(entry: MediaEntry, maxPx: Int = COVER_PX) {
        runCatching { coverTarget(entry, maxPx).delete() }
    }

    fun clearCovers() {
        runCatching { coverRoot.listFiles()?.forEach { it.delete() } }
    }

    private fun coverTarget(entry: MediaEntry, maxPx: Int): File =
        File(coverRoot, sha1("${entry.key}@$maxPx") + ".jpg")

    /** 按 inSampleSize 采样解码，避免 8000px 的原图直接进内存 */
    private fun decodeSampled(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun fileFor(entry: MediaEntry): File {
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        val suffix = if (ext.isBlank()) "" else ".$ext"
        return File(root, sha1(entry.key) + suffix)
    }

    /** 按修改时间淘汰旧缓存，默认上限 1GB */
    suspend fun trim(maxBytes: Long = 1024L * 1024 * 1024) = withContext(Dispatchers.IO) {
        val files = root.listFiles()?.filter { it.isFile } ?: return@withContext
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@withContext
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= maxBytes) return@forEach
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    fun clear() {
        runCatching { root.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** 封面缩略图长边像素：电视卡片最大约 400dp，720 已足够清晰 */
        const val COVER_PX = 720
    }
}
