package com.mrlaki5.mystockmanager.openai

import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.MIN_KEYWORDS

/** Shutterstock's fixed category list. Supplied to the model as a schema enum so it cannot invent one. */
val SHUTTERSTOCK_CATEGORIES = listOf(
    "Abstract", "Animals/Wildlife", "Arts", "Backgrounds/Textures",
    "Beauty/Fashion", "Buildings/Landmarks", "Business/Finance", "Celebrities",
    "Education", "Food and drink", "Healthcare/Medical", "Holidays",
    "Industrial", "Interiors", "Miscellaneous", "Nature", "Objects",
    "Parks/Outdoor", "People", "Religion", "Science", "Signs/Symbols",
    "Sports/Recreation", "Technology", "Transportation", "Vintage",
)

object VisionPrompt {

    val SYSTEM = """
        You write metadata for stock photography submissions to Adobe Stock and Shutterstock.
        You are given one photograph. Describe only what is actually visible; never invent
        brands, names, or events you cannot see.

        The photographer may state where the photo was taken. Treat any location they give
        you as fact even if you cannot recognise it, and do not contradict it — but do not
        infer any further places, landmarks or regions beyond what they stated and what you
        can actually see.
    """.trimIndent()

    fun user(location: String? = null): String = buildString {
        appendLine(BASE)
        if (!location.isNullOrBlank()) {
            appendLine()
            appendLine(
                "The photographer states this was taken at: $location. Treat it as fact. " +
                    "It is prepended to the caption automatically, so do not open the " +
                    "description with it — but do include the place and its sensible broader " +
                    "terms (for example city, region, country) among the keywords, because " +
                    "buyers search by place. Do not add landmarks or districts you were not " +
                    "told about."
            )
        }
    }

    private val BASE = """
        Produce submission metadata for this image.

        Description: the caption for this photo, one or two sentences, stating plainly what is
        happening and what is visible. Write it as a factual editorial caption rather than
        marketing copy: no "stunning", no "breathtaking", no camera settings, no
        filename-style text. This is the only prose you write and it carries the asset on
        both marketplaces, so it must stand on its own.

        Do not open the description with the place name or the date. The app prepends those
        itself, and a caption that repeats them reads as a stutter.

        Keywords: between $MIN_KEYWORDS and $MAX_KEYWORDS keywords, ordered by relevance with the
        most important first. Both agencies weight leading keywords most heavily, so the first
        ten must be the terms a buyer would actually search for. Include a mix of literal subject
        terms, setting, and conceptual/emotional terms. Single words or short phrases, lowercase,
        no duplicates, no punctuation.

        Category: choose the single best Shutterstock category, and optionally a secondary one.
    """.trimIndent()
}
