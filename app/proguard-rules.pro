# ============================================================
# AI录音 — R8 / ProGuard 规则
#
# 已在 build.gradle.kts release buildType 中接线：
#   optimization { enable = true }
#   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
#
# 原则：OkHttp / Retrofit / Coil / Compose / DataStore / Coroutines /
# kotlinx.serialization 运行时均自带 consumer rules（META-INF/proguard），
# 此处只保留本项目确实需要的补充规则，不堆砌通用模板。
# ============================================================

# ------------------------------------------------------------
# JSch（com.github.mwiede:jsch，SFTP 上传）
# JSch 通过 Class.forName 按配置字符串反射加载算法实现类，
# 且未自带 consumer rules，必须整体保留。
# ------------------------------------------------------------
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ------------------------------------------------------------
# kotlinx.serialization（项目自身 @Serializable DTO）
# 运行时自带规则覆盖库内部；此处按官方建议为项目 DTO 补充
# $$serializer / Companion / serializer() 保留，防 R8 full mode 下反射查找失败。
# ------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.example.ai_recorder_android.**$$serializer { *; }
-keepclassmembers class com.example.ai_recorder_android.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.ai_recorder_android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------
# Room（KSP 生成代码直接引用实体构造器，自带 consumer rules；
# 仅防御性保留实体类与 Database 子类，避免混淆破坏表名/列名诊断信息。）
# ------------------------------------------------------------
-keep @androidx.room.Entity class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
