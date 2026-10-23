import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val resolvedName = providers.gradleProperty("versionName").getOrElse(appVersion.getProperty("VERSION_NAME"))
val resolvedCode = providers.gradleProperty("versionCode").getOrElse(appVersion.getProperty("VERSION_CODE")).toInt()

android {
    namespace = "io.github.betterzslf"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "io.github.betterzslf"
        minSdk = 33
        targetSdk = 36
        versionCode = resolvedCode
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
