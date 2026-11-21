# 拾光相册 · TV（ShiGuang Album TV）

把家里的照片墙搬到电视上：本地硬盘、NAS（WebDAV / SMB）、手机里导出的照片，做成一条条**播放清单**，配上切换动效和背景音乐，遥控器一按就开始放。手机扫码就能改配置，不用在电视上敲字。

给爸妈用的一款东西——所以他们能碰到的每个按钮都得看得清、够得着、不会误触。

- 包名：`com.shiguang.album`
- 最低系统：Android 5.0（API 21）· 目标 Android 14（API 34）
- 主平台：Android TV / 电视盒子（实测 Android 7.1 盒子 + 小米电视）
- 输入：遥控器为主，手机网页为辅
- 当前版本：**v1.7.3**（versionCode 14）

![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-yellow)

> 使用说明见 **[使用说明.md](使用说明.md)**，实现细节与设计取舍见 **[开发文档.md](开发文档.md)**。

---

## 它解决什么问题

电视上放照片，现成的方案通常是「U 盘 + 系统相册」，或者投屏。前者要反复插拔，后者手机不能锁屏。这个项目想把三件事做顺：

1. **照片留在原来的地方**（NAS、旧硬盘、电视本机都行），不用搬来搬去；
2. **一组照片 = 一个清单**，每个清单有自己的动效、停留时长、背景音乐；
3. **配置不上电视敲**，手机扫码改，改完电视上立刻生效。

---

## 功能

### 播放

- **三种存储源**：本地目录、WebDAV（群晖 / 坚果云 / Nextcloud）、SMB（SMB1/2/3 自动协商，兼容老 NAS）
- **一个清单可以包含多个文件夹**，并可选「包含子文件夹」
- **7 种切换动效**：淡入淡出、左右滑动、上下滑动、缩放推进、旋转翻页、马赛克溶解、每次随机
- **播放顺序**：顺序 / 随机 / 单张循环；**播完之后**：循环、停在最后一张、回到首页
- **背景音乐**：本地文件、直链、在线搜歌；支持多首曲目轮播、独立音量、淡入淡出、循环
- **视频同播**：清单里的视频会自动接在图片之间播放
- **照片切换不黑屏**：下一张完全解码好之后才切场，等待期间一直停在当前这张（Android 7.1 上也验证过）

### 电视端

- 全遥控器可完成配置，包括输入密码、选文件夹、选动效
- 自带软键盘（英文 / 数字 / 符号 / 中文页签 + 常驻数字小键盘）
- 首页卡片网格，焦点放大 1.08 + 白描边；深色 / 浅色主题 + 自定义强调色 + 壁纸
- 壁纸为程序绘制（渐变 + 径向光斑），不占包体，4K 也不糊；也支持自己的照片和视频壁纸

### 手机配置页（扫码即用）

- 电视内置 Ktor 服务，手机浏览器直连，**不用装 App**
- WebSocket 实时同步：手机上改外观，电视上立刻变
- **清单缩略图**：电视端把首图采样压缩成 180px JPEG 缓存下来，手机拉的就是这张小图，不再传几 MB 原图
- **长按左侧手柄上下拖动排序**

### 在线音乐（默认零配置）

- 内置直连网易云 + 酷我，装完就能搜、能听，**不需要自己搭服务**
- 想覆盖 QQ / 酷狗 / 咪咕 / B站 等更多音源，可以选填一个自建聚合服务（详见下文「参考项目」）
- 试听 / 选用前都会先试一遍能不能真的取到音频，死链和 VIP 曲会直接告诉你，不会存进清单

---

## 快速开始

### 直接装

到 [Releases](../../releases) 下载 `拾光相册-vX.Y.Z-release.apk`，用 U 盘或 `adb install` 装到电视。

```bash
adb connect 192.168.1.x:5555     # 电视需开启「开发者选项 → 网络调试」
adb install -r 拾光相册-v1.7.3-release.apk
```

首次打开会自带一个「电视本机」存储源（`Pictures` / `DCIM` / `Movies` / `Download`），**不会预置任何清单**，第一条要自己建。

### 三步开始播放

1. 首页「＋ 新建清单」→ 起个名字
2. 「选文件夹」→ 进入存储源 → 进到目标文件夹 → 直接按「确定」就纳入整个文件夹
3. 挑动效和停留时长 → 保存 → 首页按 OK 开始播

更细的说明（含 NAS 配置、音乐、壁纸、遥控器按键表）都在 **[使用说明.md](使用说明.md)**。

---

## 手机配置

1. 首页右上角 📱 → 弹出二维码
2. 手机连同一个 Wi-Fi，扫码打开 `http://<电视IP>:8080/`
3. 手机上可以建清单、拖拽排序、改外观、加存储源

> 端口被占用时会自动顺延到 8081~8089，**以二维码里的地址为准**。

---

## 在线音乐与参考项目

这块要单独说明一下，因为它借了别人的思路。

**内置直连（默认，零配置）**

App 内直接调用平台的公开 Web 接口完成「搜 → 取流」，不经过任何中间服务：

| 平台 | 搜索 | 取流 |
| --- | --- | --- |
| 网易云 | `music.163.com/api/search/get/web` | `music.163.com/song/media/outer/url` |
| 酷我 | `search.kuwo.cn/r.s` | `antiserver.kuwo.cn/anti.s` |

播放地址不落盘——多数平台地址带时效签名，存下来就过期，每次播放现取。

**可选的自建聚合服务**

如果要覆盖 QQ / 酷狗 / 咪咕 / B站 等 13 个音源，可以自己跑一个聚合服务，在「管理 → 音乐服务」里填地址。接口形式参照了这几类自托管项目：

- **[go-music-dl](https://github.com/guohuiyuan/go-music-dl)** — 本项目参照了它「把聚合服务自托管、Android 侧内嵌/对接」的整体思路，以及它的搜索页与取流接口形态；
- **music-lib** 等同类聚合项目 — 提供多音源搜索结果与播放地址解析。

对接的具体形态（`*` 为本项目实现，非上游代码）：

```
GET  {base}/search?q=<关键词>&type=song[&sources=netease,kuwo...]   → 返回 HTML，解析 li.song-card 上的 data-*
GET  {base}/download?id=&source=&name=&artist=&album=&cover=&extra=&stream=1
```

**这些项目本身都是自托管服务，官方没有公网实例**，所以「要不要自建」不是接法问题，而是它们的形态本身。因此本项目把最常用的两个平台直接内置了，装完就能听；自建服务属于可选的增强，不填也一切正常。

> 本项目**不包含** go-music-dl / music-lib 的任何源代码，只是按它们的接口约定去对接，并借鉴了「服务自托管 + 客户端轻量」的产品思路。感谢这些项目的探索。

---

## 遥控器操作

| 场景 | 按键 | 行为 |
| --- | --- | --- |
| 首页 | 方向键 | 移动焦点（卡片放大 1.08 + 白描边） |
| 首页 | OK | 进入全屏播放 |
| 首页 | 长按 OK / 菜单键 | 清单管理（播放 / 编辑 / 复制 / 删除） |
| 播放 | 左 / 右 | 上一张 / 下一张 |
| 播放 | OK | 暂停 / 继续 |
| 播放 | 上 / 下 | 系统媒体音量 |
| 播放 | 菜单 | 播放设置（模式 / 动效 / 时长 / 顺序） |
| 输入 | OK | 弹出软键盘；方向键移动、OK 输入、返回取消 |
| 二级页面 | 返回 | 回上一级 |
| 有弹窗时 | 返回 | 只关当前弹窗 |
| 首页 | 返回 | 退出应用 |

---

## 技术选型与取舍

| 需求点 | 实现 | 为什么 |
| --- | --- | --- |
| UI | Kotlin + Jetpack Compose（自研焦点组件） | tv-material 的卡片做不出「1.08 放大 + 2dp 白描边」，焦点态全部自己控制，兼容性反而更好 |
| SMB | jcifs-ng 2.1.10 | 自动协商 SMB1/2/3，老 NAS 也能连 |
| WebDAV | OkHttp + 自写 PROPFIND 解析 | 不引第三方 Dav 库，群晖 / 坚果云 / Nextcloud 都验证过 |
| 图片 | Coil 3 | 采样解码 + 磁盘缓存；远程文件先落地再解码，避免主线程解码大图 |
| 视频 | Media3 ExoPlayer | MP4/MKV/AVI 交给系统解码器 |
| 音乐 | MediaPlayer + 音量淡入淡出 | 音频场景更轻，独立音量不干扰系统音量 |
| 配置服务 | 内嵌 Ktor CIO + 单文件网页 | 手机直连，无需装 App；WebSocket 实时同步 |
| 存储 | 单文件 JSON（原子写） | 配置只有几 KB，省掉 Room 的注解处理与迁移成本 |
| 密码 | Keystore AES/GCM / 应用私有随机密钥 | 配置文件与接口里都不出现明文密码 |

---

## 目录结构

```
ShiGuangAlbumTV/
├── app/src/main/
│   ├── assets/config/index.html          # 手机配置页（单文件，CSS/JS 全内联）
│   ├── res/                              # 图标、TV 横幅
│   └── java/com/shiguang/album/
│       ├── TvAlbumApp.kt                 # Application：启动配置服务 + Coil
│       ├── AppContainer.kt               # 极简依赖容器
│       ├── MainActivity.kt               # 全屏沉浸入口
│       ├── data/
│       │   ├── model/Models.kt           # 存储源 / 清单 / 音乐 / 动效 模型
│       │   ├── store/ConfigStore.kt      # JSON 持久化 + 状态流 + 启动迁移
│       │   ├── security/SecretCrypto.kt   # AES/GCM 密码加密
│       │   ├── source/                   # LocalMediaSource / WebDAV / SMB / OnlineMusicService
│       │   ├── source/MediaRepository.kt # 清单 → 文件列表、封面、断线重试
│       │   └── cache/MediaCache.kt        # 远程文件与封面缓存（1GB 上限）
│       ├── server/ConfigServer.kt        # Ktor：REST + WebSocket + 配置页
│       └── ui/
│           ├── AppRoot.kt                # 首页 / 播放 / 管理 导航
│           ├── home/ player/ settings/ components/
│           └── theme/Theme.kt            # 深色 + 浅色 + 强调色
├── 使用说明.md
├── 开发文档.md
└── LICENSE
```

---

## 自己编译

需要 JDK 17+ 与 Android SDK。工程**没有带 `gradlew`**，用本机 Gradle 8.13 即可：

```bash
# 调试包
gradle assembleDebug --console=plain
# 只验语法，改完代码先跑这个（比打包快一倍）
gradle compileDebugKotlin --console=plain
# 正式包
gradle assembleRelease --console=plain
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

几个容易踩的点：

- `local.properties` 里的 `sdk.dir` 要改成你机器上的 SDK 路径；
- 工程放在中文目录下时需要 `android.overridePathCheck=true`（`gradle.properties` 已带）；
- 国内网络建议在 `settings.gradle.kts` 里把阿里云镜像放到最前；
- `release` 默认复用 debug 签名，方便直接侧载；要正式签名见 `开发文档.md` 7.7。

---

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 手机打不开配置页 | 确认同网段；路由器的 AP 隔离/访客网络会拦；看二维码里的 IP 对不对 |
| 8080 被占用 | 服务自动顺延 8081~8089，以二维码地址为准 |
| SMB 认证失败 | 老设备只支持 SMB1 时确认共享已开启；域用户写成 `DOMAIN\user` |
| WebDAV 401 | 群晖要开 WebDAV Server 套件，确认端口（默认 5005/5006） |
| 播放卡顿 | 远程源建议千兆内网；或把照片降到 4K 以内 |
| 应用装不上 | 电视要允许「未知来源」；部分机型用 `adb install --user 0` |
| 搜到的歌放不了 | VIP / 版权曲拿不到音频，界面会直接标出来 |

更多见 [使用说明.md](使用说明.md#常见问题)。

---

## 已知限制

- 远程视频会先完整下载到本地缓存再播放，4GB 以上的 MKV 首次要等；边下边播在计划里。
- 「马赛克」动效是 14×8 网格对角揭开，观感接近像素化翻转但更省性能。
- 仅 Android；iOS 需要另建工程，但 UI 与协议设计可以直接复用。
- 电视端软键盘的中文页签走系统输入法，电视没装中文输入法时，中文名建议在手机上改。
- 在线音乐依赖平台公开接口，平台调整后需要跟着改（`OnlineMusicService.kt`）。

---

## 许可

[MIT License](LICENSE) —— 你可以任意使用、修改、闭源分发、商用，**唯一要求是保留版权声明与许可声明**（在副本或衍生物里注明来源）。

简单说：**欢迎复刻，注明出处就行。**

```
Copyright (c) 2026 ShiGuang Album TV (拾光相册 TV) and contributors
```

---

## 致谢

- [go-music-dl](https://github.com/guohuiyuan/go-music-dl) 与 music-lib 等音频聚合项目：在线音乐的服务形态与接口约定参照
- [Coil](https://github.com/coil-kt/coil)、[Media3](https://github.com/androidx/media)、[Ktor](https://ktor.io/)、[jcifs-ng](https://github.com/AgNO3/jcifs-ng)、[OkHttp](https://square.github.io/okhttp/)
- 界面节奏参考了 tvOS 设置页与几家电视端 App 的焦点处理方式

如果这个项目帮到了你，点个 Star 就好。
