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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit

/**
 * Outcome shape mirrors what WorkManager needs: [Transient] becomes Result.retry(),
 * [Terminal] becomes Result.failure(). Deciding that here keeps the retry policy in one
 * place instead of spread across the worker.
 */
sealed interface OpenAiResult {
    data class Success(
        val metadata: StockMetadata,
        /** The place the hint named, for the caption lead; null when it named none. */
        val place: String?,
        val promptTokens: Int?,
        val completionTokens: Int?,
    ) : OpenAiResult

    data class Transient(val message: String, val retryAfterSeconds: Long?) : OpenAiResult

    /** [stopsBatch] marks account-wide failures (no credits, bad key) every queued image would hit too. */
    data class Terminal(val message: String, val stopsBatch: Boolean = false) : OpenAiResult
}

class OpenAiClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val endpoint: String = ENDPOINT,
) {

    fun generate(
        apiKey: String,
        model: String,
        reasoningEffort: ReasoningEffort,
        imageBase64Jpeg: String,
        hint: String? = null,
        systemPrompt: String = VisionPrompt.DEFAULT_SYSTEM,
    ): OpenAiResult {
        if (apiKey.isBlank()) return OpenAiResult.Terminal("No OpenAI API key set. Add one in Settings.", stopsBatch = true)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(buildRequestBody(model, reasoningEffort, imageBase64Jpeg, hint, systemPrompt).toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return OpenAiResult.Transient(networkMessage(e), null)
        }

        response.use {
            val body = try {
                it.body.string()
            } catch (e: IOException) {
                return OpenAiResult.Transient(networkMessage(e), null)
            }
            if (!it.isSuccessful) return errorFor(model, it.code, it.header("Retry-After"), body)
            return parseSuccess(body)
        }
    }

    private fun networkMessage(e: IOException): String = when (e) {
        is UnknownHostException -> "No internet connection, or OpenAI could not be reached."
        is SocketTimeoutException -> "OpenAI took too long to respond."
        is SSLException -> "Secure connection to OpenAI failed: ${e.message ?: e.javaClass.simpleName}"
        else -> "Network error: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun errorFor(model: String, code: Int, retryAfter: String?, body: String): OpenAiResult {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val errorCode = error?.get("code")?.jsonPrimitive?.contentOrNullSafe()
        val errorType = error?.get("type")?.jsonPrimitive?.contentOrNullSafe()
        val detail = error?.get("message")?.jsonPrimitive?.contentOrNullSafe() ?: body.take(300).ifBlank { "no details" }
        val retryAfterSeconds = retryAfter?.toLongOrNull()

        return when {
            // OpenAI reports an empty balance as a 429 too, but retrying it can never succeed.
            errorCode == INSUFFICIENT_QUOTA || errorType == INSUFFICIENT_QUOTA -> OpenAiResult.Terminal(
                "Your OpenAI account is out of credits or over its spending limit. Add credits at platform.openai.com, then try again.",
                stopsBatch = true,
            )
            code == 401 -> OpenAiResult.Terminal("OpenAI rejected the API key. Check it in Settings.", stopsBatch = true)
            code == 403 -> OpenAiResult.Terminal("OpenAI denied access: $detail", stopsBatch = true)
            code == 404 || errorCode == "model_not_found" ->
                OpenAiResult.Terminal("Model $model is not available to your OpenAI account. Pick another in Settings.", stopsBatch = true)
            code == 400 -> OpenAiResult.Terminal("OpenAI rejected the request: $detail")
            code == 408 -> OpenAiResult.Transient("OpenAI timed out.", retryAfterSeconds)
            code == 429 -> OpenAiResult.Transient("OpenAI rate limit reached: $detail", retryAfterSeconds)
            code >= 500 -> OpenAiResult.Transient("OpenAI is having problems ($code): $detail", retryAfterSeconds)
            else -> OpenAiResult.Terminal("OpenAI error $code: $detail")
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
            place = MetadataParser.parsePlace(content, json),
            promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
            completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    private fun buildRequestBody(
        model: String,
        reasoningEffort: ReasoningEffort,
        imageBase64Jpeg: String,
        hint: String?,
        systemPrompt: String,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            // GPT-6 models are reasoning models: they take an effort level instead of a temperature.
            put("reasoning_effort", reasoningEffort.apiValue)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", VisionPrompt.user(hint))
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
            }
            putJsonObject("place") {
                putJsonArray("type") { add("string"); add("null") }
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val INSUFFICIENT_QUOTA = "insufficient_quota"
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
