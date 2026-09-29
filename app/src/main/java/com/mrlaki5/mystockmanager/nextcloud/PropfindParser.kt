package com.mrlaki5.mystockmanager.nextcloud

import okhttp3.HttpUrl
import org.w3c.dom.Element
import java.io.InputStream
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory

data class DavEntry(val name: String, val isCollection: Boolean, val size: Long?)

/** DOM rather than XmlPullParser, which is only a stub in JVM unit tests. */
object PropfindParser {

    private const val DAV = "DAV:"

    /** The members of the collection at [requestUrl], leaving out the collection itself. */
    fun parseChildren(body: InputStream, requestUrl: HttpUrl): List<DavEntry> {
        val self = segmentsOf(requestUrl)
        return parse(body, requestUrl).filter { it.first != self }.map { it.second }
    }

    /** The entry for [requestUrl] itself, from a Depth 0 response. */
    fun parseSelf(body: InputStream, requestUrl: HttpUrl): DavEntry? {
        val self = segmentsOf(requestUrl)
        return parse(body, requestUrl).firstOrNull { it.first == self }?.second
    }

    private fun parse(body: InputStream, requestUrl: HttpUrl): List<Pair<List<String>, DavEntry>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's factory throws on features it does not know; the JVM's honours it.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val document = factory.newDocumentBuilder().parse(body)
        val responses = document.getElementsByTagNameNS(DAV, "response")
        return (0 until responses.length).mapNotNull { index ->
            val response = responses.item(index) as Element
            val href = response.firstText("href") ?: return@mapNotNull null
            // Resolving against the request keeps percent-decoding in OkHttp; URLDecoder would turn '+' into a space.
            val url = requestUrl.resolve(href.trim()) ?: return@mapNotNull null
            val segments = segmentsOf(url)
            val name = segments.lastOrNull() ?: return@mapNotNull null
            val isCollection = response.getElementsByTagNameNS(DAV, "collection").length > 0
            val size = response.firstText("getcontentlength")?.trim()?.toLongOrNull()
            segments to DavEntry(name, isCollection, size)
        }
    }

    private fun segmentsOf(url: HttpUrl): List<String> =
        url.pathSegments.filter { it.isNotEmpty() }.map { Normalizer.normalize(it, Normalizer.Form.NFC) }

    private fun Element.firstText(localName: String): String? =
        getElementsByTagNameNS(DAV, localName).item(0)?.textContent
}
