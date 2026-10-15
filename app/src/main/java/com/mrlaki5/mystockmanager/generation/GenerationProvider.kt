package com.mrlaki5.mystockmanager.generation

enum class GenerationProvider(val id: String, val label: String) {
    // Declaration order is the order of the settings chips.
    ON_DEVICE("on_device", "On-device"),
    OPENAI("openai", "OpenAI");

    companion object {
        val DEFAULT = ON_DEVICE

        fun of(id: String): GenerationProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
