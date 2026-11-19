plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:run:presentation"))
            implementation(project(":design-system"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
        }
        androidHostTest.dependencies {
            // Align the existing product test runtime; reusable content owns no Activity.
            implementation(libs.androidx.activity.ktx)
            implementation(libs.androidx.core.ktx)
        }
    }
}
