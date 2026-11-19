plugins { id("betterzslf.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":feature:schedule:api"))
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies { implementation(libs.serialization.json) }
    }
}
