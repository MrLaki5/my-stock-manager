package com.mrlaki5.mystockmanager.nextcloud

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class PropfindParserTest {

    private val folderUrl =
        "https://cloud.example.com/remote.php/dav/files/milan/my-stock-manager/Beach%20Day".toHttpUrl()

    // Trimmed from a real NextCloud 207, including its 404 propstat for a folder's content length.
    private val listing = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/</d:href>
            <d:propstat>
              <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
            <d:propstat>
              <d:prop><d:getcontentlength/></d:prop>
              <d:status>HTTP/1.1 404 Not Found</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/IMG%2B1.jpg</d:href>
            <d:propstat>
              <d:prop><d:resourcetype/><d:getcontentlength>12345</d:getcontentlength></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/Cafe%CC%81.jpg</d:href>
            <d:propstat>
              <d:prop><d:resourcetype/><d:getcontentlength>7</d:getcontentlength></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/extras/</d:href>
            <d:propstat>
              <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `lists children, skipping the collection itself`() {
        val entries = PropfindParser.parseChildren(listing.byteInputStream(), folderUrl)
        assertEquals(
            listOf(
                DavEntry("IMG+1.jpg", isCollection = false, size = 12345),
                DavEntry("Café.jpg", isCollection = false, size = 7),
                DavEntry("extras", isCollection = true, size = null),
            ),
            entries,
        )
    }

    @Test
    fun `reads the collection itself from a depth 0 answer`() {
        assertEquals(
            DavEntry("Beach Day", isCollection = true, size = null),
            PropfindParser.parseSelf(listing.byteInputStream(), folderUrl),
        )
    }

    @Test
    fun `matches the DAV namespace whatever prefix the server picks`() {
        val upper = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>https://cloud.example.com/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/a.jpg</D:href>
                <D:propstat><D:prop><D:getcontentlength>3</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals(
            listOf(DavEntry("a.jpg", isCollection = false, size = 3)),
            PropfindParser.parseChildren(upper.byteInputStream(), folderUrl),
        )
    }
}
