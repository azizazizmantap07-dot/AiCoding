# AI Coder — R8 / ProGuard rules (release)

# ---- Kotlin / Serialization ----
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class com.kaneki.aicoder.**$$serializer { *; }
-keepclassmembers class com.kaneki.aicoder.** {
    *** Companion;
}
-if class com.kaneki.aicoder.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep all app models/DTOs used via reflection by kotlinx.serialization
-keep @kotlinx.serialization.Serializable class com.kaneki.aicoder.** { *; }

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---- Ktor ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# ---- Security crypto / Tink ----
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- Compose ----
-dontwarn androidx.compose.**
# Keep composition when minify enabled
-keep class androidx.compose.runtime.** { *; }

# ---- General Android ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Strip verbose logging in release (optional, safe)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
