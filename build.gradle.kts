plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp) apply false
}

// Gradle silently substitutes equal group/name components even for explicit project dependencies.
// Keep every nested leaf module distinct before any compiler resolves the combined product graph.
val verifyModuleCoordinates = tasks.register("verifyModuleCoordinates") {
    group = "verification"
    doLast {
        val libraries = allprojects.filter {
            it.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") || it.plugins.hasPlugin("com.android.library")
        }
        require(libraries.all { it.group.toString() != "unspecified" && it.version.toString() != "unspecified" }) {
            "Every reusable module needs an explicit component identity"
        }
        val duplicates = libraries.groupBy { "${it.group}:${it.name}" }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) { "Duplicate module components: ${duplicates.mapValues { (_, projects) -> projects.map { it.path } }}" }
        libraries.sortedBy { it.path }.forEach { println("${it.path} = ${it.group}:${it.name}:${it.version}") }
    }
}
subprojects {
    tasks.configureEach {
        if (name.startsWith("compile") || name.startsWith("link") || name.startsWith("assemble") ||
            name.startsWith("bundle") || name.endsWith("Test")) dependsOn(verifyModuleCoordinates)
    }
}
