package io.github.betterzslf.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewOutlineProvider
import android.webkit.*
import androidx.annotation.Keep
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Composable
actual fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier) {
    val latestModel = rememberUpdatedState(model)
    val latestEvent = rememberUpdatedState(onEvent)
    val renderer = remember { AndroidRunMapRenderer({ latestModel.value }, { latestEvent.value(it) }) }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }
    AndroidView(factory = renderer::create, modifier = modifier.clipToBounds(),
        update = { renderer.render(it, model) }, onRelease = renderer::release)
}

/** A separate, app-owned HTML renderer. No account token, native location, or file access is exposed. */
private class AndroidRunMapRenderer(
    private var currentModel: (() -> RunMapModel)?,
    private var onEvent: ((RunMapEvent) -> Unit)?,
) {
    private data class ActiveDocument(val view: WebView, val document: RunMapBootstrapDocument) {
        val context: RunMapBootstrapContext get() = RunMapBootstrapContext(document.ownerId, document.epoch, document.provider)
        override fun toString(): String = "ActiveMapDocument(redacted)"
    }
    private val main = Handler(Looper.getMainLooper())
    // Interception and bridge admission run off-main. Never read Compose/currentModel/owned there.
    private val activeDocument = AtomicReference<ActiveDocument?>(null)
    private var owned: WebView? = null
    private var ownerId: String? = null
    private var epoch = 0L
    private var documentId: String? = null
    private var loadedProvider: RunMapProvider? = null
    private var loadedKey: String? = null
    private var lastModel: RunMapModel? = null
    private var ready = false
    private var disposed = false
    private var rendererGone = false

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView = WebView(context).also { view ->
        owned?.let(::release)
        owned = view
        ownerId = UUID.randomUUID().toString()
        rendererGone = false
        view.outlineProvider = ViewOutlineProvider.BOUNDS
        view.clipToOutline = true
        view.clipChildren = true
        view.clipToPadding = true
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.addJavascriptInterface(MapJavascriptBridge(view), "runMap")
        val initial = RunMapBootstrapContext(requireNotNull(ownerId), 0, null)
        view.webChromeClient = chromeClient(initial)
        view.webViewClient = pageClient(initial)
    }

    private fun chromeClient(context: RunMapBootstrapContext) = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
        override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
            callback?.onReceiveValue(null)
            return true
        }
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.cancel()
            if (currentDocument(view, context)?.let(::matchingModel) != null) fail(RunMapFailure.SDK)
            return true
        }
        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            result?.cancel(); return true
        }
        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            result?.cancel(); return true
        }
    }

    private fun pageClient(context: RunMapBootstrapContext) = object : WebViewClient() {
        @Deprecated("Legacy callback has no bootstrap credential")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val current = currentDocument(view, context) ?: return true
            val input = request?.bootstrapRequest() ?: return true
            // Normally app loadUrl bypasses this callback. No other navigation gets an exception.
            return !RunMapBootstrapPolicy.allowsInitial(current.document, context, input)
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val current = currentDocument(view, context) ?: return blockedResponse()
            val input = request?.bootstrapRequest() ?: return blockedResponse()
            if (input.isMainFrame) {
                val consumed = RunMapBootstrapPolicy.consume(current.document, context, input) ?: return blockedResponse()
                val next = ActiveDocument(current.view, consumed)
                if (!activeDocument.compareAndSet(current, next) || activeDocument.get() !== next) return blockedResponse()
                // The exact fake base is fulfilled in memory. It is NEVER passed to the network.
                return WebResourceResponse("text/html", "UTF-8", 200, "OK",
                    mapOf("Cache-Control" to "no-store", "Pragma" to "no-cache", "X-Content-Type-Options" to "nosniff"),
                    ByteArrayInputStream(consumed.html.toByteArray(Charsets.UTF_8)))
            }
            return if (RunMapBootstrapPolicy.allowsSubresource(current.document, context, input)) null else blockedResponse()
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            if (view == null || url != RunMapWeb.baseUrl || currentDocument(view, context) == null) return
            applyCurrent(view)
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val current = currentDocument(view, context) ?: return
            val input = request?.bootstrapRequest() ?: return
            if (input.isMainFrame && input.url == RunMapWeb.baseUrl && input.ticket == current.document.ticket) fail(RunMapFailure.NETWORK)
        }
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            if (currentDocument(view, context) != null) fail(RunMapFailure.NETWORK)
        }
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Process death belongs to the view, including the interval before its first document.
            if (view != null && view === owned) {
                val reportCurrentEpoch = currentDocument(view, context) != null
                activeDocument.set(null)
                rendererGone = true
                documentId = null
                ready = false
                if (reportCurrentEpoch) fail(RunMapFailure.SDK)
                release(view)
            }
            return true
        }
    }

    private fun WebResourceRequest.bootstrapRequest() = RunMapBootstrapRequest(
        url.toString(), method, isForMainFrame,
        requestHeaders?.entries?.firstOrNull { it.key.equals(RunMapBootstrapPolicy.headerName, ignoreCase = true) }?.value,
        isRedirect, hasGesture(),
    )
    private fun currentDocument(view: WebView?, context: RunMapBootstrapContext): ActiveDocument? {
        val current = activeDocument.get() ?: return null
        return current.takeIf { view === it.view && RunMapBootstrapPolicy.isCurrent(it.document, context) }
    }

    fun render(view: WebView, model: RunMapModel) {
        if (disposed || rendererGone || view !== owned || lastModel == model) return
        val changedConfiguration = model.provider != loadedProvider || model.apiKey != loadedKey
        lastModel = model
        val id = if (changedConfiguration || documentId == null) UUID.randomUUID().toString() else documentId!!
        val document = RunMapWeb.document(model, id)
        val html = document.html
        if (document.issue != null || html == null) {
            documentId = null
            loadedProvider = null
            loadedKey = null
            ready = false
            loadOwnedDocument(view, null, null, RunMapBootstrapPolicy.emptyDocument)
            fail(document.issue ?: RunMapFailure.CONFIGURATION)
            return
        }
        if (changedConfiguration || documentId == null) {
            documentId = id
            loadedProvider = model.provider
            loadedKey = model.apiKey
            ready = false
            loadOwnedDocument(view, model.provider, id, html)
        } else if (ready) applyCurrent(view)
    }

    private fun loadOwnedDocument(view: WebView, provider: RunMapProvider?, id: String?, html: String) {
        // Invalidate admission BEFORE stopping the old load/replacing clients, including empty docs.
        activeDocument.set(null)
        view.stopLoading()
        val owner = ownerId ?: return
        val document = RunMapBootstrapDocument(owner, ++epoch, provider, id, UUID.randomUUID().toString(), html)
        val current = ActiveDocument(view, document)
        view.webChromeClient = chromeClient(current.context)
        view.webViewClient = pageClient(current.context)
        activeDocument.set(current)
        // Per-epoch app credential, not an account token. Same-URL stale requests cannot consume it.
        view.loadUrl(RunMapWeb.baseUrl, mapOf(RunMapBootstrapPolicy.headerName to document.ticket))
    }

    /** Main thread only. Changed provider/key invalidates old callbacks even before the next render. */
    private fun matchingModel(current: ActiveDocument): RunMapModel? {
        if (disposed || rendererGone || current.view !== owned || current.document.pending || current.document.documentId != documentId) return null
        val model = currentModel?.invoke() ?: return null
        return model.takeIf { it.provider == current.document.provider && it.apiKey == loadedKey }
    }
    private fun applyCurrent(view: WebView) {
        val current = activeDocument.get()?.takeIf { it.view === view } ?: return
        val model = matchingModel(current) ?: return
        val id = current.document.documentId ?: return
        val script = RunMapWeb.updateScript(model, id) ?: return
        view.evaluateJavascript(script, null)
    }

    private fun fail(reason: RunMapFailure) {
        if (!disposed) onEvent?.invoke(RunMapEvent.Failed(reason))
    }

    @Keep
    private inner class MapJavascriptBridge(private val view: WebView) {
        private val queued = AtomicInteger()
        @JavascriptInterface
        fun postMessage(message: String?) {
            if (message == null || message.length > 4096) return
            val admitted = activeDocument.get()?.takeIf { it.view === view && !it.document.pending } ?: return
            val context = admitted.context
            if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); return }
            if (!main.post {
                try {
                    val current = currentDocument(view, context) ?: return@post
                    val model = matchingModel(current) ?: return@post
                    val id = current.document.documentId ?: return@post
                    if (view.url != RunMapWeb.baseUrl) return@post
                    val event = RunMapWeb.parseEvent(message, model, id) ?: return@post
                    if (event == RunMapEvent.Ready) { ready = true; applyCurrent(view) }
                    onEvent?.invoke(event)
                } finally { queued.decrementAndGet() }
            }) queued.decrementAndGet()
        }
    }

    fun release(view: WebView) {
        if (view !== owned) return
        activeDocument.set(null)
        owned = null
        ownerId = null
        documentId = null
        loadedKey = null
        loadedProvider = null
        lastModel = null
        ready = false
        view.removeJavascriptInterface("runMap")
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = pageClient(RunMapBootstrapContext("released_view", 0, null))
        view.clearHistory()
        view.destroy()
    }
    fun dispose() {
        disposed = true
        onEvent = null
        currentModel = null
        owned?.let(::release)
        activeDocument.set(null)
    }
    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
