plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":design-system"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.icons)
        }
        androidHostTest.dependencies {
            // Align the existing product test runtime; reusable content owns no Activity.
            implementation(libs.androidx.activity.ktx)
            implementation(libs.androidx.core.ktx)
        }
    }
}
