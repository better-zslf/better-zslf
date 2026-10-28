package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.WindowInsetsController
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.betterzslf.core.storage.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidPlatform {
    internal lateinit var context: Context
    internal var versionName: String = "development"
    fun initialize(context: Context, versionName: String) {
        this.context = context.applicationContext
        this.versionName = versionName
    }
}

actual fun createPlatformServices(): PlatformServices = object : PlatformServices {
    override val storage: KeyValueStore = EncryptedStore(AndroidPlatform.context)
    override val versionName: String = AndroidPlatform.versionName
    override val description: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    override fun httpClient() = HttpClient(OkHttp)
    override fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return
        runCatching {
            AndroidPlatform.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun copyToClipboard(text: String): Boolean = runCatching {
        val manager = AndroidPlatform.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@runCatching false
        manager.setPrimaryClip(ClipData.newPlainText(AndroidPlatform.context.getString(android.R.string.ok), text))
        true
    }.getOrDefault(false)
}

@Composable
actual fun PlatformAppearance(dark: Boolean?) {
    val view = LocalView.current
    val systemDark = LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val resolvedDark = dark ?: systemDark
    SideEffect {
        var context: Context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window?.insetsController?.setSystemBarsAppearance(if (resolvedDark) 0 else flags, flags)
        window?.isNavigationBarContrastEnforced = false
    }
}

/** Values are authenticated-encrypted with a non-exportable Android Keystore key. */
private class EncryptedStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("better_zslf", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey("better_zslf_storage", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("better_zslf_storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun get(key: String): String? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            require(raw.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        }.getOrElse { preferences.edit().remove(key).apply(); null }
    }
    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(key, encoded).commit()) { AppText.Common.LOCAL_DATA_SAVE_FAILED }
    }
    override fun remove(key: String) { preferences.edit().remove(key).commit() }
    override fun clear() { preferences.edit().clear().commit() }
}

@Composable
actual fun HtmlPanel(html: String, modifier: Modifier, onLoadState: (HtmlPanelLoadState) -> Unit) {
    // Changing HTML owns a new native document. A common retry also remounts this whole key group.
    key(html) {
        val callback = rememberUpdatedState(onLoadState)
        val renderer = remember { AndroidArticleRenderer { callback.value(it) } }
        DisposableEffect(renderer) { onDispose { renderer.invalidate() } }
        AndroidView(modifier = modifier, factory = renderer::createView,
            update = { renderer.render(it, html) }, onRelease = renderer::release)
    }
}

private const val ARTICLE_BASE_URL = "https://zslf.zsc.edu.cn/"

private class AndroidArticleRenderer(private var onLoadState: ((HtmlPanelLoadState) -> Unit)?) {
    private var ownedView: WebView? = null
    private var started = false
    private var failed = false
    private var disposed = false

    @SuppressLint("SetJavaScriptEnabled")
    fun createView(context: Context): WebView = WebView(context).apply {
        ownedView = this
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (owns(view) && request.isForMainFrame && request.url.scheme == "https") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (started && !failed && (url == ARTICLE_BASE_URL || url == "about:blank")) report(view, HtmlPanelLoadState.READY)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) reportFailure(view)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) reportFailure(view)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                reportFailure(view) // Only an owned, live document may notify its session.
                return true // Also consume late callbacks for an already disposed view.
            }
        }
    }

    fun render(view: WebView, html: String) {
        if (!owns(view) || started) return
        started = true
        report(view, HtmlPanelLoadState.LOADING)
        runCatching { view.loadDataWithBaseURL(ARTICLE_BASE_URL, articleDocument(html), "text/html", "UTF-8", null) }
            .onFailure { reportFailure(view) }
    }

    private fun owns(view: WebView) = !disposed && view === ownedView
    private fun report(view: WebView, state: HtmlPanelLoadState) {
        if (owns(view)) onLoadState?.invoke(state)
    }
    private fun reportFailure(view: WebView) {
        if (!owns(view)) return
        failed = true
        report(view, HtmlPanelLoadState.FAILED)
    }

    fun invalidate() { disposed = true; onLoadState = null }
    fun release(view: WebView) {
        if (view !== ownedView) return
        invalidate()
        ownedView = null
        view.stopLoading()
        view.webViewClient = ReleasedArticleClient
        view.tag = null
        view.destroy()
    }
}

/** A detached/destroyed view must not return WebViewClient's default false for a late renderer exit. */
private object ReleasedArticleClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
}

private fun articleDocument(html: String) = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><style>body{font:16px/1.8 sans-serif;padding:16px;overflow-wrap:anywhere}img,video,table{max-width:100%;height:auto}</style></head><body>$html</body></html>"""
