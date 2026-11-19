plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:profile:domain"))
            implementation(project(":core:network-session"))
            implementation(project(":core:text"))
            implementation(libs.ktor.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            // Local ProfileApiFixture + session/header copies drive the real client.
            implementation(project(":core:storage"))
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
