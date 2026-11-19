plugins {
    id("betterzslf.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:text"))
            api(libs.serialization.json)
        }
    }
}
