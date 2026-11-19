package io.github.betterzslf.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.text.AppText
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A fresh application-scoped store; no global platform owner or callback is required for cold start. */
fun newAndroidEncryptedStore(context: Context): KeyValueStore = EncryptedStore(context.applicationContext)

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
        }.getOrElse { throw IllegalStateException(AppText.App.LOCAL_DATA_TEMPORARILY_UNAVAILABLE_TITLE) }
    }
    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(key, encoded).commit()) { AppText.Common.LOCAL_DATA_SAVE_FAILED }
    }
    override fun remove(key: String) { check(preferences.edit().remove(key).commit()) { AppText.App.PARTIAL_LOCAL_DATA_CLEAR_FAILED } }
    override fun clear() { check(preferences.edit().clear().commit()) { AppText.App.PARTIAL_LOCAL_DATA_CLEAR_FAILED } }
}
