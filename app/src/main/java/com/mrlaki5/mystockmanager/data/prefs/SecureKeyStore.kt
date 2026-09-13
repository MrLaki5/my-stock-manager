package com.mrlaki5.mystockmanager.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the user-supplied OpenAI key in EncryptedSharedPreferences backed by a
 * Keystore master key. The key is never hardcoded, never logged, and is excluded from
 * backup (see backup_rules.xml).
 */
class SecureKeyStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_OPENAI, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OPENAI, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val PREFS_NAME = "stock_secure_prefs"
        private const val KEY_OPENAI = "openai_api_key"
        private const val KEY_MODEL = "openai_model"
    }
}
