plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(libs.serialization.json) }
    }
}
