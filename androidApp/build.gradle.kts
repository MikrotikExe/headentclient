import java.io.FileInputStream
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "sk.tvhclient.android"
    compileSdk = 36

    // M374: ndkVersion odstranena — NDK bol potrebny len na extrakciu
    // natívnych debug symbolov (vypnute nizsie), inak ho build nepotrebuje.

    // Podpis pre Play: kluc sa cita z keystore.properties (nie je v gite).
    // Ak subor chyba (napr. CI debug build), release sa proste nepodpise.
    val keystoreProps = Properties()
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) {
        keystoreProps.load(FileInputStream(keystorePropsFile))
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "sk.tvhclient"
        minSdk = 23
        targetSdk = 36
        versionCode = 39
        versionName = "1.0.1"
        buildConfigField(
            "String",
            "BUILD_DATE",
            "\"" + SimpleDateFormat("dd.MM.yyyy").format(Date()) + "\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // libVLC bundluje natívne .so pre vsetky ABI (~200MB). Rozdelime APK
    // podla ABI a zahodime x86/x86_64 (len emulator). Vysledok: samostatne
    // mensie APK pre kazde realne zariadenie (~50-60MB namiesto ~200MB).
    // ABI splits len pre APK build (assemble*), NIE pre bundle (AAB). AGP 8.9+
    // pri bundleRelease so zapnutym splits padne ("Sequence contains more than one
    // matching element"). Pri AAB sa splits aj tak ignoruje — Play si rozdeli ABI
    // sam z App Bundle. Podmienka podla nazvu gradle ulohy.
    val buildingBundle = gradle.startParameter.taskNames.any {
        it.contains("bundle", ignoreCase = true)
    }
    splits {
        abi {
            isEnable = !buildingBundle
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // M400: natívne debug symboly ZAPNUTÉ (návrat z M374) — pribalia sa
            // do AAB a Play prestane hlásiť upozornenie; natívne pády (libVLC)
            // budú v Console čitateľné. Cena: dlhší CI build (extrakcia symbolov
            // z ~200MB .so kniznic).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // Play: vlastny kluc ak je keystore.properties; inak (CI test) debug
            // podpis, nech je release APK instalovatelny na otestovanie R8.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    // Vynuti modernu verziu androidx.fragment. Stara 1.0.0 sa tahá tranzitivne (cez ine
    // kniznice) a Play ju hlasi ako zastaralu ("Technicka kvalita"). Appka fragmenty priamo
    // nepouziva (je cela v Compose), takze toto len povysi verziu a uspokoji hlasenie, bez
    // dosahu na kod. Constraint nepridava zavislost, len obmedzuje verziu ak je tahaná.
    constraints {
        implementation("androidx.fragment:fragment:1.8.6") {
            because("Stara tranzitivna 1.0.0 je zastarala; vynutit verziu kompatibilnu so SDK 35")
        }
    }
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.libvlc.all)
}
