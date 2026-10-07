package com.mrlaki5.mystockmanager.generation

enum class GenerationProvider(val id: String, val label: String) {
    OPENAI("openai", "OpenAI"),
    ON_DEVICE("on_device", "On-device");

    companion object {
        val DEFAULT = OPENAI

        fun of(id: String): GenerationProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
