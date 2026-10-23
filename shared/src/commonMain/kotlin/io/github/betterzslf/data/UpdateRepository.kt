package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint

class UpdateRepository(private val api: ApiClient) {
    suspend fun check() = api.execute(ApiEndpoint.CHECK_VERSION)

    companion object {
        /** Invalid/older school-app versions never advertise a better-zslf upgrade. */
        fun isNewerVersion(candidate: String, installed: String): Boolean {
            val next = parseVersion(candidate) ?: return false
            val current = parseVersion(installed) ?: return false
            repeat(maxOf(next.size, current.size)) { index ->
                val comparison = (next.getOrElse(index) { 0L }).compareTo(current.getOrElse(index) { 0L })
                if (comparison != 0) return comparison > 0
            }
            return false
        }

        private fun parseVersion(value: String): List<Long>? {
            val plain = value.trim().removePrefix("v")
            if (plain.isBlank()) return null
            val components = plain.split('.')
            return components.map { component ->
                if (component.isEmpty() || component.any { it !in '0'..'9' }) return null
                component.toLongOrNull() ?: return null
            }
        }
    }
}
