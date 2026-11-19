package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText

enum class CampusPortalPhase { NotSignedIn, Offline, Connecting, Online, Failed, NotOnCampus }

data class CampusPortalState(
    val phase: CampusPortalPhase = CampusPortalPhase.NotSignedIn,
    val detail: String = "",
    val connectedAtMillis: Long? = null,
    val maskedPhone: String = "",
    /** Product binder uses this capability address; never offer it as an arbitrary editable URL. */
    val portalAddress: String? = null,
    val manualPortalOpened: Boolean = false,
    val autoAttempted: Boolean = false,
) {
    val busy: Boolean get() = phase == CampusPortalPhase.Connecting
    val canConnect: Boolean get() = phase == CampusPortalPhase.Offline
    val canDisconnect: Boolean get() = phase == CampusPortalPhase.Online
    val canRetry: Boolean get() = phase == CampusPortalPhase.Failed || phase == CampusPortalPhase.NotOnCampus
}

fun campusDialRejectionDetail(rejection: CampusDialRejection, raw: String): String = when (rejection) {
    CampusDialRejection.PasswordWrong -> AppText.CampusNetwork.PORTAL_PASSWORD_WRONG_DETAIL
    CampusDialRejection.Unpaid -> AppText.CampusNetwork.PORTAL_UNPAID_DETAIL
    CampusDialRejection.NoRedirect -> AppText.CampusNetwork.PORTAL_NO_REDIRECT_DETAIL
    CampusDialRejection.Other ->
        if (raw.isBlank()) AppText.CampusNetwork.PORTAL_DIAL_REJECTED_DETAIL
        else AppText.CampusNetwork.portalErrorDetail(raw)
}
