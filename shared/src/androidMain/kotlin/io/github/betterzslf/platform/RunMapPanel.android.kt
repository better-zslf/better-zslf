package io.github.betterzslf.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.annotation.Keep
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@Composable
actual fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier) {
    val latestModel = rememberUpdatedState(model)
    val latestEvent = rememberUpdatedState(onEvent)
    val renderer = remember { AndroidRunMapRenderer({ latestModel.value }, { latestEvent.value(it) }) }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }
    AndroidView(factory = renderer::create, modifier = modifier,
        update = { renderer.render(it, model) }, onRelease = renderer::release)
}

/** A separate, app-owned HTML renderer. No account token, native location, or file access is exposed. */
private class AndroidRunMapRenderer(
    private var currentModel: (() -> RunMapModel)?,
    private var onEvent: ((RunMapEvent) -> Unit)?,
) {
    private val main = Handler(Looper.getMainLooper())
    private var owned: WebView? = null
    private var documentId: String? = null
    @Volatile private var loadedProvider: RunMapProvider? = null
    private var loadedKey: String? = null
    private var lastModel: RunMapModel? = null
    private var ready = false
    private var disposed = false
    private var rendererGone = false

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView = WebView(context).also { view ->
        owned?.let(::release)
        owned = view
        rendererGone = false
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
        view.webChromeClient = object : WebChromeClient() {
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
                result?.cancel(); fail(RunMapFailure.SDK); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                result?.cancel(); return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            // loadDataWithBaseURL is app-initiated. Every subsequent page/link navigation is blocked.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.isForMainFrame) return blockedResponse()
                return if (RunMapWeb.isAllowedRequest(request.url.toString(), loadedProvider)) null else blockedResponse()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == null || view !== owned || disposed || rendererGone || url == null || !RunMapWeb.isAllowedNavigation(url)) return
                applyCurrent(view)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (view === owned && request?.isForMainFrame == true) fail(RunMapFailure.NETWORK)
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                if (view === owned) fail(RunMapFailure.NETWORK)
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (view != null && view === owned) {
                    rendererGone = true
                    documentId = null
                    ready = false
                    fail(RunMapFailure.SDK)
                    release(view)
                }
                return true
            }
        }
    }

    fun render(view: WebView, model: RunMapModel) {
        if (disposed || rendererGone || view !== owned || lastModel == model) return
        val changedConfiguration = model.provider != loadedProvider || model.apiKey != loadedKey
        lastModel = model
        val id = if (changedConfiguration || documentId == null) UUID.randomUUID().toString() else documentId!!
        val document = RunMapWeb.document(model, id)
        if (document.issue != null || document.html == null) {
            documentId = null
            ready = false
            view.stopLoading()
            view.loadDataWithBaseURL(RunMapWeb.baseUrl, EMPTY_DOCUMENT, "text/html", "UTF-8", null)
            fail(document.issue ?: RunMapFailure.CONFIGURATION)
            return
        }
        if (changedConfiguration || documentId == null) {
            documentId = id
            loadedProvider = model.provider
            loadedKey = model.apiKey
            ready = false
            view.stopLoading()
            view.loadDataWithBaseURL(RunMapWeb.baseUrl, document.html, "text/html", "UTF-8", null)
        } else if (ready) applyCurrent(view)
    }

    private fun applyCurrent(view: WebView) {
        if (disposed || rendererGone || view !== owned) return
        val id = documentId ?: return
        val model = currentModel?.invoke() ?: return
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
            if (queued.incrementAndGet() > 16) { queued.decrementAndGet(); return }
            if (!main.post {
                try {
                    if (disposed || rendererGone || view !== owned) return@post
                    val id = documentId ?: return@post
                    val model = currentModel?.invoke() ?: return@post
                    val url = view.url ?: return@post
                    if (!RunMapWeb.isAllowedNavigation(url)) return@post
                    val event = RunMapWeb.parseEvent(message, model, id) ?: return@post
                    if (event == RunMapEvent.Ready) { ready = true; applyCurrent(view) }
                    onEvent?.invoke(event)
                } finally { queued.decrementAndGet() }
            }) queued.decrementAndGet()
        }
    }

    fun release(view: WebView) {
        if (view !== owned) return
        owned = null
        documentId = null
        loadedKey = null
        loadedProvider = null
        lastModel = null
        ready = false
        view.removeJavascriptInterface("runMap")
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.clearHistory()
        view.destroy()
    }
    fun dispose() {
        disposed = true
        onEvent = null
        currentModel = null
        owned?.let(::release)
    }
    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    companion object {
        private const val EMPTY_DOCUMENT = "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'\"><title>地图暂不可用</title>"
    }
}
