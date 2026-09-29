package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** The flags the sync engine reads and writes, narrowed so tests can fake them. */
interface SyncFlags {
    val account: NextcloudAccount?
    var authRejected: Boolean
    var fullCheckRequested: Boolean
    fun fullCheckDone(at: Long)
}

/** Sync settings as observable state; each value is read lazily so construction never opens the Keystore. */
@Singleton
class NextcloudSettings @Inject constructor(
    private val keyStore: SecureKeyStore,
) : SyncFlags {

    private val _enabled by lazy { MutableStateFlow(keyStore.syncEnabled) }
    val enabled: StateFlow<Boolean> get() = _enabled

    private val _wifiOnly by lazy { MutableStateFlow(keyStore.syncWifiOnly) }
    val wifiOnly: StateFlow<Boolean> get() = _wifiOnly

    private val _account by lazy { MutableStateFlow(readAccount()) }
    val savedAccount: StateFlow<NextcloudAccount?> get() = _account
    override val account: NextcloudAccount? get() = _account.value

    private val _lastSuccessAt by lazy { MutableStateFlow(keyStore.syncLastSuccessAt.takeIf { it > 0 }) }
    val lastSuccessAt: StateFlow<Long?> get() = _lastSuccessAt

    private val _lastError by lazy { MutableStateFlow(keyStore.syncLastError.ifBlank { null }) }
    val lastError: StateFlow<String?> get() = _lastError

    override var authRejected: Boolean by keyStore::syncAuthRejected
    override var fullCheckRequested: Boolean by keyStore::syncFullCheckRequested

    val lastFullCheckAt: Long get() = keyStore.syncLastFullCheckAt
    val targetKey: String get() = keyStore.syncTargetKey

    /** For screens that must not read the Keystore on the main thread during startup. */
    fun enabledOffMain(): Flow<Boolean> = flow { emitAll(enabled) }.flowOn(Dispatchers.IO)

    override fun fullCheckDone(at: Long) {
        keyStore.syncLastFullCheckAt = at
    }

    fun saveAccount(account: NextcloudAccount) {
        keyStore.nextcloudServer = account.baseUrl.toString()
        keyStore.nextcloudLogin = account.login
        keyStore.nextcloudAppPassword = account.appPassword
        keyStore.nextcloudUserId = account.userId
        keyStore.nextcloudRoot = NextcloudAccount.displayRoot(account.root)
        keyStore.syncTargetKey = account.targetKey
        keyStore.syncAuthRejected = false
        _account.value = account
        recordError(null)
    }

    fun setEnabled(on: Boolean) {
        keyStore.syncEnabled = on
        _enabled.value = on
    }

    fun setWifiOnly(on: Boolean) {
        keyStore.syncWifiOnly = on
        _wifiOnly.value = on
    }

    fun recordSuccess(at: Long) {
        keyStore.syncLastSuccessAt = at
        _lastSuccessAt.value = at
        recordError(null)
    }

    fun recordError(message: String?) {
        keyStore.syncLastError = message.orEmpty()
        _lastError.value = message
    }

    private fun readAccount(): NextcloudAccount? {
        val baseUrl = keyStore.nextcloudServer.toHttpUrlOrNull() ?: return null
        val root = NextcloudAccount.parseRoot(keyStore.nextcloudRoot).getOrNull() ?: return null
        val login = keyStore.nextcloudLogin.ifBlank { return null }
        val password = keyStore.nextcloudAppPassword.ifBlank { return null }
        val userId = keyStore.nextcloudUserId.ifBlank { return null }
        return NextcloudAccount(baseUrl, login, password, userId, root)
    }
}
