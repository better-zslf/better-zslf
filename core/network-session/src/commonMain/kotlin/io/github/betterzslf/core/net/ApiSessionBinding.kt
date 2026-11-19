package io.github.betterzslf.core.net

import io.github.betterzslf.core.storage.SessionSnapshot

/**
 * An in-memory request capability, not a credential DTO. There are deliberately no public
 * fields, copy/component functions, serializers, or owner/token/epoch-bearing diagnostics.
 * A binding belongs to exactly one ApiClient and one immutable session mutation epoch.
 */
class ApiSessionBinding internal constructor(
    private val authority: Any,
    private val snapshot: SessionSnapshot,
) {
    internal fun matches(authority: Any, current: SessionSnapshot): Boolean =
        this.authority === authority && snapshot.matches(current)

    internal fun ownerFor(authority: Any): String? = if (this.authority === authority) snapshot.owner else null
    internal fun tokenFor(authority: Any): String? = if (this.authority === authority) snapshot.token else null

    override fun toString(): String = "ApiSessionBinding(redacted)"
}
