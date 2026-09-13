package com.mrlaki5.mystockmanager.metadata

import com.adobe.internal.xmp.XMPConst
import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.PropertyOptions
import com.adobe.internal.xmp.options.SerializeOptions
import com.mrlaki5.mystockmanager.metadata.model.StockMetadata

/**
 * Builds the XMP packet with Adobe's own reference implementation rather than
 * hand-rolled XML. The structures matter: `dc:subject` is an unordered rdf:Bag, while
 * `dc:title` and `dc:description` are rdf:Alt language alternatives. Emitting those as
 * plain strings produces XML that parses but that Adobe's ingest quietly ignores.
 */
object XmpPacketBuilder {

    fun build(metadata: StockMetadata): String {
        val xmp = XMPMetaFactory.create()

        xmp.setProperty(XMPConst.NS_DC, "format", "image/jpeg")
        xmp.setLocalizedText(
            XMPConst.NS_DC, "title", null, XMPConst.X_DEFAULT, metadata.title,
        )
        xmp.setLocalizedText(
            XMPConst.NS_DC, "description", null, XMPConst.X_DEFAULT, metadata.description,
        )

        val bag = PropertyOptions().setArray(true)
        for (keyword in metadata.keywords) {
            xmp.appendArrayItem(XMPConst.NS_DC, "subject", bag, keyword, null)
        }

        // photoshop:Headline mirrors the title; some ingest pipelines read it instead.
        xmp.setProperty(XMPConst.NS_PHOTOSHOP, "Headline", metadata.title)
        metadata.category?.let { xmp.setProperty(XMPConst.NS_PHOTOSHOP, "Category", it) }

        val options = SerializeOptions()
            .setUseCompactFormat(true)
            // Keep the <?xpacket?> wrapper: Adobe expects a self-delimiting packet in APP1.
            .setOmitPacketWrapper(false)
            .setPadding(0)

        return XMPMetaFactory.serializeToString(xmp, options)
    }
}
