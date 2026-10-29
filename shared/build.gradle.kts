plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// One edition per compilation, including native/framework and host-test targets.
// This is source selection, not a runtime feature flag or Android-only flavor.
val betterZslfEdition = providers.gradleProperty("betterZslfEdition").orElse("pro").get()
require(betterZslfEdition in setOf("standard", "pro")) {
    "betterZslfEdition must be standard or pro (was '$betterZslfEdition')"
}
val isProEdition = betterZslfEdition == "pro"
val proOnlyMainSources = listOf(
    "**/feature/water/**",
    "**/feature/waterui/**",
    "**/ui/water/**",
    "**/platform/BluetoothAccess*.kt",
    "**/feature/run/RunRouteParser.kt",
    "**/feature/run/RunRouteValidation.kt",
    "**/feature/run/RunDetourPlanner.kt",
    "**/feature/runui/RunImportController.kt",
    "**/feature/runui/RunManualDraft.kt",
    "**/ui/run/RunImportPage.kt",
    "**/ui/run/RunManualDraftSession.kt",
    "**/ui/run/RunManualDraftSheet.kt",
)
val proOnlyTestSources = listOf(
    "**/feature/water/**",
    "**/feature/waterui/**",
    "**/feature/run/RunGeometryAndImportTest.kt",
    "**/feature/run/RunRouteAndTrackTest.kt",
    "**/feature/runui/RunImportControllerTest.kt",
    "**/feature/runui/RunManualDraftTest.kt",
    "**/feature/runui/RunManualImportControllerTest.kt",
    "**/ui/run/RunManualDraftSessionTest.kt",
)

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
        // Shared contracts have exactly one selected implementation. Unselected directories are
        // never compiler inputs; existing Pro source remains in the repository for the Pro build.
        commonMain {
            kotlin.srcDir("src/${betterZslfEdition}Main/kotlin")
        }
        commonTest {
            kotlin.srcDir("src/${betterZslfEdition}Test/kotlin")
        }
        all {
            if (!isProEdition) {
                kotlin.exclude(proOnlyMainSources)
                kotlin.exclude(proOnlyTestSources)
            }
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.nav)
            implementation(libs.miuix.blur)
            implementation(libs.ktor.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ksoup)
            if (isProEdition) implementation(libs.kable)
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
