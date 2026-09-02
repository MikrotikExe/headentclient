# ============================================================================
# Headent Client — R8 / ProGuard keep pravidla pre release build
# ============================================================================

# --- kotlinx.serialization -------------------------------------------------
# Modelove triedy v shared module su @Serializable; serializery sa generuju
# a pristupuju cez reflexiu/companion. Drzime ich.
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

# Nase @Serializable modely (shared/model/*) pokryvaju genericke pravidla vyssie:
# kotlinx.serialization je compile-time (nazvy poli su konstanty v $$serializer),
# takze samotne triedy a ich polia mozu byt obfuskovane. Ziadne polymorfne
# ani reflexne pouzitie v shared module nie je -> blanket keep nie je potrebny.
# Pre istotu drzime len mena @Serializable tried (citatelnejsi mapping/diag log).
-keepnames @kotlinx.serialization.Serializable class sk.tvhclient.shared.**

# --- libVLC (org.videolan) -------------------------------------------------
# Pouziva JNI native callbacky — triedy/metody referencovane z native kodu
# sa nesmu prejmenovat ani odstranit.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# --- Ktor + okhttp engine --------------------------------------------------
# Ziadne blanket keep: Ktor aj okhttp si nesu vlastne consumer keep pravidla
# v AAR/JAR (META-INF/proguard), R8 ich aplikuje automaticky. Nas kod vola
# Ktor len priamo (bez reflexie), takze R8 moze zvysok bezpecne zahodit.
# Engine vytvarame explicitne HttpClient(OkHttp), ServiceLoader lookup drzime
# len pre istotu (jedna mala trieda).
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }
# Ktor pouziva kotlinx-atomicfu -> AtomicFieldUpdater hlada volatile polia
# podla mena; nesmu sa premenovat (odporucane pravidlo Ktor pre R8).
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembernames class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- kotlinx.coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- androidx.security.crypto / Tink ---------------------------------------
# Tink (EncryptedSharedPreferences) referencuje compile-only errorprone a
# javax anotacie, ktore nie su v runtime classpath -> R8 ich hlasi ako chybajuce.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.**
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Kotlin metadata / reflexia --------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- Enums (serializovane ako enum) ----------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Compose (R8-friendly, len pre istotu pri tooling) ---------------------
-dontwarn androidx.compose.**
