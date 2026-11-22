plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "io.github.betterzslf.desktop"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            // AppGraph builds CoroutineScope(Dispatchers.Main.immediate); on desktop the Main
            // dispatcher is provided by the Swing module (same version as kotlinx-coroutines-core).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            // Mock-data preview mode serves canned payloads from desktopApp resources.
            implementation("io.ktor:ktor-client-mock:3.4.3")
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.betterzslf.desktop.MainKt"
        // -PmockData=true swaps the preview to canned offline payloads (default: real network).
        jvmArgs += "-Dbetterzslf.preview.mock=${providers.gradleProperty("mockData").getOrElse("false")}"
    }
}
