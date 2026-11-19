plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network-session"))
            implementation(project(":core:text"))
            implementation(libs.serialization.json)
        }
    }
}
