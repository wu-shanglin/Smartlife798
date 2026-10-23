# ---------------------------------------------------------------------------
# R8 / ProGuard rules for the release build.
#
# The release build keeps `isMinifyEnabled = false` today (see docs/06-工程规范与路线图.md
# "版本与发布"), so these rules are only exercised once minification is
# switched on. They are kept here so that switch stays a one-line change.
# ---------------------------------------------------------------------------

# ---- Gson: every API model is deserialized reflectively -------------------
# Fields are matched by name / @SerializedName, and generic type tokens are
# looked up by signature, so model classes and their generic signatures must
# survive shrinking.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

-keep class com.smartlife798.app.core.** { *; }

# Gson internals used through reflection.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- OkHttp / Okio --------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---- Alipay SDK -----------------------------------------------------------
# The SDK ships its own consumer rules for the payment activity; these keep the
# entry points it resolves by name at runtime.
-keep class com.alipay.** { *; }
-dontwarn com.alipay.**

# ---- ML Kit / CameraX ----------------------------------------------------
-dontwarn com.google.mlkit.**
-keep class com.google.mlkit.** { *; }
-dontwarn androidx.camera.**

# ---- Kotlin metadata / coroutines ---------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
