plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // M550 (AGP 9): Android target via com.android.kotlin.multiplatform.library —
    // the old combination of com.android.library + kotlin.multiplatform in a single module
    // no longer works with the new AGP DSL. The Android configuration lives here, a top-level
    // android {} block does not exist in this plugin.
    android {
        namespace = "sk.tvhclient.shared"
        compileSdk = 36
        minSdk = 23
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        // unit tests (commonTest) run on the JVM as androidHostTest
        withHostTestBuilder {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.network)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.multiplatform.settings)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.androidx.security.crypto)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
