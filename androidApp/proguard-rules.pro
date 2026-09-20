# ============================================================================
# Headent Client — R8 / ProGuard keep rules for the release build
# ============================================================================

# --- kotlinx.serialization -------------------------------------------------
# The model classes in the shared module are @Serializable; the serializers are generated
# and accessed via reflection/companion. We keep them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# Our own @Serializable models (shared/model/*) are covered by the generic rules above:
# kotlinx.serialization is compile-time (field names are constants in $$serializer),
# so the classes themselves and their fields may be obfuscated. There is no polymorphic
# or reflective use in the shared module -> a blanket keep is not needed.
# To be safe we keep only the names of the @Serializable classes (a more readable mapping/diag log).
-keepnames @kotlinx.serialization.Serializable class sk.tvhclient.shared.**

# --- libVLC (org.videolan) -------------------------------------------------
# It uses JNI native callbacks — classes/methods referenced from native code
# must not be renamed or removed.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# --- Ktor + okhttp engine --------------------------------------------------
# No blanket keeps: both Ktor and okhttp carry their own consumer keep rules
# in the AAR/JAR (META-INF/proguard), and R8 applies them automatically. Our code calls
# Ktor only directly (no reflection), so R8 can safely drop the rest.
# We create the engine explicitly with HttpClient(OkHttp); we keep the ServiceLoader lookup
# only to be safe (one small class).
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
# Ktor uses kotlinx-atomicfu -> AtomicFieldUpdater looks up volatile fields
# by name; they must not be renamed (Ktor's recommended rule for R8).
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembernames class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- kotlinx.coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- androidx.security.crypto / Tink ---------------------------------------
# Tink (EncryptedSharedPreferences) references compile-only errorprone and
# javax annotations that are not on the runtime classpath -> R8 reports them as missing.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.**
# No blanket keep for Tink (a large library, we used to keep all of it). The only thing
# Tink needs under R8 is protobuf-lite: GeneratedMessageLite looks up message
# fields by name via reflection -> the subclasses' fields must not be renamed.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.crypto.tink.**

# --- Kotlin metadata / reflection ------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- Enums (serialized as enum) --------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Compose (R8-friendly, just to be safe with tooling) -------------------
-dontwarn androidx.compose.**
