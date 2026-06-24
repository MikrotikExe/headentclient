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
    compileSdk = 35

    // NDK je potrebne, aby AGP vedel vytiahnut natívne debug symboly z libVLC
    // .so (objcopy). Verzia musi byt v CI nainstalovana (viz workflow).
    ndkVersion = "27.0.12077973"

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
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.0"
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
    splits {
        abi {
            isEnable = true
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
            // Natívne debug symboly libVLC do App Bundle. libVLC .so su
            // prebuildene a strippnute (bez DWARF), takze FULL by nemal co
            // vytiahnut -> pouzivame SYMBOL_TABLE (tabulka symbolov), co Play
            // staci na zrusenie upozornenia o chybajucich symboloch.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
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
