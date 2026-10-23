package io.github.betterzslf.platform

import android.annotation.SuppressLint
import android.app.Activity
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
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
        check(preferences.edit().putString(key, encoded).commit()) { "本地数据保存失败" }
    }
    override fun remove(key: String) { preferences.edit().remove(key).commit() }
    override fun clear() { preferences.edit().clear().commit() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun HtmlPanel(html: String, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.isForMainFrame && request.url.scheme == "https") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
            }
        }
    }, update = { view ->
        if (view.tag != html) {
            view.tag = html
            view.loadDataWithBaseURL("https://zslf.zsc.edu.cn/", articleDocument(html), "text/html", "UTF-8", null)
        }
    }, onRelease = { it.destroy() })
}

private fun articleDocument(html: String) = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><style>body{font:16px/1.8 sans-serif;padding:16px;overflow-wrap:anywhere}img,video,table{max-width:100%;height:auto}</style></head><body>$html</body></html>"""
