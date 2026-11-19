plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:schedule:domain"))
            api(libs.ktor.core)
            implementation(libs.coroutines.core)
            implementation(libs.ksoup)
        }
        commonTest.dependencies {
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        jvmTest.dependencies { implementation(libs.ktor.cio) }
    }
}
