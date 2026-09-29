package com.mrlaki5.mystockmanager.nextcloud

import android.graphics.BitmapFactory
import androidx.core.net.toUri
import com.mrlaki5.mystockmanager.metadata.MetadataReader
import com.mrlaki5.mystockmanager.storage.AppFileStore
import com.mrlaki5.mystockmanager.storage.CaptureDate
import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One HTTP client for every NextCloud call, so connections are pooled across a sync run. */
@Singleton
class WebDavDrives @Inject constructor() : RemoteDriveFactory {

    val http: OkHttpClient by lazy { WebDavClient.defaultHttp() }

    override fun create(account: NextcloudAccount): RemoteDrive = WebDavClient(account, http)
}

@Singleton
class AlbumFiles @Inject constructor(
    private val mediaStore: MediaStoreExporter,
) : LocalFiles {

    override fun sizeOf(uri: String): Long? = mediaStore.sizeOf(uri.toUri())

    override fun open(uri: String): InputStream =
        mediaStore.openInput(uri.toUri()) ?: throw FileNotFoundException("Could not open $uri")
}

@Singleton
class PullAlbumFiles @Inject constructor(
    private val mediaStore: MediaStoreExporter,
    private val fileStore: AppFileStore,
) : PullAlbum {

    override fun newTempFile(): File = fileStore.newTempFile("pull")

    override fun inspect(file: File): InspectedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        // A file with no readable metadata still comes in, just without a title or keywords.
        val metadata = runCatching { MetadataReader.read(file) }.getOrNull()
        return InspectedImage(
            widthPx = bounds.outWidth,
            heightPx = bounds.outHeight,
            capturedOn = CaptureDate.readFrom(file),
            title = metadata?.iptcTitle ?: metadata?.xmpTitle,
            caption = metadata?.iptcDescription,
            keywords = metadata?.iptcKeywords?.ifEmpty { metadata.xmpSubjects }.orEmpty(),
            category = metadata?.xmpCategory,
        )
    }

    override fun publish(file: File, displayName: String, eventName: String): String =
        mediaStore.publish(file, displayName, eventName).toString()

    override fun overwrite(uri: String, file: File): Boolean {
        val entry = uri.toUri()
        if (!mediaStore.exists(entry)) return false
        mediaStore.overwrite(entry, file)
        return true
    }

    override fun sizeOf(uri: String): Long? = mediaStore.sizeOf(uri.toUri())

    override fun dateTaken(uri: String): String? =
        mediaStore.dateTakenMillis(uri.toUri())?.let(CaptureDate::fromEpochMillis)
}
