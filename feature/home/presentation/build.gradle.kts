plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:schedule:api"))
            implementation(project(":feature:schedule:domain"))
            implementation(project(":core:text"))
            implementation(libs.kotlinx.datetime)
        }
    }
}
