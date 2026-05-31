# =========================================================================
# R8 / ProGuard keep rules
#
# Release builds enable code + resource shrinking. The rules below protect
# everything that is reached via reflection, JNI, data-binding or the worker
# factory, none of which R8 can see statically. Trimming happens only on
# genuinely unused code, so app behaviour/quality is unchanged.
# =========================================================================

# ---- existing warnings suppression ----
-dontwarn org.immutables.value.Value$Default
-dontwarn org.immutables.value.Value$Immutable
-dontwarn org.immutables.value.Value$Style$BuilderVisibility
-dontwarn org.immutables.value.Value$Style$ImplementationVisibility
-dontwarn org.immutables.value.Value$Style

# ---- yt-dlp / youtubedl-android (reads classes & assets reflectively) ----
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-keep class com.yausername.aria2c.** { *; }
-keep class org.immutables.** { *; }

# ---- FFmpegKit (JNI bridge) ----
-keep class com.antonkarpenko.ffmpegkit.** { *; }
-keep class com.arthenica.** { *; }

# ---- Native bridge: anything with native methods + our V2Ray JNI object ----
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.myAllVideoBrowser.v2ray.** { *; }
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# ---- WorkManager workers (instantiated by name via the worker factory) ----
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.myAllVideoBrowser.util.downloaders.** { *; }

# ---- Room / entities & data models (Gson + Room use reflection) ----
-keep class com.myAllVideoBrowser.data.local.room.entity.** { *; }
-keep class com.myAllVideoBrowser.data.local.model.** { *; }
-keepclassmembers class com.myAllVideoBrowser.data.** {
    <init>(...);
    <fields>;
}

# ---- Gson: keep generic signatures & @SerializedName/@Expose fields ----
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
-dontwarn sun.misc.**

# ---- Data Binding generated classes ----
-keep class com.myAllVideoBrowser.databinding.** { *; }
-keep class androidx.databinding.** { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }

# ---- ViewModels (constructed via the DI ViewModel factory / reflection) ----
-keep class * extends androidx.lifecycle.ViewModel { *; }

# ---- OkHttp / Okio / Retrofit (well-known safe suppressions) ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Keep line numbers for readable crash reports, hide source file name ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
