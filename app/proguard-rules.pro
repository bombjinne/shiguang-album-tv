# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.shiguang.album.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.shiguang.album.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# jcifs-ng
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# Ktor / 日志
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
