plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:campusnet:presentation"))
            implementation(project(":design-system"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
        }
        androidHostTest.dependencies {
            // Match the Android product test runtime; reusable content owns no Activity.
            implementation(libs.androidx.activity.ktx)
            implementation(libs.androidx.core.ktx)
        }
    }
}
