package com.mrlaki5.mystockmanager.ondevice

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import com.mrlaki5.mystockmanager.data.prefs.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One downloadable model file, pinned to a commit so its checksum cannot drift. */
data class ModelFile(val fileName: String, val sizeBytes: Long, val sha256: String, val url: String)

/** The models the on-device provider runs: a small vision model for text, and an image tagger for keywords. */
object OnDeviceModel {
    const val ID = "lfm2.5-vl-450m+siglip2"
    const val LABEL = "LFM2.5-VL and SigLIP 2"
    const val PAGE_URL = "https://huggingface.co/litert-community/LFM2.5-VL-450M"

    val CAPTIONER = ModelFile(
        fileName = "LFM2.5-VL-450M_int4_fixB.litertlm",
        sizeBytes = 406_817_104L,
        sha256 = "6854cd9677a34e680070b60076793e49aeb3224de7cb4101076b261cb9bf6033",
        url = "https://huggingface.co/litert-community/LFM2.5-VL-450M/resolve/" +
            "eb997eadaef80e304343cec7a6181f170590278f/LFM2.5-VL-450M_int4_fixB.litertlm",
    )
    val TAGGER = ModelFile(
        fileName = "siglip2_base_224_fp16.tflite",
        sizeBytes = 185_437_744L,
        sha256 = "a30ebb7b3ee15eaa68a18f9ab6a2ed740c15c343d25d898dc482317473320854",
        url = "https://huggingface.co/litert-community/SigLIP2-base-patch16-224/resolve/" +
            "509b5cbcf1a849f37696be08f8297c6cd3050bf4/siglip2_base_224_fp16.tflite",
    )
    val FILES = listOf(CAPTIONER, TAGGER)
    val TOTAL_BYTES = FILES.sumOf { it.sizeBytes }

    /** Below this the models still load, but Android is likely to kill them or everything else. */
    const val RECOMMENDED_RAM_BYTES = 4_000_000_000L
}

/** The installed, verified model files. */
data class OnDeviceFiles(val captioner: File, val tagger: File)

sealed interface ModelState {
    /** The runtimes ship only 64-bit native libraries. */
    data object Unsupported : ModelState
    data object Missing : ModelState
    data class Downloading(val bytes: Long, val total: Long, val waitingForWifi: Boolean) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Owns the downloaded model files. DownloadManager does the transfers, so they survive the
 * app being closed and resume after a dropped connection. A file only gets its final name once
 * its checksum matches, so its presence alone means it is usable.
 */
@Singleton
class OnDeviceModelStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
) {

    private val downloads = context.getSystemService(DownloadManager::class.java)

    // App-specific external storage, because DownloadManager cannot write to internal storage.
    private val dir: File? get() = context.getExternalFilesDir(MODELS_DIR)

    val isSupported: Boolean = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    val totalRamBytes: Long = ActivityManager.MemoryInfo()
        .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        .totalMem

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val refreshLock = Mutex()

    /** The verified models, or null while any is missing. */
    fun readyFiles(): OnDeviceFiles? {
        val captioner = installed(OnDeviceModel.CAPTIONER) ?: return null
        val tagger = installed(OnDeviceModel.TAGGER) ?: return null
        return OnDeviceFiles(captioner, tagger)
    }

    val isReady: Boolean get() = isSupported && readyFiles() != null

    fun startDownload() {
        if (!isSupported || readyFiles() != null || pending().isNotEmpty()) return
        val target = dir ?: return fail("Phone storage is not available.")
        removeStaleFiles(target)
        val missing = OnDeviceModel.FILES.filter { installed(it) == null }
        if (target.usableSpace < missing.sumOf { it.sizeBytes } + FREE_SPACE_MARGIN) {
            return fail("Not enough free storage. The models need about ${"%.1f".format(OnDeviceModel.TOTAL_BYTES / 1e9)} GB.")
        }

        val ids = missing.associate { file ->
            File(target, partName(file)).delete()
            val request = DownloadManager.Request(file.url.toUri())
                .setTitle("On-device model: ${file.fileName}")
                .setDescription("On-device metadata generation")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, MODELS_DIR, partName(file))
            file.fileName to downloads.enqueue(request)
        }
        savePending(ids)
        _state.value = ModelState.Downloading(0, OnDeviceModel.TOTAL_BYTES, waitingForWifi = false)
    }

    fun cancelDownload() {
        pending().forEach { (name, id) ->
            downloads.remove(id)
            OnDeviceModel.FILES.firstOrNull { it.fileName == name }?.let { file -> dir?.let { File(it, partName(file)).delete() } }
        }
        savePending(emptyMap())
        _state.value = initialState()
    }

    /** Callers must stop the engines first; the files are mapped into memory while they run. */
    fun delete() {
        cancelDownload()
        dir?.listFiles()?.forEach { it.delete() }
        _state.value = initialState()
    }

    /** Re-reads the downloads' progress, and verifies and installs each one once it has finished. */
    suspend fun refresh(): ModelState = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val pending = pending()
            if (pending.isEmpty()) {
                if (_state.value !is ModelState.Failed) _state.value = initialState()
                return@withContext _state.value
            }

            var bytes = OnDeviceModel.FILES.filter { installed(it) != null }.sumOf { it.sizeBytes }
            var waitingForWifi = false
            val stillPending = pending.toMutableMap()
            for ((name, id) in pending) {
                val file = OnDeviceModel.FILES.first { it.fileName == name }
                when (val progress = query(id)) {
                    null -> {
                        // Cancelled from the notification, or cleared by the system.
                        cancelDownload()
                        return@withContext _state.value
                    }
                    is DownloadProgress.Running -> {
                        bytes += progress.bytes
                        waitingForWifi = waitingForWifi || progress.waitingForWifi
                    }
                    is DownloadProgress.Failed -> {
                        cancelDownload()
                        _state.value = ModelState.Failed("Download failed (error ${progress.reason}). Try again.")
                        return@withContext _state.value
                    }
                    DownloadProgress.Done -> {
                        _state.value = ModelState.Verifying
                        if (!install(file, id)) {
                            cancelDownload()
                            _state.value = ModelState.Failed("The download was corrupted. Try again.")
                            return@withContext _state.value
                        }
                        bytes += file.sizeBytes
                        stillPending.remove(name)
                    }
                }
            }
            savePending(stillPending)
            _state.value = if (stillPending.isEmpty()) initialState()
            else ModelState.Downloading(bytes, OnDeviceModel.TOTAL_BYTES, waitingForWifi)
            _state.value
        }
    }

    private fun installed(file: ModelFile): File? =
        dir?.let { File(it, file.fileName) }?.takeIf { it.isFile && it.length() == file.sizeBytes }

    private fun install(file: ModelFile, id: Long): Boolean {
        val target = dir ?: return false
        val part = File(target, partName(file))
        val ok = part.length() == file.sizeBytes && sha256(part) == file.sha256 && part.renameTo(File(target, file.fileName))
        // Removing the record after the rename leaves the installed file alone.
        downloads.remove(id)
        part.delete()
        return ok
    }

    /** Frees space held by models from older versions of the app. */
    private fun removeStaleFiles(target: File) {
        val keep = OnDeviceModel.FILES.flatMap { listOf(it.fileName, partName(it)) }.toSet()
        target.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun pending(): Map<String, Long> = keyStore.modelDownloads.split(',')
        .mapNotNull { entry -> entry.split('=').takeIf { it.size == 2 }?.let { (name, id) -> id.toLongOrNull()?.let { name to it } } }
        .toMap()

    private fun savePending(ids: Map<String, Long>) {
        keyStore.modelDownloads = ids.entries.joinToString(",") { "${it.key}=${it.value}" }
    }

    private fun query(id: Long): DownloadProgress? =
        downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadProgress.Done
                DownloadManager.STATUS_FAILED -> DownloadProgress.Failed(reason)
                else -> DownloadProgress.Running(
                    bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    waitingForWifi = status == DownloadManager.STATUS_PAUSED &&
                        reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }

    private fun initialState(): ModelState = when {
        !isSupported -> ModelState.Unsupported
        readyFiles() != null -> ModelState.Ready
        pending().isNotEmpty() -> ModelState.Downloading(0, OnDeviceModel.TOTAL_BYTES, waitingForWifi = false)
        else -> ModelState.Missing
    }

    private fun fail(message: String) {
        _state.value = ModelState.Failed(message)
    }

    private sealed interface DownloadProgress {
        data class Running(val bytes: Long, val waitingForWifi: Boolean) : DownloadProgress
        data class Failed(val reason: Int) : DownloadProgress
        data object Done : DownloadProgress
    }

    private companion object {
        const val MODELS_DIR = "models"
        const val FREE_SPACE_MARGIN = 200_000_000L

        fun partName(file: ModelFile) = "${file.fileName}.part"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
