plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:auth:domain"))
            api(project(":core:network-session"))
        }
        commonTest.dependencies {
            // Original login wire semantics cross the real core protocol, never a main reverse edge.
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
