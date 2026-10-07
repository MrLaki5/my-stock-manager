package com.mrlaki5.mystockmanager.generation

import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.MIN_KEYWORDS
import com.mrlaki5.mystockmanager.openai.SHUTTERSTOCK_CATEGORIES
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The JSON shape every generator is constrained to, so [com.mrlaki5.mystockmanager.openai.MetadataParser] serves both. */
object MetadataSchema {

    val json: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("title"); add("description"); add("keywords")
            add("shutterstock_category"); add("secondary_category"); add("place")
        }
        putJsonObject("properties") {
            putJsonObject("title") {
                put("type", "string")
            }
            putJsonObject("description") {
                put("type", "string")
            }
            putJsonObject("keywords") {
                put("type", "array")
                put("minItems", MIN_KEYWORDS)
                put("maxItems", MAX_KEYWORDS)
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("shutterstock_category") {
                put("type", "string")
                putJsonArray("enum") { SHUTTERSTOCK_CATEGORIES.forEach { add(it) } }
            }
            putJsonObject("secondary_category") {
                putJsonArray("type") { add("string"); add("null") }
                putJsonArray("enum") { SHUTTERSTOCK_CATEGORIES.forEach { add(it) }; add(JsonNull) }
            }
            putJsonObject("place") {
                putJsonArray("type") { add("string"); add("null") }
            }
        }
    }
}
