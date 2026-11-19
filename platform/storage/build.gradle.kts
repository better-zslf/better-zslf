plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { api(project(":core:storage")) }
        androidMain.dependencies { implementation(project(":core:text")) }
    }
}
