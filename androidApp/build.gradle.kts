import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
require(providers.gradleProperty("betterZslfEdition").orNull.let { it == null || it == "standard" }) {
    "The public Android product is Standard-only. Remove betterZslfEdition; build the private product from its own root."
}
val baseName = requireNotNull(appVersion.getProperty("VERSION_NAME")) { "VERSION_NAME is required" }
require(Regex("""(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)""").matches(baseName)) { "VERSION_NAME must be numeric x.y.z" }
val prerelease = appVersion.getProperty("VERSION_PRERELEASE")
require(prerelease == null || Regex("rc[1-9][0-9]{0,5}").matches(prerelease)) { "VERSION_PRERELEASE must be rc followed by 1..6 digits without leading zero" }
val expectedName = baseName + (prerelease?.let { "-$it" } ?: "")
val resolvedName = providers.gradleProperty("versionName").getOrElse(expectedName)
require(resolvedName == expectedName) { "versionName must match the Standard version.properties" }
val expectedCode = requireNotNull(appVersion.getProperty("VERSION_CODE")) { "VERSION_CODE is required" }
require(Regex("[1-9][0-9]{0,9}").matches(expectedCode) && expectedCode.toLong() <= 2_100_000_000L) { "VERSION_CODE must be a canonical positive Android version code" }
val resolvedCode = providers.gradleProperty("versionCode").getOrElse(expectedCode)
require(resolvedCode == expectedCode) { "versionCode must match the Standard version.properties" }

android {
    namespace = "io.github.betterzslf"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.betterzslf"
        minSdk = 33
        targetSdk = 36
        versionCode = resolvedCode.toInt()
        versionName = resolvedName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APP_VERSION", "\"$resolvedName\"")
    }
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
        debug {
            // Isolated synthetic acceptance install; never alters normal debug or release identity.
            if (providers.gradleProperty("scheduleProbe").orNull == "true") applicationIdSuffix = ".scheduleprobe"
        }
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
    implementation(project(":platform:android-host"))
    implementation(libs.activity.compose)
    testImplementation(kotlin("test"))
    debugImplementation(libs.compose.ui)
    debugImplementation(libs.compose.runtime)
    debugImplementation(libs.coroutines.core)
    debugImplementation(libs.ktor.core)
    debugImplementation(libs.ktor.mock)
    debugImplementation(libs.serialization.json)
}
