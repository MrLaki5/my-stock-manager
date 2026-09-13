package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import org.apache.commons.imaging.bytesource.ByteSource
import org.apache.commons.imaging.formats.jpeg.iptc.IptcRecord
import org.apache.commons.imaging.formats.jpeg.iptc.IptcTypes
import org.apache.commons.imaging.formats.jpeg.iptc.JpegIptcRewriter
import org.apache.commons.imaging.formats.jpeg.iptc.PhotoshopApp13Data
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Embeds metadata by splicing JPEG segments: IPTC IIM into APP13 (Photoshop IRB) and
 * XMP into APP1. Pixels are never decoded or re-encoded, so this is lossless and fast.
 *
 * Two passes are required because Commons Imaging exposes one rewriter per segment
 * type. The intermediate lives in memory rather than on disk — JPEGs at stock
 * resolution are a few tens of MB at worst and this avoids a second temp file.
 *
 * Deliberately never calls `removeIptc`: that is the one method on this path that
 * instantiates `JpegImagingParameters`, whose superclass holds a `BufferedImageFactory`
 * field referencing `java.awt.image.BufferedImage`. `writeIptc` does not touch it.
 */
class CommonsImagingMetadataWriter : MetadataWriter {

    override fun embed(source: File, destination: File, metadata: StockMetadata) {
        val normalized = metadata.normalized()

        val withIptc = ByteArrayOutputStream(source.length().toInt().coerceAtLeast(1024))
        JpegIptcRewriter().writeIptc(
            ByteSource.file(source),
            withIptc,
            buildApp13Data(normalized),
        )

        val xmpPacket = XmpPacketBuilder.build(normalized)
        destination.outputStream().buffered().use { out ->
            JpegXmpRewriter().updateXmpXml(
                ByteSource.array(withIptc.toByteArray()),
                out,
                xmpPacket,
            )
        }
    }

    private fun buildApp13Data(metadata: StockMetadata): PhotoshopApp13Data {
        val records = ArrayList<IptcRecord>(metadata.keywords.size + 3)

        records += IptcRecord(IptcTypes.OBJECT_NAME, metadata.title)
        records += IptcRecord(IptcTypes.HEADLINE, metadata.title)
        records += IptcRecord(IptcTypes.CAPTION_ABSTRACT, metadata.description)
        // KEYWORDS is a repeatable IIM field: one record per keyword, in relevance order.
        for (keyword in metadata.keywords) {
            records += IptcRecord(IptcTypes.KEYWORDS, keyword)
        }

        // forceUtf8Encoding=true writes the 1:90 coded-character-set marker, without
        // which non-ASCII keywords are read back as mojibake.
        return PhotoshopApp13Data(records, emptyList(), true)
    }
}
