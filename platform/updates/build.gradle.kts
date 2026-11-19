plugins { id("betterzslf.kmp-library") }

kotlin {
    android { androidResources { enable = true } }
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:update:domain"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
            // Match the product's AndroidX graph without importing Compose into this capability.
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.androidx.annotation)
        }
    }
}
