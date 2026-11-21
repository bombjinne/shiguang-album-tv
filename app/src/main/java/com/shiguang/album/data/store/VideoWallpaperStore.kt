package com.shiguang.album.data.store

import android.content.Context
import com.shiguang.album.data.cache.MediaCache
import com.shiguang.album.data.model.FileRef
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 视频壁纸的本地仓库（多段，支持轮播与手机上传）。
 *
 * 为什么不让 ExoPlayer 直接播存储源：
 * - SMB 走 jcifs，没有可用的随机访问 URI；
 * - WebDAV 要带 Basic Auth 头，得自己搭 DataSource；
 * - 更关键的是**首页每次启动都要立刻出画面**，网络抖一下就是一片黑。
 *
 * 所以视频一律先落到 filesDir（不受 cacheDir 清理影响），之后每次播放都是本地文件。
 *
 * 清单存在 `index.txt`（一行一段，Tab 分隔），顺序即播放顺序：
 * ```
 * <文件名>\t<1=手机上传 0=存储源>\t<sourceId>\t<path>\t<标题>
 * ```
 * 用文本而不是 JSON：手写、排查、降级都方便，而且不会因为某一行坏了整份清单报废。
 */
class VideoWallpaperStore(
    private val context: Context,
    private val cache: MediaCache
) {

    /** 一段视频壁纸 */
    data class Clip(
        val file: File,
        val title: String,
        /** 存储源来源（手机上传的为空） */
        val sourceId: String = "",
        val path: String = "",
        val uploaded: Boolean = false
    ) {
        val sizeBytes: Long get() = file.length()
    }

    private val dir: File = File(context.filesDir, "wallpaper-video")
    private val index: File = File(dir, "index.txt")

    /* ------------------------------ 读取 ------------------------------ */

    /** 按播放顺序返回可用的片段（文件丢失的条目自动跳过，避免播到一半黑屏） */
    fun clips(): List<Clip> {
        ensureDir()
        val rows = readIndex()
        val valid = rows.mapNotNull { row ->
            val f = File(dir, row.fileName)
            if (!f.isFile || f.length() <= 0L) null else Clip(
                file = f,
                title = row.title.ifBlank { row.fileName },
                sourceId = row.sourceId,
                path = row.path,
                uploaded = row.uploaded
            )
        }
        // 顺手清理孤儿文件（只删我们命名的，避免误伤）
        runCatching {
            val keep = valid.map { it.file.name }.toSet()
            dir.listFiles { f -> f.isFile && f.name.startsWith("bg-") && f.name !in keep }
                ?.forEach { it.delete() }
        }
        return valid
    }

    fun playableFiles(): List<File> = clips().map { it.file }

    fun totalBytes(): Long = clips().sumOf { it.sizeBytes }

    fun isEmpty(): Boolean = clips().isEmpty()

    /* ------------------------------ 写入 ------------------------------ */

    /**
     * 从存储源追回一段视频（电视端「添加视频」/ 手机端从存储源选）。
     *
     * @return 新片段；同一个来源已经存在时不重复下载，直接返回已有片段
     */
    suspend fun addSource(ref: FileRef, onProgress: ((Float) -> Unit)? = null): Clip? =
        withContext(Dispatchers.IO) {
            val rows = readIndex()
            val dup = rows.firstOrNull { !it.uploaded && it.sourceId == ref.sourceId && it.path == ref.path }
            if (dup != null) {
                val f = File(dir, dup.fileName)
                if (f.isFile) {
                    onProgress?.invoke(1f)
                    return@withContext Clip(f, dup.title.ifBlank { f.name }, ref.sourceId, ref.path, false)
                }
            }

            val displayName = ref.path.substringAfterLast('/').ifBlank { "video.mp4" }
            val entry = MediaEntry(
                sourceId = ref.sourceId,
                sourceName = "",
                path = ref.path,
                name = displayName,
                kind = MediaKind.VIDEO
            )

            val local = runCatching { cache.file(entry) { p -> onProgress?.invoke(p) } }.getOrNull()
                ?: return@withContext null
            if (!local.isFile || local.length() == 0L) return@withContext null

            val target = nextFileFor(displayName)
            if (!copyInto(local, target)) return@withContext null

            appendRow(IndexRow(target.name, uploaded = false, ref.sourceId, ref.path, displayName))
            onProgress?.invoke(1f)
            Clip(target, displayName, ref.sourceId, ref.path, false)
        }

    /**
     * 手机扫码上传的视频（临时文件已落盘）。
     *
     * @param temp   ConfigServer 收到的临时文件
     * @param title  原始文件名（展示用）
     */
    fun addUpload(temp: File, title: String): Clip? {
        ensureDir()
        if (!temp.isFile || temp.length() <= 0L) return null
        val target = nextFileFor(title)
        if (!copyInto(temp, target)) return null
        appendRow(IndexRow(target.name, uploaded = true, "", "", title.ifBlank { target.name }))
        return Clip(target, title.ifBlank { target.name }, "", "", true)
    }

    /** 删除一段（按文件名或标题都行，手机端传的是文件名） */
    fun remove(nameOrTitle: String): Boolean {
        val rows = readIndex()
        val row = rows.firstOrNull { it.fileName == nameOrTitle || it.title == nameOrTitle } ?: return false
        File(dir, row.fileName).delete()
        writeIndex(rows.filter { it.fileName != row.fileName })
        return true
    }

    /** 重排（手机端上下移动）：[names] 是文件名，未列出的按原顺序接在后面 */
    fun reorder(names: List<String>) {
        val rows = readIndex()
        if (rows.isEmpty()) return
        val byName = rows.associateBy { it.fileName }
        val ordered = names.mapNotNull { byName[it] } + rows.filter { it.fileName !in names }
        writeIndex(ordered)
    }

    /** 电视端「换一段视频」：清空后只放这一段的语义，由调用方先 [clear] 再 [addSource] */
    fun clear() {
        ensureDir()
        dir.listFiles { f -> f.isFile && (f.name.startsWith("bg-") || f.name == index.name) }
            ?.forEach { it.delete() }
    }

    /* ------------------------------ 内部 ------------------------------ */

    private data class IndexRow(
        val fileName: String,
        val uploaded: Boolean,
        val sourceId: String,
        val path: String,
        val title: String
    )

    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    private fun readIndex(): List<IndexRow> {
        ensureDir()
        if (!index.isFile) return emptyList()
        return runCatching {
            index.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val cols = line.split('\t')
                if (cols.size < 2) return@mapNotNull null
                IndexRow(
                    fileName = cols[0],
                    uploaded = cols.getOrNull(1) == "1",
                    sourceId = cols.getOrNull(2).orEmpty(),
                    path = cols.getOrNull(3).orEmpty(),
                    title = cols.getOrNull(4).orEmpty()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(rows: List<IndexRow>) {
        ensureDir()
        val text = rows.joinToString("\n") {
            listOf(
                it.fileName,
                if (it.uploaded) "1" else "0",
                it.sourceId,
                it.path,
                it.title.replace('\n', ' ')
            ).joinToString("\t")
        }
        val part = File(dir, "index.txt.part")
        part.writeText(text)
        if (!part.renameTo(index)) {
            index.delete()
            part.renameTo(index)
        }
    }

    private fun appendRow(row: IndexRow) {
        val rows = readIndex().toMutableList()
        rows.removeAll { it.fileName == row.fileName }
        rows.add(row)
        writeIndex(rows)
    }

    /** 生成一个不冲突的文件名，保留扩展名（ExoPlayer 靠它猜容器格式） */
    private fun nextFileFor(displayName: String): File {
        ensureDir()
        val ext = displayName.substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
            .takeIf { it in VIDEO_EXT } ?: "mp4"
        var n = 1
        while (true) {
            val f = File(dir, String.format(Locale.ROOT, "bg-%03d.%s", n, ext))
            if (!f.exists()) return f
            n++
        }
    }

    /** 复制到目标（先写 .part 再改名，断电不会留下半个文件） */
    private fun copyInto(source: File, target: File): Boolean {
        val part = File(target.path + ".part")
        val ok = runCatching {
            source.inputStream().use { input ->
                part.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            }
            part.length() > 0L && part.renameTo(target)
        }.getOrDefault(false)
        if (!ok) part.delete()
        return ok
    }

    private companion object {
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "3gp")
    }
}
