package com.mrlaki5.mystockmanager.metadata.model

/**
 * Adobe Stock caps keywords at 49, Shutterstock at 50 (with a minimum of 7 and at
 * least one category). A single cap of 49 therefore satisfies both agencies, so we
 * generate one keyword set per image rather than one per agency.
 */
const val MAX_KEYWORDS = 49
const val MIN_KEYWORDS = 7

/** Field limits; the title stays under Adobe Stock's 70 characters, which is why the prompt asks for a short one. */
const val TITLE_MAX = 64
const val DESCRIPTION_MAX = 2000
const val KEYWORD_MAX = 64

/** Keyword order is meaningful: both agencies weight leading keywords more heavily. */
data class StockMetadata(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val category: String? = null,
    val secondaryCategory: String? = null,
) {
    /**
     * Clamps to agency limits. Applied to every model response regardless of what the
     * prompt asked for — a prompt constraint is a request, not a guarantee.
     */
    fun normalized(): StockMetadata {
        val seen = LinkedHashMap<String, String>()
        for (raw in keywords) {
            val kw = raw.trim()
            if (kw.isEmpty()) continue
            seen.putIfAbsent(kw.lowercase(), kw)
        }
        return copy(
            title = title.trim().take(TITLE_MAX),
            description = description.trim().take(DESCRIPTION_MAX),
            keywords = seen.values.map { it.take(KEYWORD_MAX) }.take(MAX_KEYWORDS),
        )
    }

    val meetsMinimumKeywords: Boolean get() = keywords.size >= MIN_KEYWORDS
}
