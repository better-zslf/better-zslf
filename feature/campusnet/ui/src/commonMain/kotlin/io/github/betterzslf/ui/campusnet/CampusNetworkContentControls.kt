package io.github.betterzslf.ui.campusnet

import io.github.betterzslf.feature.campusnet.CampusPortalPhase

/** Render-only projection. A portal address, source and controller never cross the content API. */
data class CampusPortalDisplay(
    val phase: CampusPortalPhase = CampusPortalPhase.NotSignedIn,
    val detail: String = "",
    val elapsedMillis: Long? = null,
    val maskedPhone: String = "",
    val hasManualAddress: Boolean = false,
    val manualPortalOpened: Boolean = false,
    val openFailed: Boolean = false,
    val canConnect: Boolean = false,
    val canDisconnect: Boolean = false,
    val canRetry: Boolean = false,
)

/** Safe input gates only; field values, field changes and confirmation authority stay in the binder. */
data class CampusNetworkContentControls(
    val sessionBound: Boolean = false,
    val canGoBack: Boolean = false,
    val canOpenDorm: Boolean = false,
    val editable: Boolean = false,
    val selectable: Boolean = false,
    val canCheckPhone: Boolean = false,
    val canPrepare: Boolean = false,
    val finalDialogOpen: Boolean = false,
    val finalMaskedAccount: String = "",
    val acknowledged: Boolean = false,
)

/** No identity, credentials, URL, nonce or acknowledgment payload is supplied by content. */
enum class CampusNetworkUiEvent {
    BACK, REFRESH, OPEN_DORM, CONNECT, DISCONNECT, RETRY_CONNECTION, OPEN_PORTAL,
    TOGGLE_EXPANDED, CHECK_PHONE, PREPARE, EDIT_PREVIEW,
    OPEN_CONFIRMATION, DISMISS_CONFIRMATION, TOGGLE_ACKNOWLEDGEMENT, CONFIRM,
}
