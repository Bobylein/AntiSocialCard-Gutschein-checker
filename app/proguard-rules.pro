# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# ========== Hilt Dependency Injection ==========
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keepclasseswithmembers class * {
    @dagger.hilt.* <fields>;
}

# ========== ONNX Runtime ==========
# Note: ONNX is mentioned in task requirements but not currently used in the project
# Keeping these rules for future use
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ========== Kotlin Coroutines ==========
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.** { *; }
-dontwarn kotlinx.coroutines.**

# ========== ML Kit ==========
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.android.gms.vision.**

# ========== WebView JavaScript Interface ==========
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, java.lang.String);
}

# ========== Data/Model Classes ==========
-keep class com.antisocial.giftcardchecker.model.** { *; }
-keepclassmembers class com.antisocial.giftcardchecker.model.** {
    *;
}

# ========== Market Implementations ==========
-keep class com.antisocial.giftcardchecker.markets.** { *; }
-keepclassmembers class com.antisocial.giftcardchecker.markets.** {
    *;
}

# ========== Parcelable ==========
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ========== Serializable ==========
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ========== AndroidX and Lifecycle ==========
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**
-keep class androidx.activity.** { *; }

# ========== CameraX ==========
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ========== OkHttp ==========
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ========== General Android ==========
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ========== Enum Classes ==========
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ========== R8 Full Mode Optimizations ==========
# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}



