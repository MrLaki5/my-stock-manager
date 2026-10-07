package com.mrlaki5.mystockmanager.openai

import com.mrlaki5.mystockmanager.generation.GenerationResult
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OpenAiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        client = OpenAiClient(client = http, endpoint = server.url("/v1/chat/completions").toString())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, errorCode: String? = null, type: String? = null, message: String = "boom") {
        val body = """{"error":{"message":"$message","type":${type?.let { "\"$it\"" }},"code":${errorCode?.let { "\"$it\"" }}}}"""
        server.enqueue(MockResponse(code = code, body = body))
    }

    private fun generate() = client.generate("sk-test", "gpt-6-luna", ReasoningEffort.LOW, "AAAA")

    @Test
    fun `out of credits stops the batch instead of retrying`() {
        respond(429, errorCode = "insufficient_quota", type = "insufficient_quota")
        val result = generate()
        assertTrue(result is GenerationResult.Terminal)
        result as GenerationResult.Terminal
        assertTrue(result.stopsBatch)
        assertTrue(result.message.contains("out of credits"))
    }

    @Test
    fun `plain rate limit is retried`() {
        respond(429, errorCode = "rate_limit_exceeded")
        assertTrue(generate() is GenerationResult.Transient)
    }

    @Test
    fun `rejected key and missing model stop the batch`() {
        respond(401, errorCode = "invalid_api_key")
        respond(404, errorCode = "model_not_found")
        listOf(generate(), generate()).forEach {
            assertTrue(it is GenerationResult.Terminal && it.stopsBatch)
        }
    }

    @Test
    fun `bad request fails only that image`() {
        respond(400, message = "image too small")
        val result = generate() as GenerationResult.Terminal
        assertFalse(result.stopsBatch)
        assertTrue(result.message.contains("image too small"))
    }

    @Test
    fun `server errors are retried`() {
        respond(503)
        assertTrue(generate() is GenerationResult.Transient)
    }

    @Test
    fun `unreachable host is a transient network failure`() {
        val offline = OpenAiClient(endpoint = "https://openai.invalid/v1/chat/completions")
        val result = offline.generate("sk-test", "gpt-6-luna", ReasoningEffort.LOW, "AAAA")
        assertTrue(result is GenerationResult.Transient)
    }

    @Test
    fun `timeout is a transient failure`() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        val result = generate()
        assertTrue(result is GenerationResult.Transient)
        assertEquals("OpenAI took too long to respond.", (result as GenerationResult.Transient).message)
    }

    @Test
    fun `blank key never reaches the network`() {
        val result = client.generate("", "gpt-6-luna", ReasoningEffort.LOW, "AAAA")
        assertTrue(result is GenerationResult.Terminal && result.stopsBatch)
        assertEquals(0, server.requestCount)
    }
}
