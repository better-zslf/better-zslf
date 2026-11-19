plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:profile:domain"))
            implementation(project(":core:network-session"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            // The controller suite carries a local session fixture copy.
            implementation(project(":core:storage"))
            implementation(libs.ktor.core)
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
