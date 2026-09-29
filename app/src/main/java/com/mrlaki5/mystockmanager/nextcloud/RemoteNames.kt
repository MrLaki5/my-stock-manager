package com.mrlaki5.mystockmanager.nextcloud

import com.mrlaki5.mystockmanager.storage.MediaStoreExporter
import java.text.Normalizer

/** Folder and file names on NextCloud; collisions are case-insensitive, as desktop clients see them. */
object RemoteNames {

    private val FORBIDDEN = Regex("""[/\\:*?"<>|\x00-\x1F]""")
    private const val MAX_FILE_NAME = 200

    // NextCloud refuses these outright, so they get a harmless suffix instead.
    private val FORBIDDEN_ENDINGS = listOf(".part", ".filepart")

    fun folderBase(eventName: String): String =
        clean(MediaStoreExporter.sanitize(eventName), fallback = "Unnamed event")

    fun folderName(eventName: String, taken: Collection<String>): String =
        candidates(folderBase(eventName), taken).first()

    /** `base`, `base (2)`, `base (3)`… skipping any that [taken] already holds. */
    fun candidates(base: String, taken: Collection<String>): Sequence<String> =
        (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base ($it)" })
            .filter { candidate -> taken.none { it.equals(candidate, ignoreCase = true) } }

    /** True when [remoteName] is still a valid home for an event whose base name is [base]. */
    fun matchesBase(remoteName: String, base: String): Boolean =
        remoteName == base || Regex("${Regex.escape(base)} \\(\\d+\\)").matches(remoteName)

    fun fileName(displayName: String, imageId: Long, taken: Collection<String>): String {
        val name = fileBase(displayName)
        return if (taken.none { it.equals(name, ignoreCase = true) }) name else withIdSuffix(name, imageId)
    }

    fun withIdSuffix(fileName: String, imageId: Long): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) "${fileName.substring(0, dot)} ($imageId)${fileName.substring(dot)}"
        else "$fileName ($imageId)"
    }

    fun fileBase(displayName: String): String =
        clean(truncate(displayName.trim().replace(FORBIDDEN, "-")), fallback = "image.jpg")

    private fun truncate(name: String): String {
        if (name.length <= MAX_FILE_NAME) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        return name.take(MAX_FILE_NAME - extension.length) + extension
    }

    private fun clean(name: String, fallback: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC).trim().trimEnd('.', ' ')
        return when {
            normalized.isEmpty() -> fallback
            normalized.equals(".htaccess", ignoreCase = true) -> "_$normalized"
            FORBIDDEN_ENDINGS.any { normalized.endsWith(it, ignoreCase = true) } -> "${normalized}_"
            else -> normalized
        }
    }
}
