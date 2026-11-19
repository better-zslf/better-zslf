plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:storage"))
            implementation(project(":core:text"))
            implementation(project(":core:logging"))
            api(libs.ktor.core)
            api(libs.serialization.json)
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
