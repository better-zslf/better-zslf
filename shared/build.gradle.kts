plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "io.github.betterzslf.shared"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 33
        androidResources { enable = true }
        withHostTest {}
    }
    // JVM is a host test target, not a distributed desktop application.
    jvm()
    iosArm64()
    iosSimulatorArm64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.nav)
            implementation(libs.miuix.blur)
            implementation(libs.ktor.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kable)
            implementation(libs.lifecycle.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation(libs.activity.compose)
        }
        iosMain.dependencies {
            implementation(libs.ktor.darwin)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.cio)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "384m"
}
