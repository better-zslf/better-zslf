pluginManagement {
    includeBuild("build-logic")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:${requested.version}")
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "better-zslf"
include(":core:text", ":core:run-model", ":core:logging", ":core:storage", ":core:network-session", ":design-system")
include(":feature:services:presentation", ":feature:services:ui", ":feature:run:api")
include(":feature:run:domain", ":feature:run:data", ":feature:run:presentation", ":feature:run:ui")
include(":feature:run:runtime")
include(":feature:schedule:api", ":feature:schedule:domain", ":feature:schedule:data", ":feature:schedule:runtime", ":feature:schedule:ui")
include(":feature:home:presentation", ":feature:home:ui")
include(":feature:update:domain", ":feature:update:data", ":feature:update:presentation", ":feature:update:ui")
include(":feature:accountrecovery:domain", ":feature:accountrecovery:data", ":feature:accountrecovery:presentation", ":feature:accountrecovery:ui")
include(":feature:campusnet:domain", ":feature:campusnet:data", ":feature:campusnet:presentation", ":feature:campusnet:ui")
include(":feature:auth:domain", ":feature:auth:data", ":feature:auth:presentation", ":feature:auth:ui")
include(":feature:profile:domain", ":feature:profile:data", ":feature:profile:presentation")
include(":feature:content:domain", ":feature:content:data", ":feature:content:presentation")
include(":platform:updates", ":platform:schedule-native", ":platform:storage", ":platform:location", ":platform-ui:readonly-map")
include(":shared", ":androidApp", ":desktopApp", ":platform:android-host")
