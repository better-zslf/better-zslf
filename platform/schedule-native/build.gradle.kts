plugins { id("betterzslf.kmp-library") }

kotlin {
    android { androidResources { enable = true } }
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:schedule:domain"))
            api(libs.coroutines.core)
            implementation(project(":core:text"))
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies { implementation(project(":platform:storage")) }
    }
}
