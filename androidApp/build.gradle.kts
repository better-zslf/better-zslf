import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val edition = providers.gradleProperty("betterZslfEdition").getOrElse("pro")
require(edition in setOf("standard", "pro")) { "betterZslfEdition must be standard or pro" }
val isProEdition = edition == "pro"
val baseName = requireNotNull(appVersion.getProperty("VERSION_NAME")) { "VERSION_NAME is required" }
require(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)").matches(baseName)) { "VERSION_NAME must be numeric x.y.z" }
val prerelease = appVersion.getProperty("VERSION_PRERELEASE")
require(prerelease == null || Regex("rc[1-9][0-9]{0,5}").matches(prerelease)) { "VERSION_PRERELEASE must be rc followed by 1..6 digits without leading zero" }
val expectedName = baseName + (prerelease?.let { "-$it" } ?: "") + if (isProEdition) "-pro" else ""
val resolvedName = providers.gradleProperty("versionName").getOrElse(expectedName)
require(resolvedName == expectedName) { "versionName must match version.properties and the selected edition" }
val resolvedCode = providers.gradleProperty("versionCode").getOrElse(appVersion.getProperty("VERSION_CODE")).toInt()
require(resolvedCode in 1..2_100_000_000) { "versionCode is out of range" }

android {
    namespace = "io.github.betterzslf"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.betterzslf" + if (isProEdition) ".pro" else ""
        manifestPlaceholders["appLabel"] = if (isProEdition) "better-zslf Pro" else "better-zslf"
        minSdk = 33
        targetSdk = 36
        versionCode = resolvedCode
        versionName = resolvedName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_VERSION", "\"$resolvedName\"")
        buildConfigField("String", "APP_EDITION", "\"$edition\"")
        buildConfigField("boolean", "IS_PRO", isProEdition.toString())
    }
    // The Pro permission overlay is not even a standard-edition manifest input.
    if (isProEdition) sourceSets.getByName("main").manifest.srcFile("src/pro/AndroidManifest.xml")
    val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    if (signingPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    testImplementation(kotlin("test"))
    debugImplementation(libs.compose.ui)
    debugImplementation(libs.compose.runtime)
    debugImplementation(libs.coroutines.core)
}
