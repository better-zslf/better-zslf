package io.github.betterzslf.platform

/** Immutable document authority. Only the Android owned renderer constructs it from RunMapWeb. */
internal data class RunMapBootstrapDocument(
    val ownerId: String,
    val epoch: Long,
    val provider: RunMapProvider?,
    val documentId: String?,
    val ticket: String,
    val html: String,
    val pending: Boolean = true,
) {
    override fun toString(): String = "RunMapBootstrapDocument(epoch=$epoch, provider=$provider, pending=$pending, redacted)"
}

/** Captured by one owned view's per-document client, never reconstructed from an incoming URL. */
internal data class RunMapBootstrapContext(val ownerId: String, val epoch: Long, val provider: RunMapProvider?)
internal data class RunMapBootstrapRequest(
    val url: String,
    val method: String,
    val isMainFrame: Boolean,
    val ticket: String?,
    val isRedirect: Boolean = false,
    val hasGesture: Boolean = false,
) { override fun toString(): String = "RunMapBootstrapRequest(redacted)" }

/** Pure single-use policy. Android must atomically compare-and-set the returned consumed snapshot. */
internal object RunMapBootstrapPolicy {
    const val headerName = "X-Better-Zslf-Map-Bootstrap"
    const val emptyDocument = "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'\"><title>地图暂不可用</title>"
    private val nonce = Regex("[A-Za-z0-9_-]{1,128}")

    fun isCurrent(document: RunMapBootstrapDocument, context: RunMapBootstrapContext): Boolean =
        document.epoch > 0 && nonce.matches(document.ownerId) && nonce.matches(document.ticket) &&
            document.ownerId == context.ownerId && document.epoch == context.epoch && document.provider == context.provider &&
            document.html.isNotEmpty() && (if (document.documentId == null) document.provider == null && document.html == emptyDocument
            else document.provider != null && nonce.matches(document.documentId))

    fun allowsInitial(document: RunMapBootstrapDocument, context: RunMapBootstrapContext, request: RunMapBootstrapRequest): Boolean =
        isCurrent(document, context) && document.pending && request.isMainFrame && request.method == "GET" &&
            request.url == RunMapWeb.baseUrl && request.ticket == document.ticket && !request.isRedirect && !request.hasGesture

    fun consume(document: RunMapBootstrapDocument, context: RunMapBootstrapContext, request: RunMapBootstrapRequest): RunMapBootstrapDocument? =
        if (allowsInitial(document, context, request)) document.copy(pending = false) else null

    fun allowsSubresource(document: RunMapBootstrapDocument, context: RunMapBootstrapContext, request: RunMapBootstrapRequest): Boolean =
        isCurrent(document, context) && !document.pending && !request.isMainFrame && request.method == "GET" &&
            !request.isRedirect && request.url != RunMapWeb.baseUrl && RunMapWeb.isAllowedRequest(request.url, document.provider)
}
