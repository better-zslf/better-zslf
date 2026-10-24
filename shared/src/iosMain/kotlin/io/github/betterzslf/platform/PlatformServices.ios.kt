@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.betterzslf.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSBundle
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

actual fun createPlatformServices(): PlatformServices = IosPlatformServices

private object IosPlatformServices : PlatformServices {
    override val storage = KeychainKeyValueStore(
        service = "${NSBundle.mainBundle.bundleIdentifier ?: "io.github.betterzslf"}.key-value-store",
    )

    // Xcode obtains both bundle versions from the root version.properties via the generation script.
    override val versionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
            ?: "unknown"

    // Deliberately omit the user-assigned device name, identifiers, and other fingerprinting data.
    override val description: String
        get() = UIDevice.currentDevice.let { "${it.systemName} ${it.systemVersion} (${it.model})" }

    override fun httpClient(): HttpClient = IosStartupDiagnostics.stage("HTTP_CLIENT") {
        HttpClient(Darwin) {
            // Only triple-gated synthetic CI probes install this; normal/release networking is unchanged.
            if (IosStartupDiagnostics.enabled) install(IosStartupDiagnostics.offlinePlugin)
            engine {
                configureSession {
                    // Authorization is managed by ApiClient, not a shared cookie jar or disk cache.
                    HTTPShouldSetCookies = false
                    HTTPCookieStorage = null
                    URLCredentialStorage = null
                    URLCache = null
                    requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
                }
            }
        }
    }

    override fun openUrl(url: String) = openExternalHttpsUrl(url)
}

/** Only ordinary HTTPS links without embedded credentials leave the app. No URL is logged. */
internal fun externalHttpsUrlOrNull(value: String): NSURL? {
    val url = NSURL(string = value) ?: return null
    return url.takeIf {
        it.scheme?.equals("https", ignoreCase = true) == true &&
            !it.host.isNullOrBlank() && it.user == null && it.password == null
    }
}

internal fun openExternalHttpsUrl(value: String) {
    val url = externalHttpsUrlOrNull(value) ?: return
    val open = {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
    if (NSThread.isMainThread) open() else dispatch_async(dispatch_get_main_queue()) { open() }
}
