package io.github.betterzslf.edition

/** Selected by betterZslfEdition, never changed by saved settings or server data. */
object BuildEdition {
    const val id: String = "pro"
    const val isPro: Boolean = true
    const val updateRepository: String = "better-zslf/pro-ci"
    const val androidApplicationId: String = "io.github.betterzslf.pro"
    const val versionNameSuffix: String = "-pro"
    val features: EditionFeatureFlags = EditionFeatureFlags(water = true, routeEditors = true)
}
