plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:accountrecovery:domain"))
            api(project(":core:network-session"))
        }
        commonTest.dependencies {
            implementation(libs.ktor.mock)
            implementation(libs.coroutines.test)
        }
    }
}
