package com.mrlaki5.mystockmanager.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mrlaki5.mystockmanager.generation.GenerationResult
import com.mrlaki5.mystockmanager.metadata.model.IPTC_OBJECT_NAME_MAX
import com.mrlaki5.mystockmanager.metadata.model.MAX_KEYWORDS
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** LFM2.5-VL writes the description, title, keywords and category, all in one conversation about the photo. */
@Singleton
class OnDeviceClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var releaseJob: Job? = null

    suspend fun generate(model: File, imageJpeg: ByteArray, hint: String?): GenerationResult = lock.withLock {
        releaseJob?.cancel()
        try {
            withContext(Dispatchers.IO) { run(model, imageJpeg, hint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            GenerationResult.Terminal("On-device generation is not supported on this phone.", stopsBatch = true)
        } catch (e: Exception) {
            closeAll()
            GenerationResult.Terminal("On-device model failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            releaseJob = scope.launch {
                delay(IDLE_RELEASE_MS)
                lock.withLock { closeAll() }
            }
        }
    }

    /** Frees the model's memory now, e.g. before its file is deleted. */
    suspend fun release() = lock.withLock {
        releaseJob?.cancel()
        closeAll()
    }

    // One conversation, so the photo is encoded once and every later question is a cheap follow-up.
    private fun run(model: File, imageJpeg: ByteArray, hint: String?): GenerationResult {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = TEMPERATURE),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )
        return engineFor(model).createConversation(config).use { conversation ->
            val described = conversation.sendMessage(
                Contents.of(Content.ImageBytes(imageJpeg), Content.Text(OnDeviceText.descriptionPrompt(hint)))
            ).toString()
            val description = OnDeviceText.sentence(described)
            if (description.isEmpty()) return GenerationResult.Terminal("The on-device model returned no description.")
            val title = OnDeviceText.title(conversation.sendMessage(OnDeviceText.TITLE_PROMPT).toString(), description, IPTC_OBJECT_NAME_MAX)

            val keywords = mutableListOf<String>()
            for (question in OnDeviceText.KEYWORD_QUESTIONS) {
                if (keywords.size >= OnDeviceText.KEYWORD_TARGET) break
                val answer = conversation.sendMessage(question, maxOutputToken = MAX_ANSWER_TOKENS).toString()
                OnDeviceText.keyword(answer, keywords)?.let(keywords::add)
            }
            val category = OnDeviceText.category(
                conversation.sendMessage(OnDeviceText.CATEGORY_PROMPT, maxOutputToken = MAX_ANSWER_TOKENS).toString()
            )

            GenerationResult.Success(
                metadata = StockMetadata(
                    title = title,
                    description = description,
                    keywords = OnDeviceText.keywords(hint, keywords).take(MAX_KEYWORDS),
                    category = category,
                    secondaryCategory = null,
                ).normalized(),
                place = null,
                promptTokens = null,
                completionTokens = null,
            )
        }
    }

    private fun engineFor(model: File): Engine {
        engine?.takeIf { it.engineConfig.modelPath == model.absolutePath }?.let { return it }
        engine?.let { runCatching { it.close() } }
        // CPU only: on a Mali GPU LiteRT-LM was no faster and broke constrained decoding.
        return Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                maxNumTokens = MAX_TOKENS,
                maxNumImages = 1,
                cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath,
            )
        ).also {
            it.initialize()
            engine = it
        }
    }

    private fun closeAll() {
        engine?.let { runCatching { it.close() } }
        engine = null
    }

    private companion object {
        // Long enough to keep the model loaded between images of one batch.
        const val IDLE_RELEASE_MS = 60_000L

        // 256 image tokens, the caption, and up to 17 short questions and answers.
        const val MAX_TOKENS = 2048
        const val MAX_OUTPUT_TOKENS = 120
        const val MAX_ANSWER_TOKENS = 12

        // Metadata wants the likeliest answer, not variety.
        const val TEMPERATURE = 0.2
    }
}
