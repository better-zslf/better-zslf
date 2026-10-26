package io.github.betterzslf.edition

/** Omitted or newly introduced switches are closed. A Pro build alone grants nothing. */
data class EditionFeatureFlags(val water: Boolean = false, val routeEditors: Boolean = false)

/** Also checked at dispatch so restored/hand-constructed routes cannot bypass entry visibility. */
object EditionAccess {
    fun waterEnabled(isPro: Boolean, flags: EditionFeatureFlags): Boolean = isPro && flags.water
    fun routeEditorsEnabled(isPro: Boolean, flags: EditionFeatureFlags): Boolean = isPro && flags.routeEditors
    val waterEnabled: Boolean get() = waterEnabled(BuildEdition.isPro, BuildEdition.features)
    val routeEditorsEnabled: Boolean get() = routeEditorsEnabled(BuildEdition.isPro, BuildEdition.features)

    fun allowsPage(kind: String, isPro: Boolean, flags: EditionFeatureFlags): Boolean = when (kind) {
        "water" -> waterEnabled(isPro, flags)
        "runimport", "runmanual", "runplan", "runsupplement" -> routeEditorsEnabled(isPro, flags)
        else -> true
    }
    fun allowsPage(kind: String): Boolean = allowsPage(kind, BuildEdition.isPro, BuildEdition.features)
    fun allowsOfflinePage(kind: String): Boolean = kind in setOf("document", "resetpassword") ||
        (kind == "water" && waterEnabled)
}
