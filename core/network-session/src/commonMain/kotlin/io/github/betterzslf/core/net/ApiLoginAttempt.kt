package io.github.betterzslf.core.net

/**
 * An opaque capability for one login screen, not a credential or request DTO. There are
 * deliberately no public fields, getters, copy/component functions or serializers, and the
 * private authority binds it to exactly one ApiClient. Passive capture creates one without
 * reading or mutating the store; only an explicit claim turns an attempt into the store-wide
 * latest SUBMIT.
 */
class ApiLoginAttempt internal constructor(private val authority: Any) {
    internal fun ownedBy(authority: Any): Boolean = this.authority === authority

    // No state, epoch, owner or credential ever appears in diagnostics.
    override fun toString(): String = "ApiLoginAttempt(redacted)"
}
