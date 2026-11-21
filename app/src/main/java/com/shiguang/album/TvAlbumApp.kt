package com.shiguang.album

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import java.net.BindException
import java.net.SocketException

/**
 * 应用入口：
 * 1. 启动时就把局域网配置服务跑起来（这样电视上打开应用，手机立刻能扫码配置）
 * 2. 统一配置 Coil（磁盘缓存 + 视频首帧解码），首页封面才能「秒开」
 */
class TvAlbumApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installServerCrashGuard()
        container = AppContainer(this)
        // Ktor 引擎初始化有点耗时，放到后台线程，避免拖慢首帧
        Thread {
            runCatching { container.server.start() }
        }.apply { name = "config-server"; isDaemon = true }.start()
    }

    /**
     * 兜底「配置服务」的网络异常。
     *
     * Ktor CIO 把 bind / accept 放在自己的协程里，端口被占时抛出的 BindException
     * 不属于任何调用栈，默认行为是杀掉整个进程 —— 在电视上就是「点开就闪退」。
     * 这里只拦与 socket 绑定/监听相关的异常，让它降级成「手机配置暂时不可用」，
     * 电视上的相册播放完全不受影响；其它异常照旧交给系统，免得把真 bug 吞掉。
     */
    private fun installServerCrashGuard() {
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (isSocketServerFailure(error)) {
                Log.w(TAG, "配置服务网络异常，已拦截（电视播放不受影响）", error)
            } else {
                fallback?.uncaughtException(thread, error)
            }
        }
    }

    private fun isSocketServerFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is BindException) return true
            if (current is SocketException && current.stackTrace.any { it.className.startsWith("io.ktor") }) return true
            if (current.stackTrace.any { it.className.startsWith("io.ktor.server.cio") }) return true
            current = current.cause
            depth++
        }
        return false
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.2)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, "image_cache"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        // 切换动效由我们自己做，这里关掉 Coil 的淡入，避免两套动画打架
        .crossfade(false)
        .components { add(VideoFrameDecoder.Factory()) }
        .build()

    override fun onTerminate() {
        container.server.shutdown()
        container.repository.release()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "TvAlbumApp"
    }
}
