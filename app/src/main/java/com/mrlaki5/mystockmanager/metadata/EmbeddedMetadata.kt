package com.mrlaki5.mystockmanager.metadata

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPMetaFactory
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import java.io.File

/** IPTC/XMP a file carries from an older version of the app or another keywording tool. Read, never written. */
data class EmbeddedMetadata(
    val title: String?,
    val caption: String?,
    val keywords: List<String>,
    val category: String?,
)

/**
 * Uses [JpegImageParser] directly rather than the `Imaging` facade or `JpegImageMetadata`,
 * both of which expose `java.awt` types in their signatures.
 */
object EmbeddedMetadataReader {

    /** Null when the file carries no title, caption or keywords, or cannot be parsed. */
    fun read(file: File): EmbeddedMetadata? = runCatching { parse(file) }.getOrNull()
        ?.takeIf { !it.title.isNullOrBlank() || !it.caption.isNullOrBlank() || it.keywords.isNotEmpty() }

    private fun parse(file: File): EmbeddedMetadata {
        val parser = JpegImageParser()
        val source = ByteSource.file(file)
        val params = JpegImagingParameters()

        val records = parser.getPhotoshopMetadata(source, params)?.photoshopApp13Data?.records.orEmpty()
        fun iptc(type: IptcTypes) = records.firstOrNull { it.iptcType == type }?.value?.trim()?.ifEmpty { null }
        val iptcKeywords = records.filter { it.iptcType == IptcTypes.KEYWORDS }.map { it.value }

        val xmp = runCatching { parser.getXmpXml(source, params) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { XMPMetaFactory.parseFromString(it) }.getOrNull() }
        val xmpSubjects = xmp?.let { meta ->
            (1..meta.countArrayItems(XMPConst.NS_DC, "subject")).mapNotNull { meta.getArrayItem(XMPConst.NS_DC, "subject", it)?.value }
        }.orEmpty()
        fun xmpText(name: String) =
            xmp?.getLocalizedText(XMPConst.NS_DC, name, null, XMPConst.X_DEFAULT)?.value?.trim()?.ifEmpty { null }

        return EmbeddedMetadata(
            title = iptc(IptcTypes.OBJECT_NAME) ?: iptc(IptcTypes.HEADLINE) ?: xmpText("title"),
            caption = iptc(IptcTypes.CAPTION_ABSTRACT) ?: xmpText("description"),
            keywords = iptcKeywords.ifEmpty { xmpSubjects }.map { it.trim() }.filter { it.isNotEmpty() },
            category = xmp?.getPropertyString(XMPConst.NS_PHOTOSHOP, "Category")?.trim()?.ifEmpty { null },
        )
    }
}
