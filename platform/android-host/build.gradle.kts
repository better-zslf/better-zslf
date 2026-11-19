plugins {
    alias(libs.plugins.android.library)
}

group = "io.github.betterzslf"
version = "0.1.0-SNAPSHOT"

// This Android-only host keeps the remaining public location declaration and product-neutral theme
// and security resources. Schedule and updater capabilities own their own components and resources.
android {
    namespace = "io.github.betterzslf.android.host"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(project(":shared"))
}
