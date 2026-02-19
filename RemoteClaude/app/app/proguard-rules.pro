# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.remoteclaude.app.**$$serializer { *; }
-keepclassmembers class com.remoteclaude.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.remoteclaude.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlin.reflect.jvm.internal.** { *; }

# --- OkHttp (Ktor engine) ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- ML Kit Barcode ---
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# --- CameraX ---
-keep class androidx.camera.** { *; }

# --- Compose ---
-dontwarn androidx.compose.**
