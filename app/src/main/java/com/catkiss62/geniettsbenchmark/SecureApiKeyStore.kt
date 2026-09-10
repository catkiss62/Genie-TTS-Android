package com.catkiss62.geniettsbenchmark

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the test API key encrypted by a non-exportable Android Keystore key. */
class SecureApiKeyStore(context: Context) {
    companion object {
        private const val KEY_ALIAS = "genie_tts_deepseek_api_key_v1"
        private const val PREFS = "deepseek_private_settings"
        private const val VALUE_IV = "api_key_iv"
        private const val VALUE_CIPHER = "api_key_ciphertext"
        private const val VALUE_MODEL = "model"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveApiKey(value: String) {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "API Key 不能为空" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(VALUE_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(VALUE_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun loadApiKey(): String? {
        val iv = preferences.getString(VALUE_IV, null) ?: return null
        val encrypted = preferences.getString(VALUE_CIPHER, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            clearApiKey()
            null
        }
    }

    fun clearApiKey() {
        preferences.edit().remove(VALUE_IV).remove(VALUE_CIPHER).apply()
    }

    fun saveModel(value: String) {
        preferences.edit().putString(VALUE_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()
    }

    fun loadModel(): String = preferences.getString(VALUE_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
