package com.shiguang.album.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.ui.components.AppBackground
import com.shiguang.album.ui.components.Caption
import com.shiguang.album.ui.components.SectionTitle
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.SettingsPageHeader
import com.shiguang.album.ui.components.TvInputRow
import com.shiguang.album.ui.components.TvKeyboardDialog
import com.shiguang.album.ui.components.TvSegmented
import com.shiguang.album.ui.components.TvSwitchRow
import com.shiguang.album.ui.theme.TvTheme
import com.shiguang.album.util.NetworkUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ============================================================================
 * 电视端「存储源」编辑器
 *
 * 全部字段都能用遥控器填完：文本走软键盘，本地目录走目录浏览器。
 * 手机配置页只是加速通道，不是必要条件。
 * ========================================================================== */

private enum class Field { NAME, HOST, PORT, SHARE, USER, PASS }

@Composable
fun SourceEditorScreen(
    container: AppContainer,
    sourceId: String?,
    onBack: () -> Unit
) {
    val colors = TvTheme.colors
    val scope = rememberCoroutineScope()
    val store = container.configStore

    val original = remember(sourceId) { sourceId?.let { store.source(it) } }
    // 新建时主机地址默认填「电视当前网段」，用户只需补最后一段
    val subnet = remember { NetworkUtil.lanSubnetPrefix() }
    var draft by remember(sourceId) {
        mutableStateOf(original ?: StorageSource(name = "新的存储源", host = subnet))
    }
    var password by remember { mutableStateOf("") }
    var editingField by remember { mutableStateOf<Field?>(null) }
    var showDirDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    LaunchedEffect(toast) { if (toast != null) { delay(2400); toast = null } }

    // 弹窗/软键盘打开时不接管返回键，避免一次返回退两层
    BackHandler(enabled = editingField == null && !showDirDialog) { onBack() }

    fun save(thenTest: Boolean = false) {
        if (draft.name.isBlank()) { toast = "请先填写名称"; return }
        if (draft.type == SourceType.LOCAL && draft.localPaths.isEmpty()) {
            toast = "请至少选择一个本地文件夹"; return
        }
        if (draft.type != SourceType.LOCAL && draft.host.isBlank()) {
            toast = "请填写主机地址"; return
        }
        busy = true
        scope.launch {
            val toSave = draft.copy(
                name = draft.name.trim(),
                host = draft.host.trim(),
                shareName = draft.shareName.trim(),
                username = draft.username.trim(),
                passwordEnc = if (password.isNotEmpty()) store.encryptPassword(password) else draft.passwordEnc
            )
            store.upsertSource(toSave)
            draft = toSave
            password = ""
            if (thenTest) {
                val r = container.repository.testSource(toSave.id)
                toast = if (r.ok) "连接正常：${r.message}" else "连接失败：${r.message}"
                busy = false
            } else {
                // 保存完直接回到存储源列表：还要再点一次「返回」是多余的一步
                busy = false
                onBack()
            }
        }
    }

    AppBackground(container) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            SettingsPageHeader(
                title = if (original == null) "添加存储源" else "编辑存储源",
                subtitle = "本地文件夹、WebDAV、SMB 都能连",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp)
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    TvInputRow(
                        label = "名称",
                        value = draft.name,
                        placeholder = "例如：客厅 NAS",
                        focusRequester = nameFocus,
                        onClick = { editingField = Field.NAME }
                    )
                }

                item {
                    SectionTitle("类型")
                }

                item {
                    TvSegmented(
                        options = listOf(
                            SourceType.LOCAL to "本地存储",
                            SourceType.WEBDAV to "WebDAV",
                            SourceType.SMB to "SMB 共享"
                        ),
                        selected = draft.type,
                        onSelect = { newType ->
                            // 从本地切到远程时，主机地址若为空就补上网段前缀
                            draft = draft.copy(
                                type = newType,
                                host = if (draft.host.isBlank() && newType != SourceType.LOCAL) subnet else draft.host
                            )
                        }
                    )
                }

                if (draft.type == SourceType.LOCAL) {
                    item {
                        SectionTitle("本地文件夹")
                    }
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.surface, RoundedCornerShape(16.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            if (draft.localPaths.isEmpty()) {
                                Caption("还没有选择文件夹，点下面的按钮用遥控器浏览电视上的目录")
                            } else {
                                draft.localPaths.forEachIndexed { index, p ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            p,
                                            color = colors.textPrimary,
                                            fontSize = 15.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        TvButton(
                                            text = "移除",
                                            danger = true,
                                            onClick = {
                                                draft = draft.copy(
                                                    localPaths = draft.localPaths.filterIndexed { i, _ -> i != index }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            TvButton(
                                text = "浏览电视上的文件夹",
                                primary = true,
                                onClick = { showDirDialog = true }
                            )
                        }
                    }
                } else {
                    item {
                        TvInputRow(
                            label = "主机地址",
                            value = draft.host,
                            placeholder = "${subnet}10（补最后一段即可）",
                            onClick = { editingField = Field.HOST }
                        )
                    }
                    item {
                        TvInputRow(
                            label = "端口",
                            value = if (draft.port == 0) "" else draft.port.toString(),
                            placeholder = if (draft.type == SourceType.SMB) "445（留空默认）" else "留空按协议推断",
                            onClick = { editingField = Field.PORT }
                        )
                    }
                    item {
                        TvInputRow(
                            label = if (draft.type == SourceType.WEBDAV) "路径" else "共享文件夹",
                            value = draft.shareName,
                            placeholder = if (draft.type == SourceType.WEBDAV) "/dav/photos" else "photos",
                            onClick = { editingField = Field.SHARE }
                        )
                    }
                    item {
                        TvInputRow(
                            label = "用户名",
                            value = draft.username,
                            placeholder = "可留空（匿名访问）",
                            onClick = { editingField = Field.USER }
                        )
                    }
                    item {
                        TvInputRow(
                            label = "密码",
                            value = if (password.isNotEmpty()) password
                            else if (draft.passwordEnc.isNotEmpty()) "已保存" else "",
                            placeholder = "可留空",
                            masked = password.isNotEmpty(),
                            onClick = { editingField = Field.PASS }
                        )
                    }
                    if (draft.type == SourceType.WEBDAV) {
                        item {
                            TvSwitchRow(
                                label = "使用 HTTPS",
                                hint = "自签证书可能连接失败",
                                checked = draft.secure,
                                onChange = { draft = draft.copy(secure = it) }
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = if (busy) "处理中…" else "测试连接",
                            onClick = { if (!busy) save(thenTest = true) }
                        )
                        TvButton(
                            text = "保存",
                            primary = true,
                            onClick = { if (!busy) { save(); } }
                        )
                        if (original != null) {
                            TvButton(
                                text = "删除这个源",
                                danger = true,
                                onClick = {
                                    scope.launch {
                                        store.deleteSource(draft.id)
                                        onBack()
                                    }
                                }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(30.dp)) }
            }
        }

        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .background(colors.surfaceHigh, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) { Text(msg, color = colors.textPrimary, fontSize = 15.sp) }
        }
    }

    // 软键盘
    editingField?.let { field ->
        val cfg = when (field) {
            Field.NAME -> Triple("存储源名称", draft.name, false)
            Field.HOST -> Triple("主机地址（IP 或域名）", draft.host, false)
            Field.PORT -> Triple("端口（0 表示默认）", if (draft.port == 0) "" else draft.port.toString(), true)
            Field.SHARE -> Triple(
                if (draft.type == SourceType.WEBDAV) "WebDAV 路径" else "共享文件夹名",
                draft.shareName, false
            )
            Field.USER -> Triple("用户名", draft.username, false)
            Field.PASS -> Triple("密码（加密保存在电视上）", "", false)
        }
        TvKeyboardDialog(
            title = cfg.first,
            initial = cfg.second,
            numeric = cfg.third,
            masked = field == Field.PASS,
            onDismiss = { editingField = null },
            onConfirm = { value ->
                draft = when (field) {
                    Field.NAME -> draft.copy(name = value)
                    Field.HOST -> draft.copy(host = value)
                    Field.PORT -> draft.copy(port = value.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                    Field.SHARE -> draft.copy(shareName = value)
                    Field.USER -> draft.copy(username = value)
                    Field.PASS -> draft
                }
                if (field == Field.PASS) password = value
                editingField = null
            }
        )
    }

    if (showDirDialog) {
        TvLocalDirDialog(
            initialPaths = draft.localPaths,
            onDismiss = { showDirDialog = false },
            onConfirm = { paths ->
                draft = draft.copy(localPaths = paths)
                showDirDialog = false
            }
        )
    }
}
