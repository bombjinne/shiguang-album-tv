package com.shiguang.album.data.source

import android.net.Uri
import com.shiguang.album.data.model.ConnectionResult
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.StorageSource
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Properties

/**
 * SMB / CIFS 客户端（jcifs-ng，自动协商 SMB1 / SMB2 / SMB3）。
 *
 * path 约定：相对共享目录的路径，以 "/" 开头，例如 "/家庭照片/2024"。
 * 子项路径由「父路径 + 子项名」拼出来，避免依赖 jcifs 的 URL 编解码细节。
 */
class SmbMediaSource(
    override val source: StorageSource,
    private val password: String
) : MediaSource {

    private var context: CIFSContext? = null

    private fun ctx(): CIFSContext {
        context?.let { return it }

        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            setProperty("jcifs.smb.client.connTimeout", "15000")
            setProperty("jcifs.netbios.cachePolicy", "120")
            setProperty("jcifs.smb.client.useExtendedSecurity", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val user = source.username.trim()
        val domain = if (user.contains('\\')) user.substringBefore('\\') else ""
        val name = if (user.contains('\\')) user.substringAfter('\\') else user
        val auth = NtlmPasswordAuthenticator(domain, name, password)
        val withAuth = base.withCredentials(auth)
        context = withAuth
        return withAuth
    }

    private fun encode(segment: String): String = Uri.encode(segment)

    private fun urlFor(path: String): String {
        val host = source.host.trim()
            .removePrefix("smb://").removePrefix("SMB://")
            .trimEnd('/')
        val sb = StringBuilder("smb://").append(host)

        val share = source.shareName.trim().trim('/')
        if (share.isNotEmpty()) share.split('/').filter { it.isNotEmpty() }.forEach { sb.append('/').append(encode(it)) }

        val p = path.trim().trim('/')
        if (p.isNotEmpty()) p.split('/').filter { it.isNotEmpty() }.forEach { sb.append('/').append(encode(it)) }

        return sb.toString()
    }

    private fun smbFile(path: String): SmbFile = SmbFile(urlFor(path), ctx())

    override suspend fun test(): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            val f = smbFile("")
            if (f.exists()) {
                val children = runCatching { f.list()?.size ?: 0 }.getOrDefault(0)
                online("SMB 可访问（$children 项）")
            } else {
                offline("共享路径不存在或不可访问")
            }
        } catch (t: Throwable) {
            classify(t)
        }
    }

    override suspend fun list(path: String): List<MediaEntry> = withContext(Dispatchers.IO) {
        try {
            val dir = smbFile(path)
            if (!dir.isDirectory) throw SourceException("不是目录：$path")
            val children = dir.listFiles() ?: return@withContext emptyList()
            val parent = path.trim().trim('/')
            children.asSequence()
                .filter { !it.name.startsWith(".") }
                .mapNotNull { f ->
                    val name = f.name.trimEnd('/')
                    if (name.isEmpty()) return@mapNotNull null
                    val childPath = if (parent.isEmpty()) "/$name" else "/$parent/$name"
                    val isDir = f.isDirectory
                    if (!isDir && !MediaTypes.isMedia(name)) return@mapNotNull null
                    val size = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L)
                    val modified = if (isDir) 0L else runCatching { f.lastModified() }.getOrDefault(0L)
                    entryOf(source, childPath, name, isDir, size, modified)
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
        } catch (t: Throwable) {
            throw SourceException(t.message ?: "SMB 列目录失败")
        }
    }

    override suspend fun openStream(entry: MediaEntry): InputStream = withContext(Dispatchers.IO) {
        try {
            smbFile(entry.path).getInputStream()
        } catch (t: Throwable) {
            throw SourceException(t.message ?: "SMB 读取失败")
        }
    }

    override fun close() {
        val c = context ?: return
        context = null
        // CIFSContext 在不同小版本里父接口不同，用 Closeable 判定，避免编译期强依赖
        runCatching { (c as? java.io.Closeable)?.close() }
    }

    private fun classify(t: Throwable): ConnectionResult {
        val msg = t.message ?: t.javaClass.simpleName
        return when {
            t.javaClass.simpleName.contains("Auth", ignoreCase = true) ||
                msg.contains("STATUS_LOGON_FAILURE", ignoreCase = true) ||
                msg.contains("ACCESS_DENIED", ignoreCase = true) -> authFailed("SMB 认证失败：$msg")

            msg.contains("UnknownHost", ignoreCase = true) ||
                msg.contains("Connection refused", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true) -> offline("无法连接：$msg")

            else -> offline(msg)
        }
    }
}
