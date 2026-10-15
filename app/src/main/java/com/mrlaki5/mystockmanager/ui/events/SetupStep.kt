package com.mrlaki5.mystockmanager.ui.events

import com.mrlaki5.mystockmanager.generation.GenerationProvider
import com.mrlaki5.mystockmanager.ondevice.ModelState

/** What a new user still has to do before images can get metadata; null once the chosen provider works. */
enum class SetupStep {
    CHOOSE_PROVIDER,
    MODEL_DOWNLOADING,
    OPENAI_KEY_MISSING,
    ON_DEVICE_UNSUPPORTED;

    companion object {
        fun of(provider: GenerationProvider, hasApiKey: Boolean, model: ModelState): SetupStep? = when (provider) {
            GenerationProvider.OPENAI -> if (hasApiKey) null else OPENAI_KEY_MISSING
            GenerationProvider.ON_DEVICE -> when (model) {
                ModelState.Ready -> null
                is ModelState.Downloading, ModelState.Verifying -> MODEL_DOWNLOADING
                ModelState.Unsupported -> ON_DEVICE_UNSUPPORTED
                ModelState.Missing, is ModelState.Failed -> CHOOSE_PROVIDER
            }
        }
    }
}
