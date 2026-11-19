plugins {
    id("betterzslf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:run-model"))
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
    }
}
