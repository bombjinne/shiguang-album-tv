package com.shiguang.album

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shiguang.album.ui.AppRoot
import com.shiguang.album.ui.theme.TvAlbumTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 拒绝也不阻塞，本地源会提示不可读 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 电视全屏沉浸：常亮 + 隐藏系统栏（遥控器焦点完全交给应用）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        requestMediaPermissions()

        setContent {
            val container = (application as TvAlbumApp).container
            val appState by container.configStore.state.collectAsState()
            val settings = appState.settings
            val systemDark = isSystemInDarkTheme()

            // 主题随配置实时切换：手机端改完，电视端立即换肤，不用重启
            TvAlbumTheme(
                themeMode = settings.themeMode,
                accentColor = settings.accentColor,
                systemDark = systemDark
            ) {
                AppRoot(container = container)
            }
        }
    }

    /** 本地相册需要读媒体权限；Android 13+ 用细分权限，旧版本用 READ_EXTERNAL_STORAGE */
    private fun requestMediaPermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }
        }
    }
}
