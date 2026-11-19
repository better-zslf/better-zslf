plugins {
    id("betterzslf.compose-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:text"))
            api(project(":core:run-model"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.miuix.ui)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies { implementation(libs.coroutines.core) }
    }
}
