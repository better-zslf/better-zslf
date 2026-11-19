plugins {
    id("betterzslf.compose-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:text"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.miuix.ui)
            implementation(libs.compose.animation)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.blur)
        }
    }
}
