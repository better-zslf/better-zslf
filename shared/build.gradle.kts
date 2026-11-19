plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.betterzslf.application"
version = "0.1.0-SNAPSHOT"

// The public build contains shared capabilities and the Standard product only.
val betterZslfEdition = providers.gradleProperty("betterZslfEdition").orElse("standard").get()
require(betterZslfEdition == "standard") {
    "This public build is Standard-only. Pro sources are owned by the pro-ci product build."
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
            implementation(project(":core:text"))
            api(project(":core:run-model"))
            api(project(":core:logging"))
            api(project(":core:storage"))
            api(project(":core:network-session"))
            api(project(":design-system"))
            api(project(":feature:services:presentation"))
            implementation(project(":feature:services:ui"))
            api(project(":feature:run:api"))
            api(project(":feature:run:domain"))
            implementation(project(":feature:run:data"))
            implementation(project(":feature:run:presentation"))
            implementation(project(":feature:run:ui"))
            api(project(":platform-ui:readonly-map"))
            api(project(":feature:schedule:api"))
            api(project(":feature:schedule:domain"))
            implementation(project(":feature:schedule:data"))
            api(project(":feature:schedule:runtime"))
            implementation(project(":feature:schedule:ui"))
            api(project(":platform:schedule-native"))
            implementation(project(":feature:home:presentation"))
            implementation(project(":feature:home:ui"))
            api(project(":feature:update:domain"))
            implementation(project(":feature:update:data"))
            implementation(project(":feature:update:presentation"))
            implementation(project(":feature:update:ui"))
            implementation(project(":feature:accountrecovery:domain"))
            implementation(project(":feature:accountrecovery:data"))
            implementation(project(":feature:accountrecovery:presentation"))
            implementation(project(":feature:accountrecovery:ui"))
            api(project(":feature:campusnet:domain"))
            api(project(":feature:campusnet:data"))
            implementation(project(":feature:campusnet:presentation"))
            implementation(project(":feature:campusnet:ui"))
            api(project(":feature:auth:domain"))
            implementation(project(":feature:auth:data"))
            implementation(project(":feature:auth:presentation"))
            implementation(project(":feature:auth:ui"))
            api(project(":platform:location"))
            implementation(project(":feature:run:runtime"))
            implementation(project(":feature:profile:domain"))
            implementation(project(":feature:profile:data"))
            implementation(project(":feature:profile:presentation"))
            implementation(project(":feature:content:domain"))
            implementation(project(":feature:content:data"))
            implementation(project(":feature:content:presentation"))
            implementation(project(":platform:updates"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.coil.compose)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            api(libs.miuix.nav)
            implementation(libs.miuix.blur)
            implementation(libs.ktor.core)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.lifecycle.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies {
            implementation(project(":platform:storage"))
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
