package com.mrlaki5.mystockmanager.openai

import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.MIN_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Outcome shape mirrors what WorkManager needs: [Transient] becomes Result.retry(),
 * [Terminal] becomes Result.failure(). Deciding that here keeps the retry policy in one
 * place instead of spread across the worker.
 */
sealed interface OpenAiResult {
    data class Success(
        val metadata: StockMetadata,
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : OpenAiResult

    data class Transient(val message: String, val retryAfterSeconds: Long?) : OpenAiResult
    data class Terminal(val message: String) : OpenAiResult
}

class OpenAiClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun generate(
        apiKey: String,
        model: String,
        imageBase64Jpeg: String,
        location: String? = null,
    ): OpenAiResult {
        if (apiKey.isBlank()) return OpenAiResult.Terminal("No API key configured.")

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(buildRequestBody(model, imageBase64Jpeg, location).toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return OpenAiResult.Transient("Network error: ${e.message}", null)
        }

        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) return errorFor(it.code, it.header("Retry-After"), body)
            return parseSuccess(body)
        }
    }

    private fun errorFor(code: Int, retryAfter: String?, body: String): OpenAiResult {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: body.take(300)

        return when {
            code == 401 || code == 403 -> OpenAiResult.Terminal("Auth failed ($code): $detail")
            code == 400 -> OpenAiResult.Terminal("Bad request (400): $detail")
            code == 429 -> OpenAiResult.Transient("Rate limited: $detail", retryAfter?.toLongOrNull())
            code >= 500 -> OpenAiResult.Transient("Server error ($code): $detail", retryAfter?.toLongOrNull())
            else -> OpenAiResult.Terminal("HTTP $code: $detail")
        }
    }

    private fun parseSuccess(body: String): OpenAiResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return OpenAiResult.Terminal("Response was not JSON.")

        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: return OpenAiResult.Terminal("Response contained no choices.")

        choice["message"]?.jsonObject?.get("refusal")?.jsonPrimitive?.contentOrNullSafe()?.let {
            return OpenAiResult.Terminal("Model refused: $it")
        }

        val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: return OpenAiResult.Terminal("Response contained no content.")

        val metadata = MetadataParser.parse(content, json)
            ?: return OpenAiResult.Terminal("Could not parse metadata JSON: ${content.take(200)}")

        val usage = root["usage"]?.jsonObject
        return OpenAiResult.Success(
            metadata = metadata,
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    private fun buildRequestBody(
        model: String,
        imageBase64Jpeg: String,
        location: String?,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("temperature", 0.4)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", VisionPrompt.SYSTEM)
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", VisionPrompt.user(location))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64,$imageBase64Jpeg")
                                        put("detail", "low")
                                    }
                                }
                            )
                        }
                    }
                )
            }
            // Structured Outputs: removes the "model returned prose instead of JSON"
            // failure class entirely, so the parser only has to handle value problems.
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "stock_metadata")
                    put("strict", true)
                    put("schema", metadataSchema())
                }
            }
        }

    private fun metadataSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonArray("required") {
            add("title"); add("description"); add("keywords")
            add("shutterstock_category"); add("secondary_category")
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
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Vision calls on a large image are slow; the default 10s read timeout trips constantly.
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content.takeIf { it.isNotBlank() }
