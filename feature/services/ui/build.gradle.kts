plugins {
    id("betterzslf.compose-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:services:presentation"))
            implementation(project(":design-system"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
        }
    }
}
