@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package io.github.betterzslf.platform

import io.github.betterzslf.AppGraph
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.*
import platform.Foundation.*
import kotlin.native.Platform

/** Explicit CI-only observability. Never inspect or serialize a real storage value or exception message. */
internal object IosStartupDiagnostics {
    private val environment = NSProcessInfo.processInfo.environment
    private val phase = (environment["BETTER_ZSLF_STARTUP_PROBE"] as? String)?.takeIf { it == "seed" || it == "verify" }
    private val namespace = (environment["BETTER_ZSLF_PROBE_NAMESPACE"] as? String)
        ?.takeIf { Regex("[A-Za-z0-9_-]{1,80}").matches(it) }
    val enabled: Boolean = Platform.isDebugBinary &&
        NSBundle.mainBundle.objectForInfoDictionaryKey("DTPlatformName") == "iphonesimulator" &&
        phase != null && namespace != null

    private val events = mutableListOf<JsonObject>()
    private var probeComplete = false
    private var probeOk = false
    private var graphComplete = false
    private var graphOk = false
    private var sessionAbsent: Boolean? = null
    private var probeStarted = false

    // A configured plugin survives ApiClient's client.config copy; not a Darwin request interceptor.
    val offlinePlugin by lazy {
        createClientPlugin("IosStartupProbeOffline") {
            onRequest { _, _ -> error("CI startup probe networking is disabled") }
        }
    }

    fun keychainStatus(service: String, operation: String, status: Int) {
        if (!enabled) return
        val domain = if (service.contains(".ci-probe.")) "PROBE" else "APP"
        event("${domain}_KEYCHAIN_${operation.uppercase()}", when (status) {
            0 -> "OK"
            -25300 -> "MISSING"
            else -> "OSSTATUS"
        }, status)
    }

    fun bridgeType(stage: String, valid: Boolean) {
        if (enabled) event(stage, if (valid) "OK" else "TYPE_MISMATCH")
    }

    fun <T> stage(name: String, operation: () -> T): T {
        if (!enabled) return operation()
        event(name, "BEGIN")
        return try {
            operation().also { event(name, "OK") }
        } catch (error: Throwable) {
            event(name, errorKind(error))
            throw error
        }
    }

    fun createGraph(operation: () -> AppGraph): AppGraph {
        check(enabled)
        event("GRAPH", "BEGIN")
        return try {
            operation().also { graph ->
                sessionAbsent = graph.sessions.session.value == null
                graphComplete = true
                graphOk = true
                event("GRAPH", "OK")
            }
        } catch (error: Throwable) {
            graphComplete = true
            graphOk = false
            event("GRAPH", errorKind(error))
            throw error
        }
    }

    fun runProbeIfEnabled() {
        if (!enabled || probeStarted) return
        probeStarted = true
        event("PROBE", "BEGIN")
        val prefix = "${NSBundle.mainBundle.bundleIdentifier ?: "io.github.betterzslf"}.ci-probe.${requireNotNull(namespace)}"
        val first = KeychainKeyValueStore("$prefix.a")
        val second = KeychainKeyValueStore("$prefix.b")
        try {
            if (phase == "seed") {
                first.clear()
                second.clear()
                verify("READ_MISSING", first.get("absent") == null)
                first.put("item", "synthetic-alpha")
                verify("CREATE_READ", first.get("item") == "synthetic-alpha")
                first.put("item", "synthetic-beta")
                verify("UPDATE_READ", first.get("item") == "synthetic-beta")
                first.put("empty", "")
                verify("EMPTY_VALUE", first.get("empty") == "")
                first.put("utf8", "synthetic-中文")
                verify("UNICODE_VALUE", first.get("utf8") == "synthetic-中文")
                first.remove("item")
                verify("DELETE_READ", first.get("item") == null)
                second.put("isolation", "synthetic-other")
                first.clear()
                verify("CLEAR_NAMESPACE", first.get("empty") == null)
                verify("KEEP_OTHER_NAMESPACE", second.get("isolation") == "synthetic-other")
                second.clear()
                first.put("restart", "synthetic-persisted")
                verify("SEED_RESTART", first.get("restart") == "synthetic-persisted")
            } else {
                verify("RESTORE_AFTER_RESTART", first.get("restart") == "synthetic-persisted")
                first.remove("restart")
                verify("DELETE_AFTER_RESTART", first.get("restart") == null)
                first.clear()
                second.clear()
                verify("FINAL_NAMESPACE_EMPTY", second.get("isolation") == null)
            }
            probeOk = true
        } catch (error: Throwable) {
            event("PROBE_EXCEPTION", errorKind(error))
            // Only the two synthetic services belong to this operation. No app storage clear/fallback.
            runCatching { first.clear() }
            runCatching { second.clear() }
        } finally {
            probeComplete = true
            event("PROBE", if (probeOk) "OK" else "FAILED")
        }
    }

    private fun verify(stage: String, value: Boolean) {
        event(stage, if (value) "OK" else "ASSERTION_FAILED")
        check(value) { "Synthetic Keychain assertion failed" }
    }

    private fun errorKind(error: Throwable): String = when (error) {
        is ClassCastException -> "CLASS_CAST"
        is NullPointerException -> "NULL_POINTER"
        is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
        is IllegalStateException -> "ILLEGAL_STATE"
        else -> "OTHER_EXCEPTION"
    }

    private fun event(stage: String, result: String, status: Int? = null) {
        if (!enabled) return
        check(Regex("[A-Z_]{1,64}").matches(stage) && Regex("[A-Z_]{1,64}").matches(result))
        if (events.size < 256) events += buildJsonObject {
            put("stage", stage)
            put("result", result)
            status?.let { put("status", it) }
        }
        val report = buildJsonObject {
            put("schema", 1)
            put("phase", requireNotNull(phase))
            put("probeComplete", probeComplete)
            put("probeOk", probeOk)
            put("graphComplete", graphComplete)
            put("graphOk", graphOk)
            put("sessionAbsent", sessionAbsent?.let(::JsonPrimitive) ?: JsonNull)
            put("events", JsonArray(events.toList()))
        }.toString().encodeToByteArray()
        // Avoid NSString casts in the diagnostic writer: they are among the bridges under observation.
        runCatching {
            val directory = NSHomeDirectory() + "/Library/Caches"
            check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
            val data = report.usePinned { NSData.create(bytes = it.addressOf(0), length = report.size.convert()) }
            check(data.writeToFile("$directory/better-zslf-ci-startup.json", atomically = true))
        }.onFailure {
            NSLog("BETTER_ZSLF_CI_DIAGNOSTIC_REPORT_WRITE_FAILED")
        }
    }
}
