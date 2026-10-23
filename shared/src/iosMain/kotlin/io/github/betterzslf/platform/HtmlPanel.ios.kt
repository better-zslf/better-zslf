@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebpagePreferences
import platform.WebKit.WKWebsiteDataStore
import platform.WebKit.javaScriptEnabled
import platform.darwin.NSObject

@Composable
actual fun HtmlPanel(html: String, modifier: Modifier) {
    // WKWebView holds its navigation delegate weakly; Compose retains the renderer for its lifetime.
    val renderer = remember { ArticleRenderer() }
    UIKitView(
        factory = { renderer.createView() },
        modifier = modifier,
        update = { renderer.render(it, html) },
        onRelease = { renderer.release(it) },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}

private const val ARTICLE_BASE_URL = "https://zslf.zsc.edu.cn/"

private class ArticleRenderer : NSObject(), WKNavigationDelegateProtocol {
    private var renderedHtml: String? = null
    private var awaitingLocalDocument = false

    @Suppress("DEPRECATION") // Also disable the legacy switch for early iOS 14 WebKit releases.
    fun createView(): WKWebView {
        val configuration = WKWebViewConfiguration().apply {
            defaultWebpagePreferences = WKWebpagePreferences().apply { allowsContentJavaScript = false }
            preferences.javaScriptEnabled = false
            preferences.javaScriptCanOpenWindowsAutomatically = false
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            // Fresh controller: no injected scripts and no message handlers/native bridge.
            userContentController = WKUserContentController()
        }
        return WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
            navigationDelegate = this@ArticleRenderer
            allowsBackForwardNavigationGestures = false
        }
    }

    fun render(view: WKWebView, html: String) {
        if (renderedHtml == html) return
        renderedHtml = html
        awaitingLocalDocument = true
        // This loads a local HTML string, not an authenticated URL request. No token/cookie is passed.
        view.loadHTMLString(articleDocument(html), baseURL = NSURL(string = ARTICLE_BASE_URL))
    }

    fun release(view: WKWebView) {
        view.stopLoading()
        view.navigationDelegate = null
        renderedHtml = null
        awaitingLocalDocument = false
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val action = decidePolicyForNavigationAction
        val url = action.request.URL
        val absoluteUrl = url?.absoluteString
        val isUserLink = action.navigationType == WKNavigationTypeLinkActivated
        val isMainFrame = action.targetFrame?.mainFrame == true
        val isLocalDocument = absoluteUrl == ARTICLE_BASE_URL || absoluteUrl == "about:blank"

        // WKWebView may identify loadHTMLString by its base URL or by about:blank.
        if (awaitingLocalDocument && isMainFrame && !isUserLink && isLocalDocument) {
            awaitingLocalDocument = false
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        val isSameDocumentAnchor = isUserLink && isMainFrame && url?.fragment != null &&
            absoluteUrl?.substringBefore('#') == webView.URL?.absoluteString?.substringBefore('#')
        if (isSameDocumentAnchor) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }

        // Cancel forms, redirects, subframes, custom schemes, downloads, and all in-panel navigation.
        // Only a user-activated HTTPS link can open in the system browser (including target=_blank).
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        if (isUserLink && action.sourceFrame.mainFrame && absoluteUrl != null) {
            openExternalHttpsUrl(absoluteUrl)
        }
    }
}

/** The first CSP stays effective even if the supplied article embeds its own head/meta tags. */
private fun articleDocument(html: String): String = """
    <!doctype html>
    <html><head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; connect-src 'none'; img-src https: data:; style-src 'unsafe-inline'; font-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'">
    <style>
      :root { color-scheme: light dark; }
      body { font: -apple-system-body; margin: 16px; overflow-wrap: anywhere; }
      img { max-width: 100%; height: auto; }
      pre { white-space: pre-wrap; } table { max-width: 100%; }
    </style>
    </head><body>$html</body></html>
""".trimIndent()
