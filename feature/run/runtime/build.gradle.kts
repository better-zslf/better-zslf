plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:run-model"))
            api(project(":platform:location"))
            api(project(":feature:run:data"))
            implementation(project(":core:network-session"))
            implementation(project(":core:storage"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            // The moved queue/worker/hold suites drive suspensions through runTest; the moved
            // write-transport contract drives the real client through the mock engine.
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
    }
}
