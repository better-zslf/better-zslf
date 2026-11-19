plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:content:domain"))
            implementation(project(":core:network-session"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            // The controller suite drives suspensions through runTest.
            implementation(libs.coroutines.test)
        }
    }
}
