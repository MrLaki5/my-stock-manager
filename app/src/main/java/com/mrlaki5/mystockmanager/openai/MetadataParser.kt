package com.mrlaki5.mystockmanager.openai

import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the model's JSON payload and clamps it to agency limits.
 *
 * Structured Outputs makes the *shape* reliable, not the *values* — schema `maxItems`
 * is respected in practice but is not a guarantee we should hand to an upload. The
 * clamp in [StockMetadata.normalized] is the actual enforcement point.
 *
 * The description here is the caption *body*. The caller prepends the location and date
 * with [com.mrlaki5.mystockmanager.metadata.model.EditorialCaption] before it is written.
 */
object MetadataParser {

    fun parse(content: String, json: Json = Json { ignoreUnknownKeys = true }): StockMetadata? {
        val obj = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return null

        val title = obj["title"]?.jsonPrimitive?.content ?: return null
        val description = obj["description"]?.jsonPrimitive?.content ?: return null
        val keywords = obj["keywords"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            ?: return null

        return StockMetadata(
            title = title,
            description = description,
            keywords = keywords,
            category = obj["shutterstock_category"]?.stringOrNull(),
            secondaryCategory = obj["secondary_category"]?.stringOrNull(),
        ).normalized()
    }

    private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
        if (this is JsonNull) null else jsonPrimitive.content.takeIf { it.isNotBlank() }
}
