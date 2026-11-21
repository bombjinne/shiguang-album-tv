package com.shiguang.album.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * 把系统文件选择器（SAF）返回的 `content://` 树 URI 还原成真实绝对路径。
 *
 * 电视上的「选择目录」走 ACTION_OPEN_DOCUMENT_TREE，拿到的是
 * `content://com.android.externalstorage.documents/tree/primary%3APictures`，
 * 而我们的本地源是按 File 绝对路径工作的，所以这里做一次转换。
 *
 * 只处理外置存储（primary / SD 卡），其它 provider（云盘等）返回 null。
 */
object TreeUri {

    fun toPath(context: Context, uri: Uri): String? {
        val authority = uri.authority ?: return null
        if (!authority.contains("externalstorage")) return null
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)   // 形如 "primary:Pictures"
            val sep = docId.indexOf(':')
            val volume = if (sep >= 0) docId.substring(0, sep) else docId
            val relative = if (sep >= 0) docId.substring(sep + 1) else ""

            val base = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volume"
            }
            if (relative.isBlank()) base else "$base/$relative"
        }.getOrNull()
    }

    /** 记住这个目录的读权限，重启后仍然可读（Android 5.0+ 支持持久化权限） */
    fun takePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
