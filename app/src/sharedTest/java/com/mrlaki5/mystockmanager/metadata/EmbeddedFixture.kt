package com.mrlaki5.mystockmanager.metadata

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.ByteArrayOutputStream
import java.io.File

/** Writes IPTC and XMP the way older versions of the app did, so tests have files to read back. */
object EmbeddedFixture {

    fun embed(source: File, destination: File, metadata: EmbeddedMetadata, iptc: Boolean = true, xmp: Boolean = true) {
        var bytes = source.readBytes()
        if (iptc) {
            val records = buildList {
                metadata.title?.let { add(IptcRecord(IptcTypes.OBJECT_NAME, it)) }
                metadata.caption?.let { add(IptcRecord(IptcTypes.CAPTION_ABSTRACT, it)) }
                metadata.keywords.forEach { add(IptcRecord(IptcTypes.KEYWORDS, it)) }
            }
            val out = ByteArrayOutputStream()
            // forceUtf8Encoding marks the records as UTF-8, or non-ASCII reads back as mojibake.
            JpegIptcRewriter().writeIptc(ByteSource.array(bytes), out, PhotoshopApp13Data(records, emptyList(), true))
            bytes = out.toByteArray()
        }
        if (xmp) {
            val out = ByteArrayOutputStream()
            JpegXmpRewriter().updateXmpXml(ByteSource.array(bytes), out, xmpPacket(metadata))
            bytes = out.toByteArray()
        }
        destination.writeBytes(bytes)
    }

    private fun xmpPacket(metadata: EmbeddedMetadata): String {
        val xmp = XMPMetaFactory.create()
        metadata.title?.let { xmp.setLocalizedText(XMPConst.NS_DC, "title", null, XMPConst.X_DEFAULT, it) }
        metadata.caption?.let { xmp.setLocalizedText(XMPConst.NS_DC, "description", null, XMPConst.X_DEFAULT, it) }
        metadata.keywords.forEach { xmp.appendArrayItem(XMPConst.NS_DC, "subject", PropertyOptions().setArray(true), it, null) }
        metadata.category?.let { xmp.setProperty(XMPConst.NS_PHOTOSHOP, "Category", it) }
        return XMPMetaFactory.serializeToString(xmp, SerializeOptions().setUseCompactFormat(true).setOmitPacketWrapper(false).setPadding(0))
    }
}
