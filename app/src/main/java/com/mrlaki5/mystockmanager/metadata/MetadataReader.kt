package com.mrlaki5.mystockmanager.metadata

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPMetaFactory
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.JpegImageParser
import org.apache.commons.imaging.formats.jpeg.JpegImagingParameters
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import java.io.File

/**
 * Reads embedded metadata back out. This exists to make the write path verifiable:
 * every generated image is re-read and compared before being marked GENERATED, so a
 * silent embedding failure can never reach an upload.
 *
 * Uses [JpegImageParser] directly rather than the `Imaging` facade or
 * `JpegImageMetadata`, both of which expose `java.awt` types in their signatures.
 */
object MetadataReader {

    fun read(file: File): VerificationResult {
        val parser = JpegImageParser()
        val source = ByteSource.file(file)
        val params = JpegImagingParameters()

        val app13 = parser.getPhotoshopMetadata(source, params)?.photoshopApp13Data
        val records = app13?.records.orEmpty()

        val iptcKeywords = records
            .filter { it.iptcType == IptcTypes.KEYWORDS }
            .map { it.value }
        val iptcTitle = records.firstOrNull { it.iptcType == IptcTypes.OBJECT_NAME }?.value
        val iptcHeadline = records.firstOrNull { it.iptcType == IptcTypes.HEADLINE }?.value
        val iptcDescription =
            records.firstOrNull { it.iptcType == IptcTypes.CAPTION_ABSTRACT }?.value

        val rawXmp = runCatching { parser.getXmpXml(source, params) }.getOrNull()

        var xmpSubjects = emptyList<String>()
        var xmpTitle: String? = null
        if (!rawXmp.isNullOrBlank()) {
            runCatching {
                val xmp = XMPMetaFactory.parseFromString(rawXmp)
                val count = xmp.countArrayItems(XMPConst.NS_DC, "subject")
                xmpSubjects = (1..count).mapNotNull {
                    xmp.getArrayItem(XMPConst.NS_DC, "subject", it)?.value
                }
                xmpTitle = xmp
                    .getLocalizedText(XMPConst.NS_DC, "title", null, XMPConst.X_DEFAULT)
                    ?.value
            }
        }

        return VerificationResult(
            iptcKeywords = iptcKeywords,
            iptcTitle = iptcTitle,
            iptcHeadline = iptcHeadline,
            iptcDescription = iptcDescription,
            xmpSubjects = xmpSubjects,
            xmpTitle = xmpTitle,
            rawXmp = rawXmp,
        )
    }
}
