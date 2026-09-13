package com.mrlaki5.mystockmanager.metadata.model

/**
 * Adobe Stock caps keywords at 49, Shutterstock at 50 (with a minimum of 7 and at
 * least one category). A single cap of 49 therefore satisfies both agencies, so we
 * generate one keyword set per image rather than one per agency.
 */
const val MAX_KEYWORDS = 49
const val MIN_KEYWORDS = 7

/**
 * IPTC IIM field limits. Exceeding these produces a technically non-conformant record, so
 * values are truncated to fit.
 *
 * The editorial caption lives in the description, whose 2000 octets a caption never comes
 * close to — a location, a date and two sentences run to a few hundred. It is the title
 * that is tightly capped, which is why the prompt asks for a short one.
 */
const val IPTC_OBJECT_NAME_MAX = 64
const val IPTC_CAPTION_MAX = 2000
const val IPTC_KEYWORD_MAX = 64

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
            title = title.trim().take(IPTC_OBJECT_NAME_MAX),
            description = description.trim().take(IPTC_CAPTION_MAX),
            keywords = seen.values.map { it.take(IPTC_KEYWORD_MAX) }.take(MAX_KEYWORDS),
        )
    }

    val meetsMinimumKeywords: Boolean get() = keywords.size >= MIN_KEYWORDS
}
