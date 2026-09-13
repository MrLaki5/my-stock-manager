package com.mrlaki5.mystockmanager.ui.settings

import androidx.lifecycle.ViewModel
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keyStore: SecureKeyStore,
) : ViewModel() {

    private val _apiKey = MutableStateFlow(keyStore.apiKey)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(keyStore.model)
    val model: StateFlow<String> = _model.asStateFlow()

    val availableModels = listOf("gpt-4o-mini", "gpt-4o")

    fun setApiKey(value: String) {
        _apiKey.value = value
        keyStore.apiKey = value
    }

    fun setModel(value: String) {
        _model.value = value
        keyStore.model = value
    }
}
