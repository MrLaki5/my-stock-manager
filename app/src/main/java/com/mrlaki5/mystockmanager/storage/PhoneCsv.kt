package com.mrlaki5.mystockmanager.storage

import com.mrlaki5.mystockmanager.data.db.dao.EventCsvRow
import com.mrlaki5.mystockmanager.data.db.dao.ImageDao
import com.mrlaki5.mystockmanager.metadata.AgencyCsv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** Keeps each event's CSV in Documents, to upload from the phone; renamed and deleted events take theirs along. */
@Singleton
class PhoneCsv @Inject constructor(
    private val imageDao: ImageDao,
    private val mediaStore: MediaStoreExporter,
) {

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            imageDao.observeEventCsvRows().debounce(2.seconds).collectLatest { rows ->
                // A file that cannot be written now is retried on the next metadata change.
                runCatching { reconcile(rows) }
            }
        }
    }

    private fun reconcile(rows: List<EventCsvRow>) {
        val names = mediaStore.displayNames(rows.mapNotNull { MediaStoreExporter.idOf(it.mediaStoreUri) }) ?: return
        val wanted = filesFor(rows, names)
        val existing = mediaStore.eventCsvs() ?: return
        existing.filterKeys { it !in wanted }.values.forEach { mediaStore.deleteFile(it.uri) }
        for ((path, bytes) in wanted) {
            val stored = existing[path]
            if (stored == null || mediaStore.readBytes(stored.uri)?.contentEquals(bytes) != true) {
                mediaStore.writeEventCsv(path, bytes, stored?.uri)
            } else if (stored.mimeType != MediaStoreExporter.CSV_MIME_TYPE) {
                mediaStore.retypeCsv(stored.uri)
            }
        }
    }

    companion object {
        /** CSV bytes by relative path, for every event with a complete row; rows take the file's name in the album from [names]. */
        fun filesFor(rows: List<EventCsvRow>, names: Map<Long, String>): Map<String, ByteArray> =
            rows.groupBy { it.eventName }
                .mapValues { (_, event) ->
                    event.mapNotNull { row ->
                        val name = MediaStoreExporter.idOf(row.mediaStoreUri)?.let(names::get) ?: row.image.fileName
                        row.image.copy(fileName = name).toCsvImage().row
                    }
                }
                .filterValues { it.isNotEmpty() }
                .entries.associate { (event, csvRows) -> MediaStoreExporter.csvPathFor(event) to AgencyCsv.write(csvRows) }
    }
}
