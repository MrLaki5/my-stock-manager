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

/** LFM2.5-VL writes the caption and title; SigLIP 2 picks keywords, since small VLMs loop on long lists. */
@Singleton
class OnDeviceClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: Engine? = null
    private var tagger: KeywordTagger? = null
    private var releaseJob: Job? = null

    suspend fun generate(models: OnDeviceFiles, imageJpeg: ByteArray, hint: String?): GenerationResult = lock.withLock {
        releaseJob?.cancel()
        try {
            withContext(Dispatchers.IO) { run(models, imageJpeg, hint) }
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

    /** Frees the models' memory now, e.g. before their files are deleted. */
    suspend fun release() = lock.withLock {
        releaseJob?.cancel()
        closeAll()
    }

    private fun run(models: OnDeviceFiles, imageJpeg: ByteArray, hint: String?): GenerationResult {
        val (description, title) = caption(models.captioner, imageJpeg, hint)
        if (description.isEmpty()) return GenerationResult.Terminal("The on-device model returned no description.")

        val tags = taggerFor(models.tagger).tag(imageJpeg)
        return GenerationResult.Success(
            metadata = StockMetadata(
                title = title,
                description = description,
                keywords = OnDeviceText.keywords(hint, description, title, tags).take(MAX_KEYWORDS),
                category = tags.category,
                secondaryCategory = null,
            ).normalized(),
            place = null,
            promptTokens = null,
            completionTokens = null,
        )
    }

    // One conversation, so the photo is encoded once and the title is a cheap follow-up.
    private fun caption(model: File, imageJpeg: ByteArray, hint: String?): Pair<String, String> {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = TEMPERATURE),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        )
        return engineFor(model).createConversation(config).use { conversation ->
            val described = conversation.sendMessage(
                Contents.of(Content.ImageBytes(imageJpeg), Content.Text(OnDeviceText.descriptionPrompt(hint)))
            ).toString()
            val description = OnDeviceText.sentence(described)
            val titled = conversation.sendMessage(OnDeviceText.TITLE_PROMPT).toString()
            description to OnDeviceText.title(titled, description, IPTC_OBJECT_NAME_MAX)
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

    private fun taggerFor(model: File): KeywordTagger = tagger ?: KeywordTagger(context, model).also { tagger = it }

    private fun closeAll() {
        engine?.let { runCatching { it.close() } }
        engine = null
        tagger?.let { runCatching { it.close() } }
        tagger = null
    }

    private companion object {
        // Long enough to keep the models loaded between images of one batch.
        const val IDLE_RELEASE_MS = 60_000L

        // 256 image tokens plus two short prompts and answers.
        const val MAX_TOKENS = 2048
        const val MAX_OUTPUT_TOKENS = 120

        // Captioning wants the likeliest description, not variety.
        const val TEMPERATURE = 0.2
    }
}
