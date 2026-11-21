package com.shiguang.album.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shiguang.album.AppContainer
import com.shiguang.album.ui.home.HomeScreen
import com.shiguang.album.ui.player.PlayerScreen
import com.shiguang.album.ui.settings.AppearanceScreen
import com.shiguang.album.ui.settings.ManageScreen
import com.shiguang.album.ui.settings.PlaylistEditorScreen
import com.shiguang.album.ui.settings.SourceEditorScreen

/**
 * 页面栈：首页 / 播放 / 管理 / 外观 / 清单编辑 / 存储源编辑。
 * 用状态导航，不引入 Navigation 依赖，电视端页面深度很浅，够用且更好控焦点。
 */
private sealed interface Screen {
    data object Home : Screen
    data class Player(val playlistId: String) : Screen
    data object Manage : Screen
    data object Appearance : Screen

    /**
     * playlistId 为 null 表示新建。
     *
     * [token] 是每次进入都不同的随机值：`Screen.PlaylistEditor(null)` 两次是相等的 data class，
     * 编辑器内部的 `remember(playlistId)` 认不出这是"又新建了一个"，可能沿用上一次的草稿
     * （含那个已经存过盘的 id）—— 一保存就把上一份覆盖了。用 token 强制重建。
     */
    data class PlaylistEditor(val playlistId: String?, val token: Long = 0L) : Screen

    /** sourceId 为 null 表示新建 */
    data class SourceEditor(val sourceId: String?) : Screen
}

@Composable
fun AppRoot(container: AppContainer) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // 真正的返回栈：不要给每个页面写死「返回到管理页」。
    // 之前从首页点「＋新建清单」，按返回会莫名跳到管理页 —— 因为 onBack 被写成了 Manage。
    val backStack = remember { mutableStateListOf<Screen>() }

    fun navigate(next: Screen) {
        backStack.add(screen)
        screen = next
    }

    fun goBack() {
        screen = if (backStack.isEmpty()) Screen.Home else backStack.removeAt(backStack.lastIndex)
    }

    when (val current = screen) {
        is Screen.Home -> HomeScreen(
            container = container,
            onPlay = { playlist -> navigate(Screen.Player(playlist.id)) },
            onManageSources = { navigate(Screen.Manage) },
            onAppearance = { navigate(Screen.Appearance) },
            onEditPlaylist = { id -> navigate(Screen.PlaylistEditor(id, System.nanoTime())) }
        )

        is Screen.Player -> PlayerScreen(
            container = container,
            playlistId = current.playlistId,
            onExit = { goBack() }
        )

        is Screen.Manage -> ManageScreen(
            container = container,
            onBack = { goBack() },
            onEditPlaylist = { id -> navigate(Screen.PlaylistEditor(id, System.nanoTime())) },
            onEditSource = { id -> navigate(Screen.SourceEditor(id)) },
            onAppearance = { navigate(Screen.Appearance) }
        )

        is Screen.Appearance -> AppearanceScreen(
            container = container,
            onBack = { goBack() }
        )

        is Screen.PlaylistEditor -> PlaylistEditorScreen(
            container = container,
            playlistId = current.playlistId,
            editorToken = current.token,
            onBack = { goBack() }
        )

        is Screen.SourceEditor -> SourceEditorScreen(
            container = container,
            sourceId = current.sourceId,
            onBack = { goBack() }
        )
    }
}
