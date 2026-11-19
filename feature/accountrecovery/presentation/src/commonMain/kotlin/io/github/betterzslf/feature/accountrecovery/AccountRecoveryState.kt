package io.github.betterzslf.feature.accountrecovery

enum class AccountRecoveryPhase { GUEST, EDITING, PREVIEW, RESETTING, COMPLETED, CLEANUP_WARNING, FAILED, UNCERTAIN, SESSION_CHANGED, CLOSED }

/** Display only. The confirmation ticket and verified target are never included here. */
data class AccountRecoveryPreview(val maskedPhone: String)

data class AccountRecoveryState(
    val phase: AccountRecoveryPhase = AccountRecoveryPhase.EDITING,
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val codeRequested: Boolean = false,
    val cooldownSeconds: Int = 0,
    val verified: Boolean = false,
    val maskedPhone: String? = null,
    val preview: AccountRecoveryPreview? = null,
    val message: String? = null,
    val inputRevision: Long = 0,
) {
    val busy: Boolean get() = sending || verifying || phase == AccountRecoveryPhase.RESETTING
    val terminal: Boolean get() = phase !in setOf(AccountRecoveryPhase.EDITING, AccountRecoveryPhase.PREVIEW)
    val editable: Boolean get() = phase == AccountRecoveryPhase.EDITING && !busy
}

/**
 * Opaque, controller-owned fixed-preview ticket for the TRUSTED product binder, not UI props.
 * Its private owner fence rejects cross-controller use even when private nonce values coincide.
 */
class RecoveryResetTicket internal constructor(
    private val owner: AccountRecoveryController,
    private val nonce: Long,
) {
    internal fun matches(controller: AccountRecoveryController, expectedNonce: Long): Boolean =
        owner === controller && nonce == expectedNonce

    override fun toString(): String = "RecoveryResetTicket(redacted)"
}
