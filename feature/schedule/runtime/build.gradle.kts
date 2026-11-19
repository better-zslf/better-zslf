plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:schedule:domain"))
            api(project(":platform:schedule-native"))
            api(project(":core:storage"))
            api(libs.coroutines.core)
            implementation(project(":core:text"))
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies { implementation(libs.coroutines.test) }
    }
}
