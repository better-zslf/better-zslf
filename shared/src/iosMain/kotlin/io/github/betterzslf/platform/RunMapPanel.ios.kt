@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.NSObject

@Composable
actual fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier) {
    val latestModel = rememberUpdatedState(model)
    val latestEvent = rememberUpdatedState(onEvent)
    val renderer = remember { IosRunMapRenderer({ latestModel.value }, { latestEvent.value(it) }) }
    DisposableEffect(renderer) { onDispose { renderer.dispose() } }
    UIKitView(
        factory = { renderer.createView() },
        modifier = modifier,
        update = { renderer.render(it, model) },
        onRelease = { renderer.release(it) },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}

private const val MAP_MESSAGE_HANDLER = "runMap"
private const val MAX_MAP_MESSAGE_CHARS = 65_536
private const val EMPTY_MAP_DOCUMENT = "<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'none'; frame-src 'none'; form-action 'none'\"></head><body></body></html>"

/** Pure Kotlin owner. Only the separate delegate subclasses NSObject; no Kotlin/ObjC mixing. */
private class IosRunMapRenderer(
    private var currentModel: (() -> RunMapModel)?,
    private var onEvent: ((RunMapEvent) -> Unit)?,
) {
    private var ownedWebView: WKWebView? = null
    private var contentController: WKUserContentController? = null
    private var nativeDelegate: RunMapNativeDelegate? = null
    private var documentId: String? = null
    private var documentBase: String? = null
    private var activeNavigation: WKNavigation? = null
    private var loadedProvider: RunMapProvider? = null
    private var loadedKey: String? = null
    private var lastAttempt: RunMapModel? = null
    private var lastApplied: RunMapModel? = null
    private var hasMapDocument = false
    private var acceptingEvents = false
    private var allowingInitialNavigation = false
    private var ready = false
    private var disposed = false
    private var scriptRevision = 0L
    private var failure: RunMapFailure? = null

    fun createView(): WKWebView {
        ownedWebView?.let(::release)
        val delegate = RunMapNativeDelegate(::allowNavigation, ::onMessage, ::onFinished, ::onNavigationFailed, ::onTerminated)
        val controller = WKUserContentController().apply {
            addScriptMessageHandler(delegate, name = MAP_MESSAGE_HANDLER)
            // Baseline-iOS-14 file denial stays in the DOM; do not strongly link newer panel classes.
            addUserScript(WKUserScript(
                source = MAP_FILE_INPUT_GUARD,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = false,
            ))
        }
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            processPool = WKProcessPool()
            userContentController = controller
            defaultWebpagePreferences = WKWebpagePreferences().apply { allowsContentJavaScript = true }
            preferences.javaScriptCanOpenWindowsAutomatically = false
            mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll
        }
        val view = WKWebView(frame = CGRectZero.readValue(), configuration = configuration)
        ownedWebView = view
        contentController = controller
        nativeDelegate = delegate
        view.navigationDelegate = delegate
        view.UIDelegate = delegate
        view.allowsBackForwardNavigationGestures = false
        view.allowsLinkPreview = false
        return view
    }

    fun render(view: WKWebView, model: RunMapModel) {
        if (disposed || view !== ownedWebView) return
        val changedConfiguration = model.provider != loadedProvider || model.apiKey != loadedKey
        if (documentId == null || changedConfiguration || !hasMapDocument) {
            if (!changedConfiguration && lastAttempt == model) return
            loadDocument(view, model)
        } else if (lastAttempt != model) {
            lastAttempt = model
            if (ready) applyLatestModel(view)
        }
    }

    private fun loadDocument(view: WKWebView, model: RunMapModel) {
        // Invalidate the old epoch before stopLoading can deliver any pending native callbacks.
        val id = NSUUID().UUIDString
        val base = RunMapWeb.baseUrl
        documentId = id
        documentBase = base
        activeNavigation = null
        ++scriptRevision
        ready = false
        hasMapDocument = false
        acceptingEvents = false
        failure = null
        lastApplied = null
        lastAttempt = model
        loadedProvider = model.provider
        loadedKey = model.apiKey
        view.stopLoading()
        val document = RunMapWeb.document(model, id)
        val html = document.html
        hasMapDocument = html != null && document.issue == null
        acceptingEvents = hasMapDocument
        allowingInitialNavigation = true
        if (!isCurrent(view, id)) return
        activeNavigation = view.loadHTMLString(
            if (hasMapDocument) html ?: EMPTY_MAP_DOCUMENT else EMPTY_MAP_DOCUMENT,
            baseURL = NSURL(string = base),
        )
        if (hasMapDocument) lastApplied = model
        else reportFailure(document.issue ?: RunMapFailure.CONFIGURATION)
    }

    private fun applyLatestModel(view: WKWebView) {
        val id = documentId ?: return
        val model = currentModel?.invoke() ?: return
        if (!isCurrent(view, id) || !ready || !hasMapDocument || !sameConfiguration(model)) return
        if (lastApplied == model && failure == null) return
        lastAttempt = model
        val revision = ++scriptRevision
        val script = RunMapWeb.updateScript(model, id)
        if (script == null) {
            reportFailure(RunMapWeb.document(model, id).issue ?: RunMapFailure.CONFIGURATION)
            return
        }
        lastApplied = model
        view.evaluateJavaScript(script) { _, error ->
            if (!isCurrent(view, id) || !acceptingEvents || revision != scriptRevision) return@evaluateJavaScript
            if (error != null) {
                // NSError may contain provider URLs/API keys. Only emit the public reason enum.
                reportFailure(RunMapFailure.SDK)
            } else if (failure == RunMapFailure.CONFIGURATION ||
                failure == RunMapFailure.UNCONFIRMED_COORDINATES || failure == RunMapFailure.TOO_MANY_POINTS
            ) {
                // Eval success is not SDK success: only repair a prior model-validation error.
                failure = null
                onEvent?.invoke(RunMapEvent.Ready)
            }
        }
    }

    private fun sameConfiguration(model: RunMapModel): Boolean =
        model.provider == loadedProvider && model.apiKey == loadedKey

    private fun isCurrent(view: WKWebView, id: String): Boolean =
        !disposed && view === ownedWebView && documentId == id

    private fun isOwnDocumentUrl(url: String?): Boolean =
        url != null && (url.substringBefore('#') == documentBase || url.substringBefore('#') == "about:blank") &&
            RunMapWeb.isAllowedNavigation(url.substringBefore('#'))

    private fun allowNavigation(view: WKWebView, action: WKNavigationAction): Boolean {
        if (disposed || view !== ownedWebView || documentId == null || !allowingInitialNavigation) return false
        // No links, redirects, popups, iframe documents, HTTP forms, or system-browser fallback.
        return action.targetFrame?.mainFrame == true && action.sourceFrame.mainFrame &&
            action.navigationType == WKNavigationTypeOther && isOwnDocumentUrl(action.request.URL?.absoluteString)
    }

    private fun onMessage(controller: WKUserContentController, message: WKScriptMessage) {
        val view = ownedWebView ?: return
        val id = documentId ?: return
        if (disposed || !hasMapDocument || !acceptingEvents || controller !== contentController || message.webView !== view ||
            !message.frameInfo.mainFrame || message.name != MAP_MESSAGE_HANDLER ||
            !isOwnDocumentUrl(message.frameInfo.request.URL?.absoluteString) || !isOwnDocumentUrl(view.URL?.absoluteString)
        ) return
        val model = currentModel?.invoke() ?: return
        if (!sameConfiguration(model)) return
        val payload = message.body as? String ?: return
        if (payload.length > MAX_MAP_MESSAGE_CHARS) return
        // Common parser validates this exact fresh document ID and the latest edit/checkpoint model.
        val event = RunMapWeb.parseEvent(payload, model, id) ?: return
        when (event) {
            RunMapEvent.Ready -> {
                if (ready) return
                ready = true
                failure = null
                onEvent?.invoke(event)
                applyLatestModel(view)
            }
            is RunMapEvent.Failed -> reportFailure(event.reason)
            else -> if (ready) onEvent?.invoke(event)
        }
    }

    private fun onFinished(view: WKWebView, navigation: WKNavigation?) {
        if (disposed || view !== ownedWebView || navigation == null || navigation !== activeNavigation) return
        allowingInitialNavigation = false
    }

    private fun onNavigationFailed(view: WKWebView, navigation: WKNavigation?) {
        if (disposed || view !== ownedWebView || navigation == null || navigation !== activeNavigation) return
        allowingInitialNavigation = false
        acceptingEvents = false
        ++scriptRevision
        ready = false
        reportFailure(RunMapFailure.NETWORK)
    }

    private fun onTerminated(view: WKWebView) {
        if (disposed || view !== ownedWebView) return
        ready = false
        allowingInitialNavigation = false
        acceptingEvents = false
        ++scriptRevision
        reportFailure(RunMapFailure.UNAVAILABLE)
        // No reload/restart: a fresh explicit composition/configuration owns the next attempt.
    }

    private fun reportFailure(reason: RunMapFailure) {
        if (disposed || failure == reason) return
        failure = reason
        onEvent?.invoke(RunMapEvent.Failed(reason))
    }

    fun release(view: WKWebView) {
        if (view !== ownedWebView) return
        // Break handler -> delegate -> owner cycles before any asynchronous cancellation callbacks.
        documentId = null
        documentBase = null
        activeNavigation = null
        ++scriptRevision
        ready = false
        hasMapDocument = false
        acceptingEvents = false
        allowingInitialNavigation = false
        view.stopLoading()
        view.navigationDelegate = null
        view.UIDelegate = null
        contentController?.removeScriptMessageHandlerForName(MAP_MESSAGE_HANDLER)
        contentController?.removeAllUserScripts()
        nativeDelegate?.clear()
        nativeDelegate = null
        contentController = null
        ownedWebView = null
        loadedProvider = null
        loadedKey = null
        lastAttempt = null
        lastApplied = null
        failure = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        ownedWebView?.let(::release)
        currentModel = null
        onEvent = null
    }
}

/** All native entry points are main-run-loop callbacks; no common Kotlin interface is inherited. */
private class RunMapNativeDelegate(
    private var allowNavigation: ((WKWebView, WKNavigationAction) -> Boolean)?,
    private var onMessage: ((WKUserContentController, WKScriptMessage) -> Unit)?,
    private var onFinished: ((WKWebView, WKNavigation?) -> Unit)?,
    private var onFailure: ((WKWebView, WKNavigation?) -> Unit)?,
    private var onTerminated: ((WKWebView) -> Unit)?,
) : NSObject(), WKNavigationDelegateProtocol, WKScriptMessageHandlerProtocol, WKUIDelegateProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        onMessage?.invoke(userContentController, didReceiveScriptMessage)
    }

    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val allow = allowNavigation?.invoke(webView, decidePolicyForNavigationAction) == true
        decisionHandler(if (allow) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        onFinished?.invoke(webView, didFinishNavigation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        onFailure?.invoke(webView, didFailNavigation)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        onFailure?.invoke(webView, didFailProvisionalNavigation)
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        onTerminated?.invoke(webView)
    }

    override fun webView(webView: WKWebView, createWebViewWithConfiguration: WKWebViewConfiguration, forNavigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures): WKWebView? = null

    override fun webView(webView: WKWebView, requestMediaCapturePermissionForOrigin: WKSecurityOrigin, initiatedByFrame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: (WKPermissionDecision) -> Unit) {
        decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
    }

    override fun webView(webView: WKWebView, requestDeviceOrientationAndMotionPermissionForOrigin: WKSecurityOrigin, initiatedByFrame: WKFrameInfo, decisionHandler: (WKPermissionDecision) -> Unit) {
        decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
    }

    fun clear() {
        allowNavigation = null
        onMessage = null
        onFinished = null
        onFailure = null
        onTerminated = null
    }
}

/** All frames, document-start: no file selection API is part of the map's capability surface. */
private val MAP_FILE_INPUT_GUARD = """
    (function () {
      'use strict';
      function fileInput(target) {
        return target && target.tagName === 'INPUT' && String(target.type).toLowerCase() === 'file';
      }
      function lock(target, name, value) {
        try {
          var descriptor = Object.getOwnPropertyDescriptor(target, name);
          if (descriptor && descriptor.configurable === false) return;
          Object.defineProperty(target, name, {configurable:false, writable:false, value:value});
        } catch (_) {}
      }
      var hp = window.HTMLElement && HTMLElement.prototype;
      if (hp && hp.click) {
        var click = hp.click;
        lock(hp, 'click', function () {
          if (fileInput(this)) return;
          return click.apply(this, arguments);
        });
      }
      var ip = window.HTMLInputElement && HTMLInputElement.prototype;
      if (ip && ip.showPicker) {
        var picker = ip.showPicker;
        lock(ip, 'showPicker', function () {
          if (fileInput(this)) return;
          return picker.apply(this, arguments);
        });
      }
      var ep = window.EventTarget && EventTarget.prototype;
      if (ep && ep.dispatchEvent) {
        var dispatch = ep.dispatchEvent;
        lock(ep, 'dispatchEvent', function (event) {
          if (fileInput(this) && event && String(event.type).toLowerCase() === 'click') return false;
          return dispatch.apply(this, arguments);
        });
      }
      ['showOpenFilePicker', 'showDirectoryPicker', 'showSaveFilePicker'].forEach(function (name) {
        [window, Object.getPrototypeOf(window)].forEach(function (target) {
          if (target && typeof target[name] === 'function') lock(target, name, function () {
            var error = new Error('File selection is unavailable in the map');
            error.name = 'NotAllowedError';
            return Promise.reject(error);
          });
        });
      });
      document.addEventListener('click', function (event) {
        var target = event.target;
        if (target && target.closest && target.closest('input[type="file"]')) {
          event.preventDefault(); event.stopImmediatePropagation();
        }
      }, true);
    })();
""".trimIndent()
