@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.UnsafeNumber::class,
)
@file:Suppress("CAST_NEVER_SUCCEEDS")

package io.github.betterzslf.platform

import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * App-private, non-synchronizing Keychain records. The service is always included, including
 * in clear(), so unrelated Keychain entries are untouched. No shared access group is used.
 *
 * Callers persist session/settings only, never login passwords. Keychain failures propagate;
 * error messages contain only the operation and OSStatus, never keys, values, or account data.
 */
internal class KeychainKeyValueStore(private val service: String) : KeyValueStore {
    override fun get(key: String): String? = withItemAttributes(key) { attributes ->
        val result = alloc<CFTypeRefVar>().apply { value = null }
        val status = withDictionary(
            attributes + mapOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne),
        ) { SecItemCopyMatching(it, result.ptr) }
        if (status == errSecItemNotFound) return@withItemAttributes null
        checkStatus("read", status)
        val data = CFBridgingRelease(result.value) as? NSData
            ?: error("Keychain read returned an unexpected data type")
        (NSString.create(data, NSUTF8StringEncoding) as String?)
            ?: error("Keychain read returned invalid UTF-8")
    }

    override fun put(key: String, value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Keychain value could not be encoded as UTF-8")
        val retainedData = CFBridgingRetain(data)
        try {
            withItemAttributes(key) { attributes ->
                val update = mapOf(
                    kSecValueData to retainedData,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                )
                val status = withDictionary(attributes + update) { SecItemAdd(it, null) }
                if (status == errSecDuplicateItem) {
                    withDictionary(attributes) { query ->
                        withDictionary(update) { changes ->
                            checkStatus("update", SecItemUpdate(query, changes))
                        }
                    }
                } else {
                    checkStatus("write", status)
                }
            }
        } finally {
            if (retainedData != null) CFRelease(retainedData)
        }
    }

    override fun remove(key: String) = delete(key)

    override fun clear() = delete(null)

    private fun delete(key: String?) {
        withItemAttributes(key) { attributes ->
            val status = withDictionary(attributes) { SecItemDelete(it) }
            if (status != errSecItemNotFound) checkStatus("delete", status)
        }
    }

    private fun <T> withItemAttributes(
        key: String?,
        operation: MemScope.(Map<CFStringRef?, CFTypeRef?>) -> T,
    ): T = memScoped {
        val retainedService = CFBridgingRetain(service)
        val retainedKey = key?.let { CFBridgingRetain(it) }
        try {
            val attributes = mutableMapOf<CFStringRef?, CFTypeRef?>(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to retainedService,
                kSecAttrSynchronizable to kCFBooleanFalse,
            )
            if (key != null) attributes[kSecAttrAccount] = retainedKey
            operation(attributes)
        } finally {
            if (retainedKey != null) CFRelease(retainedKey)
            if (retainedService != null) CFRelease(retainedService)
        }
    }

    /** All values remain retained by the enclosing operation until this dictionary is released. */
    private fun <T> MemScope.withDictionary(
        attributes: Map<CFStringRef?, CFTypeRef?>,
        operation: (CFDictionaryRef?) -> T,
    ): T {
        val keys = allocArrayOf(*attributes.keys.toTypedArray())
        val values = allocArrayOf(*attributes.values.toTypedArray())
        val dictionary = CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            attributes.size.convert(),
            null,
            null,
        ) ?: error("Keychain query allocation failed")
        try {
            return operation(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    private fun checkStatus(operation: String, status: OSStatus) {
        check(status == errSecSuccess) { "Keychain $operation failed (OSStatus=$status)" }
    }
}
