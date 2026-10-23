package io.github.betterzslf.feature.settings

import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val theme: String = "System",
    val dynamicColor: Boolean = false,
    val preferredRunMode: String = "real",
    val samplingSeconds: Int = 10,
    val metricUnits: Boolean = true,
    val skipImportWarning: Boolean = false,
)

class SettingsStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val current = MutableStateFlow(runCatching {
        json.decodeFromString<AppSettings>(store.get(KEY) ?: "{}")
    }.getOrElse {
        mutableError.value = "设置读取失败，暂时使用默认值。"
        AppSettings()
    })
    val settings = current.asStateFlow()
    fun update(transform: (AppSettings) -> AppSettings): Boolean {
        val next = transform(current.value).let { it.copy(samplingSeconds = it.samplingSeconds.coerceIn(10, 60)) }
        return try {
            store.put(KEY, json.encodeToString(next))
            current.value = next
            mutableError.value = null
            true
        } catch (_: Exception) {
            mutableError.value = "设置保存失败，原设置保持不变，请重试。"
            false
        }
    }
    fun reset(): Boolean = try {
        store.remove(KEY)
        mutableError.value = null
        true
    } catch (_: Exception) {
        mutableError.value = "本机设置清理失败，请重试。"
        false
    } finally {
        current.value = AppSettings()
    }
    private companion object { const val KEY = "settings.v1" }
}
