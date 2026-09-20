import java.io.FileInputStream
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    // M550 (AGP 9): Kotlin is built into AGP, the org.jetbrains.kotlin.android plugin
    // is no longer applied (it is incompatible with the new DSL).
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "sk.tvhclient.android"
    compileSdk = 36

    // M374: ndkVersion removed — the NDK was needed only for extracting
    // native debug symbols (disabled below), otherwise the build does not need it.

    // Signing for Play: the key is read from keystore.properties (which is not in git).
    // If the file is missing (e.g. a CI debug build), the release simply is not signed.
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

    val buildingBundle = gradle.startParameter.taskNames.any {
        it.contains("bundle", ignoreCase = true)
    }
    // M424-fix3: the universal APK is built SEPARATELY, only on demand:
    //   gradle :androidApp:assembleRelease -PuniversalApk
    // Then the splits are disabled and a single APK with both ARM ABIs is produced (androidApp-release.apk).
    // A regular build (push, debugging) builds only the splits — the universal one does not hold CI up.
    val universalRequested = project.hasProperty("universalApk")
    // M574: build for the Android Studio emulator (x86_64 Android TV / Google TV image):
    //   gradle :androidApp:assembleDebug -PemulatorAbi
    // The regular splits contain only ARM, so the APK could not be installed on the emulator.
    // libVLC also ships x86_64 libraries, it is enough to let them into the split.
    val emulatorRequested = project.hasProperty("emulatorAbi")

    defaultConfig {
        applicationId = "sk.tvhclient"
        minSdk = 23
        targetSdk = 36
        // M424-fix4: ABI filter ONLY for the universal build. With splits
        // enabled the ABIs are already restricted by include() in the splits block, and AGP rejects the
        // ndk.abiFilters + splits combination ("Conflicting configuration").
        // The universal build has splits disabled and without the filter it would take all
        // ABIs from libVLC including x86/x86_64 (212 MB instead of ~104 MB).
        // The AAB for Play has no filter — Play splits the ABIs itself.
        if (universalRequested && !buildingBundle) {
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
        versionCode = 51
        versionName = "1.0.7"
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

    // libVLC bundles native .so files for all ABIs (~200MB). We split the APK
    // by ABI and drop x86/x86_64 (emulator only). The result: separate
    // smaller APKs for each real device (~50-60MB instead of ~200MB).
    // ABI splits only for the APK build (assemble*), NOT for the bundle (AAB). AGP 8.9+
    // fails on bundleRelease with splits enabled ("Sequence contains more than one
    // matching element"). For an AAB splits is ignored anyway — Play splits the ABIs
    // itself from the App Bundle. The condition is based on the gradle task name.
    splits {
        abi {
            isEnable = !buildingBundle && !universalRequested
            reset()
            if (emulatorRequested) include("armeabi-v7a", "arm64-v8a", "x86_64")
            else include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // M550: kotlinOptions {} has been removed in AGP 9; jvmTarget is taken
    // from compileOptions.targetCompatibility (17).

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // M400: native debug symbols ENABLED (a revert of M374) — they are bundled
            // into the AAB and Play stops reporting the warning; native crashes (libVLC)
            // will be readable in the Console. The cost: a longer CI build (extracting symbols
            // from ~200MB of .so libraries).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            // Play: our own key if keystore.properties is present; otherwise (CI test) a debug
            // signature, so the release APK is installable for testing R8.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // M680 (PR #16, jpstotz): the debug build installs ALONGSIDE the Play version —
        // a different application id (.debug), a different launcher name (src/debug/AndroidManifest.xml)
        // and a banner with a „DEBUG BUILD" ribbon (src/debug/res/drawable-nodpi/tv_banner.png).
        // The data is therefore separate: in the debug app you have to add the server again.
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    // Forces a modern version of androidx.fragment. The old 1.0.0 is pulled in transitively (through other
    // libraries) and Play reports it as outdated ("Technical quality"). The app does not use fragments
    // directly (it is entirely in Compose), so this merely upgrades the version and satisfies the report, without
    // any impact on the code. The constraint does not add a dependency, it only limits the version if it is pulled in.
    constraints {
        implementation("androidx.fragment:fragment:1.8.6") {
            because("The old transitive 1.0.0 is outdated; force a version compatible with SDK 35")
        }
    }
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.tvprovider)   // M580: favourites row on the Android TV home screen
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
