package com.mrlaki5.mystockmanager.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

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

    /** Blank means the built-in default, so prompt improvements still reach users who never edited it. */
    var systemPrompt: String by string(KEY_SYSTEM_PROMPT)

    // The NextCloud app password lives here for the same reasons as the OpenAI key.
    var nextcloudServer: String by string(KEY_NC_SERVER)
    var nextcloudLogin: String by string(KEY_NC_LOGIN)
    var nextcloudAppPassword: String by string(KEY_NC_PASSWORD)
    var nextcloudUserId: String by string(KEY_NC_USER_ID)
    var nextcloudRoot: String by string(KEY_NC_ROOT)

    var syncEnabled: Boolean by boolean(KEY_SYNC_ENABLED, default = false)
    var syncWifiOnly: Boolean by boolean(KEY_SYNC_WIFI_ONLY, default = true)

    // Kept here rather than in stock.db, which is backed up: a restore then forces a clean re-sync.
    var syncTargetKey: String by string(KEY_SYNC_TARGET)
    var syncAuthRejected: Boolean by boolean(KEY_SYNC_AUTH_REJECTED, default = false)
    var syncFullCheckRequested: Boolean by boolean(KEY_SYNC_FULL_CHECK, default = false)
    var syncLastFullCheckAt: Long by long(KEY_SYNC_LAST_FULL_CHECK)
    var syncLastSuccessAt: Long by long(KEY_SYNC_LAST_SUCCESS)
    var syncLastError: String by string(KEY_SYNC_LAST_ERROR)

    private fun string(key: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getString(key, "").orEmpty()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            prefs.edit().putString(key, value).apply()
    }

    private fun boolean(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            prefs.edit().putBoolean(key, value).apply()
    }

    private fun long(key: String) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getLong(key, 0L)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) =
            prefs.edit().putLong(key, value).apply()
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val PREFS_NAME = "stock_secure_prefs"
        private const val KEY_OPENAI = "openai_api_key"
        private const val KEY_MODEL = "openai_model"
        private const val KEY_SYSTEM_PROMPT = "openai_system_prompt"
        private const val KEY_NC_SERVER = "nextcloud_server"
        private const val KEY_NC_LOGIN = "nextcloud_login"
        private const val KEY_NC_PASSWORD = "nextcloud_app_password"
        private const val KEY_NC_USER_ID = "nextcloud_user_id"
        private const val KEY_NC_ROOT = "nextcloud_root"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_SYNC_WIFI_ONLY = "sync_wifi_only"
        private const val KEY_SYNC_TARGET = "sync_target_key"
        private const val KEY_SYNC_AUTH_REJECTED = "sync_auth_rejected"
        private const val KEY_SYNC_FULL_CHECK = "sync_full_check_requested"
        private const val KEY_SYNC_LAST_FULL_CHECK = "sync_last_full_check_at"
        private const val KEY_SYNC_LAST_SUCCESS = "sync_last_success_at"
        private const val KEY_SYNC_LAST_ERROR = "sync_last_error"
    }
}
