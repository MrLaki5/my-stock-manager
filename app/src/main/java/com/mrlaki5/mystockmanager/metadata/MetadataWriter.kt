package com.mrlaki5.mystockmanager.metadata

import com.mrlaki5.mystockmanager.metadata.model.StockMetadata
import java.io.File

/**
 * Kept as an interface so the Commons Imaging implementation can be swapped for a
 * hand-written APP13/APP1 splicer without touching callers, should the library turn
 * out to be unusable on ART.
 */
interface MetadataWriter {
    /** Reads [source], writes a metadata-embedded copy to [destination]. Never mutates [source]. */
    fun embed(source: File, destination: File, metadata: StockMetadata)
}

/** Result of writing then reading back, used to prove the round trip actually holds. */
data class VerificationResult(
    val iptcKeywords: List<String>,
    val iptcTitle: String?,
    val iptcHeadline: String?,
    val iptcDescription: String?,
    val xmpSubjects: List<String>,
    val xmpTitle: String?,
    val rawXmp: String?,
) {
    /**
     * Headline is checked as well as Object Name: both are written, and a field that is
     * written but never read back is a field that can rot unnoticed.
     */
    fun matches(expected: StockMetadata): List<String> = buildList {
        if (iptcKeywords != expected.keywords) {
            add("IPTC keywords differ: wrote ${expected.keywords.size}, read ${iptcKeywords.size}")
        }
        if (xmpSubjects != expected.keywords) {
            add("XMP dc:subject differs: wrote ${expected.keywords.size}, read ${xmpSubjects.size}")
        }
        if (iptcTitle != expected.title) add("IPTC Object Name differs: '$iptcTitle'")
        if (iptcHeadline != expected.title) add("IPTC Headline differs: '$iptcHeadline'")
        if (xmpTitle != expected.title) add("XMP dc:title differs: '$xmpTitle'")
        if (iptcDescription != expected.description) add("IPTC description differs")
    }
}
