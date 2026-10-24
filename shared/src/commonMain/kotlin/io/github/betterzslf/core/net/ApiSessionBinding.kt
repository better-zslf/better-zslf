package io.github.betterzslf.core.net

import io.github.betterzslf.core.model.Session

/**
 * An in-memory request capability, not a credential DTO. There are deliberately no public
 * fields, copy/component functions, serializers, or owner/token-bearing debug representations.
 * A binding belongs to exactly one ApiClient and captures one immutable session snapshot.
 */
class ApiSessionBinding internal constructor(
    private val authority: Any,
    private val owner: String,
    private val token: String,
) {
    internal fun matches(authority: Any, session: Session?): Boolean =
        this.authority === authority && session != null && session.userId == owner && session.token == token

    internal fun ownerFor(authority: Any): String? = if (this.authority === authority) owner else null
    internal fun tokenFor(authority: Any): String? = if (this.authority === authority) token else null

    override fun toString(): String = "ApiSessionBinding(redacted)"
}
