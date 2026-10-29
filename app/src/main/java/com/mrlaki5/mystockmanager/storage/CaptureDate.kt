package com.mrlaki5.mystockmanager.storage

import android.media.ExifInterface
import java.io.FileDescriptor
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads the date a photo was taken out of its EXIF, for the editorial caption.
 *
 * Stored and returned as an ISO local date rather than an instant on purpose. EXIF
 * DateTimeOriginal is a wall clock reading from the camera with no zone attached, so
 * converting it to epoch millis would mean inventing a zone and then converting back —
 * which silently shifts the date by a day for anything shot near midnight, or for a
 * photographer who has since flown somewhere else. The caption needs a calendar date, so
 * a calendar date is what gets kept.
 *
 * Uses the platform ExifInterface rather than the AndroidX one: the only tag needed here
 * is a JPEG DateTimeOriginal, which the platform class has read correctly since long
 * before minSdk 29, and it costs no dependency.
 */
object CaptureDate {

    private val EXIF_FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    // MediaMetadataRetriever's rendering of an MP4 creation time, which is UTC.
    private val MP4_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]'Z'", Locale.US)

    fun readFrom(fd: FileDescriptor): String? =
        runCatching { parse(ExifInterface(fd)) }.getOrNull()

    /** Reads only the header, so this never pulls a whole album file through memory. */
    fun readFrom(stream: InputStream): String? =
        runCatching { parse(ExifInterface(stream)) }.getOrNull()

    /**
     * For the MediaStore fallback, which stores an instant rather than a wall clock. The
     * device zone is the best guess available: MediaStore itself had to pick one to derive
     * this, so anything else would be guessing at its guess.
     */
    fun fromEpochMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    /** MP4 stores an instant, so it is placed in [zone] like [fromEpochMillis]; an unset time reads as 1904, the MP4 epoch. */
    fun fromMp4(raw: String, zone: ZoneId = ZoneId.systemDefault()): String? = runCatching {
        val utc = LocalDateTime.parse(raw.trim(), MP4_FORMAT)
        if (utc.year < 1971) return null
        utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(zone).toLocalDate().toString()
    }.getOrNull()

    private fun parse(exif: ExifInterface): String? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        return runCatching {
            LocalDateTime.parse(raw.trim(), EXIF_FORMAT).toLocalDate().toString()
        }.getOrNull()
    }
}
