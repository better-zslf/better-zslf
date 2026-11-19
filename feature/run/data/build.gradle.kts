plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:run:domain"))
            api(project(":core:network-session"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            // Original protocol/paging cases cross the real typed adapter, never a main reverse edge.
            implementation(project(":feature:run:presentation"))
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
