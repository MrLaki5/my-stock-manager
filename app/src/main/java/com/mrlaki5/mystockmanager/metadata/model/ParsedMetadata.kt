package com.mrlaki5.mystockmanager.metadata.model

/** A CSV row or a file's embedded metadata as the images table stores it: [description] is the caption body. */
data class ParsedMetadata(
    val title: String?,
    val description: String?,
    val captionPlace: String,
    val keywords: List<String>,
    val category: String?,
) {
    val generated: Boolean get() = !title.isNullOrBlank() && !description.isNullOrBlank() && keywords.isNotEmpty()

    companion object {
        /** Splits off a caption lead [EditorialCaption.build] could have written, and clamps to agency limits. */
        fun of(
            title: String?,
            caption: String?,
            keywords: List<String>,
            category: String?,
            capturedOn: String?,
            knownLocation: String?,
        ): ParsedMetadata {
            val parts = caption?.let { EditorialCaption.parse(it, capturedOn, knownLocation) }
            val clamped = StockMetadata(title.orEmpty(), parts?.body.orEmpty(), keywords, category).normalized()
            return ParsedMetadata(
                title = clamped.title.ifEmpty { null },
                description = clamped.description.ifEmpty { null },
                captionPlace = parts?.location.orEmpty(),
                keywords = clamped.keywords,
                category = category?.trim()?.ifEmpty { null },
            )
        }
    }
}
