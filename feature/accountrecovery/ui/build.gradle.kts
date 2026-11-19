plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:accountrecovery:presentation"))
            implementation(project(":design-system"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.miuix.ui)
        }
        androidHostTest.dependencies {
            // Match the Android product test runtime; do not add platform hosts to reusable content.
            implementation(libs.androidx.activity.ktx)
            implementation(libs.androidx.core.ktx)
        }
    }
}
