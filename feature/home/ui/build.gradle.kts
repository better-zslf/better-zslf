plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:home:presentation"))
            implementation(project(":design-system"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
        }
    }
}
