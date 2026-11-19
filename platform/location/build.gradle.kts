plugins { id("betterzslf.compose-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:run-model"))
            implementation(project(":core:text"))
            implementation(libs.compose.runtime)
            implementation(libs.coroutines.core)
        }
        androidMain.dependencies {
            // The permission requester actual drives the activity result launcher and LocalContext.
            implementation(libs.activity.compose)
            implementation(libs.compose.ui)
        }
        iosMain.dependencies {
            // LocalUIViewController for the iOS permission requester actual.
            implementation(libs.compose.ui)
        }
    }
}
