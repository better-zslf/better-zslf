package io.github.betterzslf.ui.auth

/**
 * The nine user intents of the login surface, collected whole from the original chooser and
 * campus form. Zero payload by design: no field text, credential, NavKey or route crosses this
 * API, and nothing was added or removed from the user-visible behavior.
 *
 *  CHOOSE_CAMPUS     tap the 掌上莲峰 entry card
 *  CHOOSE_ACADEMIC   tap the 教务系统 entry card
 *  CHOOSE_DORM       tap the dorm-network text link
 *  ENTER_APP         tap the "进入应用" action
 *  BACK              tap the campus form's back chevron
 *  SUBMIT            tap the login button
 *  RECOVER_PASSWORD  tap the recover-password text action
 *  USER_AGREEMENT    tap the user-agreement document button
 *  PRIVACY_POLICY    tap the privacy-policy document button
 */
enum class AuthLoginUiEvent {
    CHOOSE_CAMPUS, CHOOSE_ACADEMIC, CHOOSE_DORM, ENTER_APP,
    BACK, SUBMIT, RECOVER_PASSWORD, USER_AGREEMENT, PRIVACY_POLICY,
}

/**
 * Safe chooser display: three booleans and nothing else. Bound flags come from the root's
 * session/academic state; the derived enter visibility keeps the chooser from ever offering a
 * way into an app that would have nothing to show.
 */
data class AuthChooserDisplay(
    val showDormLink: Boolean = true,
    val campusBound: Boolean = false,
    val academicBound: Boolean = false,
) {
    /** Only appears once something is actually usable. */
    val canEnter: Boolean get() = campusBound || academicBound
}

/**
 * Safe campus-form display: a finite phase flag, a whitelisted notice already folded into fixed
 * AppText by the root binder, and one local branded extra entry (label plus an opaque local key
 * minted by the root; the UI never sees the NavKey behind it).
 */
data class CampusLoginDisplay(
    val busy: Boolean = false,
    val notice: CampusLoginNotice? = null,
    val extraLabel: String? = null,
    val extraKey: String = "",
)

/**
 * Notice copy already resolved to existing AppText constants; the root keeps raw service text
 * and storage notices behind a whitelist, so nothing arbitrary can reach the form.
 */
data class CampusLoginNotice(
    val title: String,
    val message: String,
)

/** Safe form controls: plain flags mirroring the original enabled states. */
data class CampusLoginControls(
    val submitEnabled: Boolean = false,
    val recoveryAvailable: Boolean = false,
)
