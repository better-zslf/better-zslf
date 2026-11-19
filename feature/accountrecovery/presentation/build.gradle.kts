plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:accountrecovery:domain"))
            api(libs.coroutines.core)
            implementation(project(":core:text"))
        }
        commonTest.dependencies { implementation(libs.coroutines.test) }
    }
}
