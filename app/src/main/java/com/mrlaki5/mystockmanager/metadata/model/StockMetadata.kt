package com.mrlaki5.mystockmanager.metadata.model

/**
 * Adobe Stock caps keywords at 49, Shutterstock at 50 (with a minimum of 7 and at
 * least one category). A single cap of 49 therefore satisfies both agencies, so we
 * generate one keyword set per image rather than one per agency.
 */
const val MAX_KEYWORDS = 49
const val MIN_KEYWORDS = 7

/**
 * IPTC IIM field limits.
 *
 * Both title fields are far shorter than an editorial caption: Object Name allows 64
 * octets and Headline 256, while a caption with a location and a date routinely runs past
 * either. Rather than throw caption text away to fit, the untruncated caption goes to XMP
 * dc:title, which has no limit and is what agencies read, and each IIM record carries a
 * form trimmed to its own cap so the record stays conformant.
 *
 * See [StockMetadata.objectName] and [StockMetadata.headline].
 */
const val IPTC_OBJECT_NAME_MAX = 64
const val IPTC_HEADLINE_MAX = 256
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
            // Held to the caption limit, not a title limit: this is the full caption, and
            // the per-record trims below are what keep each IIM field legal.
            title = title.trim().take(IPTC_CAPTION_MAX),
            description = description.trim().take(IPTC_CAPTION_MAX),
            keywords = seen.values.map { it.take(IPTC_KEYWORD_MAX) }.take(MAX_KEYWORDS),
        )
    }

    /**
     * The title trimmed to fit IPTC Object Name. Cut on a word boundary rather than
     * mid-word: this is the string a file browser shows as the document title, and
     * "...Large Serbian national f" reads as corruption rather than as an abbreviation.
     */
    val objectName: String get() = title.clampToWord(IPTC_OBJECT_NAME_MAX)

    /**
     * The title trimmed to fit IPTC Headline. Usually the whole caption — a location, a
     * date and one sentence fit inside 256 — but a two-sentence caption can pass it, and
     * losing the tail silently is worse than trimming it deliberately. XMP dc:title still
     * carries the caption entire.
     */
    val headline: String get() = title.clampToWord(IPTC_HEADLINE_MAX)

    val meetsMinimumKeywords: Boolean get() = keywords.size >= MIN_KEYWORDS
}

private fun String.clampToWord(max: Int): String {
    if (length <= max) return this
    val cut = take(max)
    val lastSpace = cut.lastIndexOf(' ')
    // Only honour the word boundary if it leaves something substantial; a caption whose
    // first word is enormous should still be cut rather than reduced to nothing.
    val trimmed = if (lastSpace > max / 2) cut.take(lastSpace) else cut
    return trimmed.trimEnd(' ', ',', '-', ':', ';')
}
