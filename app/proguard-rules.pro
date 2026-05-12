# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# secp256k1-kmp JNI
-keep class fr.acinq.secp256k1.** { *; }

# Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Coil
-dontwarn coil3.**

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# ExoPlayer / Media3
-dontwarn androidx.media3.**

# java.lang.management (not available on Android)
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Jackson Kotlin module (transitive via Quartz) uses kotlin-reflect to
# introspect stdlib types. R8 strips serialVersionUID from EmptyList /
# EmptyMap and metadata Jackson needs, causing crash on Amber NIP-55 login.
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.collections.** {
    private static final long serialVersionUID;
}
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# Strip verbose and debug logs in release builds. R8 evaluates these
# assumenosideeffects rules and removes the call sites entirely — string
# formatting, varargs allocation, and logcat I/O all vanish. Keeps Log.i,
# Log.w, Log.e for production observability.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
