package com.mrlaki5.mystockmanager.metadata

/** One image's line in an event's CSV; [description] is the full caption. Read back, null is a field the CSV does not give. */
data class CsvRow(
    val fileName: String,
    val title: String,
    val description: String?,
    val keywords: List<String>?,
    val category: String?,
)

/** One file per event for both agencies: Adobe Stock reads Title, Keywords, Category; Shutterstock Description, Keywords, Categories. */
object AgencyCsv {

    const val FILE_NAME = "metadata.csv"

    private val HEADER = listOf("Filename", "Title", "Description", "Keywords", "Category", "Categories")

    fun write(rows: List<CsvRow>): ByteArray = buildString {
        line(HEADER)
        for (row in rows) {
            line(
                listOf(
                    row.fileName,
                    row.title,
                    row.description.orEmpty(),
                    row.keywords.orEmpty().joinToString(", "),
                    row.category?.let(::adobeCategory)?.toString().orEmpty(),
                    row.category.orEmpty(),
                ),
            )
        }
    }.toByteArray(Charsets.UTF_8)

    /** Rows by file name; a file without a Filename or Title column yields none, and a row with a blank title is not filled in yet. */
    fun read(bytes: ByteArray): Map<String, CsvRow> {
        val records = parse(bytes.toString(Charsets.UTF_8).removePrefix("﻿"))
        val header = records.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyMap()
        fun column(name: String) = header.indexOf(name.lowercase()).takeIf { it >= 0 }
        val fileName = column("Filename") ?: return emptyMap()
        val title = column("Title") ?: return emptyMap()
        val description = column("Description")
        val keywords = column("Keywords")
        val categories = column("Categories")

        return records.drop(1).mapNotNull { record ->
            fun field(index: Int) = record.getOrNull(index)?.trim().orEmpty()
            CsvRow(
                fileName = field(fileName).ifEmpty { return@mapNotNull null },
                title = field(title).ifEmpty { return@mapNotNull null },
                description = description?.let(::field),
                // The app never writes a row without keywords, so a blank cell clears nothing.
                keywords = keywords?.let(::field)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null },
                // Shutterstock allows two; the app keeps one. Blank is no category.
                category = categories?.let(::field)?.substringBefore(',')?.trim(),
            )
        }.associateBy { it.fileName }
    }

    /** Adobe Stock's numbered category closest to a Shutterstock one; null leaves Adobe's optional column empty. */
    fun adobeCategory(shutterstock: String): Int? = ADOBE_CATEGORY[shutterstock]

    private fun StringBuilder.line(fields: List<String>) {
        fields.joinTo(this, ",") { field ->
            if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + field.replace("\"", "\"\"") + "\""
            } else {
                field
            }
        }
        append("\r\n")
    }

    /** RFC 4180: quoted fields may hold commas, doubled quotes and line breaks. */
    private fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> field.append(c)
                c == ',' -> { record += field.toString(); field.clear() }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    record += field.toString(); field.clear()
                    if (record.any { it.isNotBlank() }) records += record
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        record += field.toString()
        if (record.any { it.isNotBlank() }) records += record
        return records
    }

    // Adobe Stock: 1 Animals, 2 Buildings and Architecture, 3 Business, 7 Food, 8 Graphic Resources,
    // 10 Industry, 11 Landscapes, 12 Lifestyle, 13 People, 15 Culture and Religion, 16 Science,
    // 18 Sports, 19 Technology, 20 Transport.
    private val ADOBE_CATEGORY = mapOf(
        "Abstract" to 8,
        "Animals/Wildlife" to 1,
        "Arts" to 15,
        "Backgrounds/Textures" to 8,
        "Beauty/Fashion" to 12,
        "Buildings/Landmarks" to 2,
        "Business/Finance" to 3,
        "Celebrities" to 13,
        "Education" to 12,
        "Food and drink" to 7,
        "Healthcare/Medical" to 16,
        "Holidays" to 15,
        "Industrial" to 10,
        "Interiors" to 2,
        "Nature" to 11,
        "Objects" to 12,
        "Parks/Outdoor" to 11,
        "People" to 13,
        "Religion" to 15,
        "Science" to 16,
        "Signs/Symbols" to 8,
        "Sports/Recreation" to 18,
        "Technology" to 19,
        "Transportation" to 20,
    )
}
