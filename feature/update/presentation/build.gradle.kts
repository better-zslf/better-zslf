plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:update:domain"))
            implementation(project(":core:text"))
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies { implementation(libs.coroutines.test) }
    }
}
