# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
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

# Jackson reflects on TypeReference's generic superclass. Full-mode R8 needs
# the generic base kept as well as Signature; keeping the attribute alone can
# leave a raw superclass signature with no type arguments.
-keep,allowobfuscation class com.fasterxml.jackson.core.type.TypeReference { *; }

# secp256k1-kmp JNI
-keep class fr.acinq.secp256k1.** { *; }

# Quartz 1.08.0 consumer rules, excluding its app-wide keepnames directive.
# Recheck these compatibility rules when upgrading Quartz. Preserve native
# resource lookup, scrypt, enum and Jackson protocol-model behavior.
-keepdirectories libs
-keep enum ** { *; }
-keep class com.lambdaworks.codec.** { *; }
-keep class com.lambdaworks.crypto.** { *; }
-keep class com.lambdaworks.jni.** { *; }
-keep class com.vitorpamplona.quartz.** { *; }

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
# The Quartz compatibility rules above keep its Jackson-reflected protocol models.
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
