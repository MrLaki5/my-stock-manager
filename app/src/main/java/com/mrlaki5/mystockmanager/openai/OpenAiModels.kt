package com.mrlaki5.mystockmanager.openai

enum class ReasoningEffort(val apiValue: String, val label: String) {
    NONE("none", "None"),
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High");

    companion object {
        val DEFAULT = LOW

        fun of(apiValue: String): ReasoningEffort = entries.firstOrNull { it.apiValue == apiValue } ?: DEFAULT
    }
}

/** A model offered in Settings, with the reasoning efforts OpenAI accepts for it. */
data class OpenAiModel(val id: String, val efforts: List<ReasoningEffort>) {

    /** Falls back to the lowest effort the model accepts, e.g. Sol rejects "none". */
    fun effective(effort: ReasoningEffort): ReasoningEffort = effort.takeIf { it in efforts } ?: efforts.first()
}

object OpenAiModels {
    val LUNA = OpenAiModel("gpt-6-luna", ReasoningEffort.entries)
    val SOL = OpenAiModel("gpt-6.1-sol", listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))

    val ALL = listOf(LUNA, SOL)
    val DEFAULT = LUNA

    /** Saved ids from older versions map to their replacement, so a selection is never lost. */
    fun byId(id: String): OpenAiModel = ALL.firstOrNull { it.id == id } ?: when (id) {
        "gpt-4o" -> SOL
        else -> DEFAULT
    }
}
