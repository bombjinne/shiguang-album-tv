package com.shiguang.album.data.source

import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.data.store.ConfigStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 存储源实例工厂：同一个源复用同一个客户端（避免每次列目录都重新握手 SMB / 建连接池）。
 * 断线或改配置时调用 [invalidate]，下次会自动重建——这就是「自动重连」的实现基础。
 */
class MediaSourceFactory(private val store: ConfigStore) {

    private val cache = ConcurrentHashMap<String, MediaSource>()

    @Synchronized
    fun get(source: StorageSource): MediaSource {
        cache[source.id]?.let { existing ->
            // 配置变了（比如改了密码/地址）就重建
            if (existing.source == source) return existing
            cache.remove(source.id)?.close()
        }
        val password = store.decryptPassword(source.passwordEnc)
        val created = when (source.type) {
            SourceType.LOCAL -> LocalMediaSource(source)
            SourceType.WEBDAV -> WebDavMediaSource(source, password)
            SourceType.SMB -> SmbMediaSource(source, password)
        }
        cache[source.id] = created
        return created
    }

    @Synchronized
    fun invalidate(sourceId: String) {
        cache.remove(sourceId)?.close()
    }

    @Synchronized
    fun closeAll() {
        cache.values.forEach { runCatching { it.close() } }
        cache.clear()
    }
}
