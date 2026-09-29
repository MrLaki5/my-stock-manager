package com.mrlaki5.mystockmanager.nextcloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.Normalizer

/** A saved, tested NextCloud account. [baseUrl] always ends in a slash and carries any subpath install. */
data class NextcloudAccount(
    val baseUrl: HttpUrl,
    val login: String,
    val appPassword: String,
    val userId: String,
    val root: List<String>,
) {
    /** Identifies where files go; the password is left out so changing it does not re-upload everything. */
    val targetKey: String get() = "$baseUrl|$userId|${root.joinToString("/")}"

    /** [segments] are below the sync root. */
    fun davUrl(segments: List<String> = emptyList()): HttpUrl = homeUrl(root + segments)

    /** [segments] are below the user's files; addPathSegment encodes each, so names never need escaping. */
    fun homeUrl(segments: List<String>): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(userId)
            .apply { segments.forEach { addPathSegment(it) } }
            .build()

    companion object {
        const val DEFAULT_ROOT = "/my-stock-manager"

        // Where a pasted WebDAV, web UI or API address stops being the install's base.
        private val ENDPOINT_SEGMENTS = setOf("remote.php", "index.php", "ocs", "apps")

        fun parseServer(input: String): Result<HttpUrl> {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return invalid("Enter your NextCloud server address")
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return invalid("That is not a valid server address")
            if (url.scheme != "https") return invalid("The server address must use https://")

            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val keep = segments.indexOfFirst { it in ENDPOINT_SEGMENTS }.let { if (it < 0) segments.size else it }
            val base = url.newBuilder()
                .username("")
                .password("")
                .query(null)
                .fragment(null)
                .encodedPath("/")
                .apply { segments.take(keep).forEach { addPathSegment(it) } }
                .addPathSegment("")
                .build()
            return Result.success(base)
        }

        fun parseRoot(input: String): Result<List<String>> {
            val segments = input.trim().ifEmpty { DEFAULT_ROOT }
                .split('/')
                .map { Normalizer.normalize(it.trim(), Normalizer.Form.NFC) }
                .filter { it.isNotEmpty() }
            return when {
                segments.isEmpty() -> invalid("Choose a folder inside your NextCloud, such as $DEFAULT_ROOT")
                segments.any { it == "." || it == ".." } -> invalid("The folder cannot contain . or ..")
                else -> Result.success(segments)
            }
        }

        fun displayRoot(root: List<String>): String = "/" + root.joinToString("/")

        private fun invalid(message: String): Result<Nothing> =
            Result.failure(IllegalArgumentException(message))
    }
}
