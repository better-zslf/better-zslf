plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:content:domain"))
            implementation(project(":core:network-session"))
        }
    }
}
