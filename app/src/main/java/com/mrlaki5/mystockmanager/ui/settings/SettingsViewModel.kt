package com.mrlaki5.mystockmanager.ui.settings

import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import com.mrlaki5.mystockmanager.nextcloud.NextcloudAccount
import com.mrlaki5.mystockmanager.nextcloud.NextcloudSettings
import com.mrlaki5.mystockmanager.nextcloud.PullState
import com.mrlaki5.mystockmanager.nextcloud.SyncCoordinator
import com.mrlaki5.mystockmanager.openai.OpenAiModel
import com.mrlaki5.mystockmanager.openai.OpenAiModels
import com.mrlaki5.mystockmanager.openai.ReasoningEffort
import com.mrlaki5.mystockmanager.openai.VisionPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import javax.inject.Inject

/** The NextCloud form. Nothing is saved until the connection has been tested with it. */
data class AccountDraft(
    val server: String,
    val login: String,
    val appPassword: String,
    val folder: String,
) {
    val canSave: Boolean get() = server.isNotBlank() && login.isNotBlank() && appPassword.isNotBlank()

    companion object {
        fun of(account: NextcloudAccount?) = AccountDraft(
            server = account?.baseUrl?.toString()?.removeSuffix("/").orEmpty(),
            login = account?.login.orEmpty(),
            appPassword = account?.appPassword.orEmpty(),
            folder = account?.root?.let(NextcloudAccount::displayRoot) ?: NextcloudAccount.DEFAULT_ROOT,
        )
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keyStore: SecureKeyStore,
    private val nextcloud: NextcloudSettings,
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    private val _apiKey = MutableStateFlow(keyStore.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(keyStore.model)
    val model: StateFlow<OpenAiModel> = _model.asStateFlow()

    val availableModels = OpenAiModels.ALL

    private val _reasoningEffort = MutableStateFlow(keyStore.reasoningEffort)

    /** What the next generation will actually send, after clamping to the model's supported levels. */
    val reasoningEffort: StateFlow<ReasoningEffort> = combine(_model, _reasoningEffort) { model, effort ->
        model.effective(effort)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, keyStore.model.effective(keyStore.reasoningEffort))

    private val _systemPrompt = MutableStateFlow(keyStore.systemPrompt.ifBlank { VisionPrompt.DEFAULT_SYSTEM })
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _saved = MutableStateFlow(AccountDraft.of(nextcloud.account))

    private val _draft = MutableStateFlow(_saved.value)
    val draft: StateFlow<AccountDraft> = _draft.asStateFlow()

    val dirty: StateFlow<Boolean> = combine(_draft, _saved) { draft, saved -> draft != saved }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasAccount: StateFlow<Boolean> = nextcloud.savedAccount.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, nextcloud.account != null)

    val syncEnabled: StateFlow<Boolean> = nextcloud.enabled
    val wifiOnly: StateFlow<Boolean> = nextcloud.wifiOnly

    val status: StateFlow<String> = coordinator.status(::formatSyncTime)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val pulling: StateFlow<Boolean> = coordinator.observePull()
        .map { it is PullState.Running || it is PullState.Waiting }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Only a pull seen finishing while this screen is open is announced, not one from last week.
        viewModelScope.launch {
            var active = false
            coordinator.observePull().collect { state ->
                when (state) {
                    is PullState.Running, is PullState.Waiting -> active = true
                    is PullState.Finished -> if (active) _message.value = state.summary.describe()
                    is PullState.Failed -> if (active) _message.value = "Pull failed: ${state.message}"
                    PullState.Idle -> Unit
                }
                if (state is PullState.Finished || state is PullState.Failed || state == PullState.Idle) active = false
            }
        }
    }

    fun setApiKey(value: String) {
        _apiKey.value = value
        keyStore.apiKey = value
    }

    fun setModel(value: OpenAiModel) {
        _model.value = value
        keyStore.model = value
    }

    fun setReasoningEffort(value: ReasoningEffort) {
        _reasoningEffort.value = value
        keyStore.reasoningEffort = value
    }

    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value.ifBlank { VisionPrompt.DEFAULT_SYSTEM }
        keyStore.systemPrompt = if (value.isBlank() || value == VisionPrompt.DEFAULT_SYSTEM) "" else value
    }

    fun resetSystemPrompt() = setSystemPrompt(VisionPrompt.DEFAULT_SYSTEM)

    fun setServer(value: String) = edit { it.copy(server = value) }

    fun setLogin(value: String) = edit { it.copy(login = value) }

    fun setAppPassword(value: String) = edit { it.copy(appPassword = value) }

    fun setFolder(value: String) = edit { it.copy(folder = value) }

    fun saveAccount() {
        val draft = _draft.value
        if (!draft.canSave || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            coordinator.applyAccount(draft.server, draft.login, draft.appPassword, draft.folder)
                .onSuccess { account ->
                    val saved = AccountDraft.of(account)
                    _saved.value = saved
                    _draft.value = saved
                    _message.value = "Connected as ${account.userId}"
                }
                .onFailure { _message.value = it.message ?: "Could not connect to NextCloud" }
            _busy.value = false
        }
    }

    fun setSyncEnabled(on: Boolean) {
        viewModelScope.launch { coordinator.setEnabled(on) }
    }

    fun setWifiOnly(on: Boolean) {
        viewModelScope.launch { coordinator.setWifiOnly(on) }
    }

    fun syncNow() {
        viewModelScope.launch {
            coordinator.syncNow()
            _message.value = "Checking NextCloud…"
        }
    }

    fun pullNow() {
        viewModelScope.launch {
            coordinator.pullNow()
            _message.value = "Pulling from NextCloud…"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun edit(change: (AccountDraft) -> AccountDraft) {
        _draft.value = change(_draft.value)
    }

    private fun formatSyncTime(at: Long): String =
        DateUtils.formatSameDayTime(at, System.currentTimeMillis(), DateFormat.MEDIUM, DateFormat.SHORT).toString()
}
