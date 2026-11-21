package com.shiguang.album.data.store

import android.content.Context
import com.shiguang.album.data.model.Playlist
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 播放清单「文件数量」的持久化缓存。
 *
 * 为什么需要它：一个清单挂了 NAS 上 7000 多张照片，统计一次要把所有目录递归列一遍，
 * 走 WebDAV 要几十秒。而首页、播放器、编辑器来回切一次就是一次重新组合，
 * 原来只在内存里记（HomeState 的 countMemo），返回首页就全部重算 —— 用户看到的
 * 就是「统计出来过，再点进去又从头数」。
 *
 * 这里按「清单内容签名」落盘：签名 = 文件夹列表 + 是否包含子文件夹。
 * 签名没变就直接用上次的数，一个文件都不用列；用户改了文件夹才会重算。
 */
class PlaylistStatStore(context: Context) {

    @Serializable
    private data class StatEntry(
        val sig: String = "",
        val count: Int = 0,
        val updatedAt: Long = 0L
    )

    @Serializable
    private data class StatFile(
        val entries: Map<String, StatEntry> = emptyMap()
    )

    private val file = File(context.filesDir, "playlist_stats.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val guard = Any()
    private var entries: MutableMap<String, StatEntry> = load()

    private fun load(): MutableMap<String, StatEntry> = runCatching {
        if (file.exists()) json.decodeFromString<StatFile>(file.readText()).entries.toMutableMap()
        else mutableMapOf()
    }.getOrElse { mutableMapOf() }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, "playlist_stats.json.tmp")
            tmp.writeText(json.encodeToString(StatFile(entries.toMap())))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(StatFile(entries.toMap())))
                tmp.delete()
            }
        }
    }

    /** 清单内容签名：文件夹（含顺序、是否递归）变了就算不同 */
    fun signatureOf(playlist: Playlist): String {
        val raw = buildString {
            append(playlist.includeSubFolders).append('|')
            playlist.folders.forEach { append(it.sourceId).append(':').append(it.path).append('|') }
        }
        return sha1(raw)
    }

    /** 有缓存且签名一致时直接返回，否则 null（调用方去后台数一次） */
    fun cachedCount(playlist: Playlist): Int? {
        val e = synchronized(guard) { entries[playlist.id] } ?: return null
        return if (e.sig == signatureOf(playlist)) e.count else null
    }

    /** 缓存是否老到值得后台重数一次（默认一天） */
    fun isStale(playlist: Playlist, maxAgeMs: Long = 24L * 60 * 60 * 1000): Boolean {
        val e = synchronized(guard) { entries[playlist.id] } ?: return true
        if (e.sig != signatureOf(playlist)) return true
        return System.currentTimeMillis() - e.updatedAt > maxAgeMs
    }

    fun put(playlist: Playlist, count: Int) {
        synchronized(guard) {
            // 数出 0 先别当定论：多半是 NAS 刚刚超时/断线（用户明明有一堆照片）。
            // 把 updatedAt 记成 0，下次进首页 isStale 会立刻命中，自动再数一遍。
            val stamp = if (count > 0 || playlist.folders.isEmpty()) System.currentTimeMillis() else 0L
            entries[playlist.id] = StatEntry(signatureOf(playlist), count, stamp)
            persist()
        }
    }

    /** 用户主动点「重新统计」：先丢掉缓存，避免数到一半又被旧值盖回去 */
    fun invalidate(playlistId: String) {
        synchronized(guard) {
            if (entries.remove(playlistId) != null) persist()
        }
    }

    fun remove(playlistId: String) = invalidate(playlistId)

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
