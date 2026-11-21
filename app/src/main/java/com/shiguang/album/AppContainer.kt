package com.shiguang.album

import android.content.Context
import com.shiguang.album.data.cache.MediaCache
import com.shiguang.album.data.source.MediaRepository
import com.shiguang.album.data.source.OnlineMusicService
import com.shiguang.album.data.source.MediaSourceFactory
import com.shiguang.album.data.store.ConfigStore
import com.shiguang.album.data.store.PlaylistStatStore
import com.shiguang.album.data.store.VideoWallpaperStore
import com.shiguang.album.server.ConfigServer

/**
 * 极简依赖容器（不引入 Hilt，减少学习成本和构建复杂度）。
 * 所有单例都挂在这里，Activity / Composable / HTTP 服务共用同一份实例。
 */
class AppContainer(val context: Context) {

    val configStore: ConfigStore = ConfigStore(context)
    /** 清单文件数缓存：跨页面、跨重启复用，不再重复遍历上万张照片 */
    val statStore: PlaylistStatStore = PlaylistStatStore(context)
    val sourceFactory: MediaSourceFactory = MediaSourceFactory(configStore)
    val repository: MediaRepository = MediaRepository(configStore, sourceFactory)
    val mediaCache: MediaCache = MediaCache(context, repository)
    /** 视频壁纸的本地副本（filesDir，不受 cacheDir 清理影响，冷启动 0 等待） */
    val videoWallpaper: VideoWallpaperStore = VideoWallpaperStore(context, mediaCache)
    val onlineMusic: OnlineMusicService = OnlineMusicService(configStore)

    val server: ConfigServer = ConfigServer(context, configStore, repository, mediaCache, onlineMusic, videoWallpaper)
}
