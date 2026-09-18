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

# No Bouncy Castle implementation in releaseRuntimeClasspath. OkHttp's optional
# provider references below need warning suppression, not a blanket keep rule.

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
# Quartz keeps its Jackson-reflected protocol models in its consumer rules.
# kotlin-reflect supplies Metadata retention. Preserve the concrete stdlib
# singleton fields required by Jackson rather than all Jackson implementations.
-keepclassmembers class kotlin.collections.EmptyList {
    private static final long serialVersionUID;
}
-keepclassmembers class kotlin.collections.EmptyMap {
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
