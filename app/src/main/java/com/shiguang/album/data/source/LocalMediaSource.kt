package com.shiguang.album.data.source

import com.shiguang.album.data.model.ConnectionResult
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.StorageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 本地存储：path 就是文件系统绝对路径。
 * 空路径时返回「被纳管的根目录列表」，手机上选择文件夹时也是从这一层开始往下展开。
 */
class LocalMediaSource(override val source: StorageSource) : MediaSource {

    override suspend fun test(): ConnectionResult = withContext(Dispatchers.IO) {
        if (source.localPaths.isEmpty()) return@withContext offline("未选择任何本地文件夹")
        val bad = source.localPaths.filterNot { File(it).isDirectory }
        when {
            bad.size == source.localPaths.size -> offline("目录不存在或不可读：${bad.firstOrNull()}")
            bad.isNotEmpty() -> online("部分目录不可读：${bad.joinToString()}")
            else -> online("本地目录可访问（${source.localPaths.size} 个）")
        }
    }

    override suspend fun list(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        // 根层：返回配置的若干个根目录
        if (path.isBlank()) {
            return@withContext source.localPaths.map { p ->
                entryOf(source, p, File(p).name.ifBlank { p }, isDir = true)
            }
        }
        val dir = File(path)
        val children = dir.listFiles() ?: return@withContext emptyList()
        children.asSequence()
            .filter { !it.name.startsWith(".") }
            .mapNotNull { f ->
                if (f.isDirectory) entryOf(source, f.absolutePath, f.name, isDir = true)
                else if (MediaTypes.isMedia(f.name))
                    entryOf(source, f.absolutePath, f.name, isDir = false, size = f.length(), modified = f.lastModified())
                else null
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    override suspend fun openStream(entry: MediaEntry): InputStream = withContext(Dispatchers.IO) {
        val f = File(entry.path)
        if (!f.isFile) throw SourceException("文件不存在：${entry.path}")
        FileInputStream(f)
    }

    override fun close() = Unit
}
