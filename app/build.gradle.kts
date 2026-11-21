plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.shiguang.album"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shiguang.album"
        minSdk = 21
        targetSdk = 34
        versionCode = 14
        versionName = "1.7.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 未配置正式签名前，release 也使用 debug 签名，方便直接侧载测试
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // Android 5.x 上没有 java.time 等新 API，用脱糖补上（Ktor 等库依赖）
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        // 首页底栏要显示版本号（BuildConfig.VERSION_NAME）
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    // 视频播放
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 图片加载 + 视频首帧缩略图
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // 二维码
    implementation("com.google.zxing:core:3.5.3")

    // WebDAV 走 HTTP 客户端（PROPFIND + GET）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMB / CIFS（支持 SMB2/3，兼容旧 SMB1）
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // 内嵌 HTTP 服务（手机扫码配置页）
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // 关键：Ktor 2.3 的 ApplicationEngineEnvironmentReloading 内部用到 java.nio.file
    // （SimpleFileVisitor / WatchService），而 java.nio.file 是 Android 8.0 (API 26) 才有的。
    // 电视盒子常见 Android 7.1 (API 25)，不脱糖就会 NoClassDefFoundError 直接闪退。
    // 用 nio 变体把 java.nio.file 一起脱糖进包里，老设备才能跑起配置服务。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}

// AGP 会自动再塞一份非 nio 的 desugar_jdk_libs，两套配置同时进 L8 会互相打架
// （表现为 "Use JsonReader.setLenient(true) to accept malformed JSON"），这里只保留 nio 变体。
configurations.matching { it.name == "coreLibraryDesugaring" }.configureEach {
    exclude(group = "com.android.tools", module = "desugar_jdk_libs")
    exclude(group = "com.android.tools", module = "desugar_jdk_libs_configuration")
}
