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
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.betterzslf.desktop.MainKt"
    }
}
