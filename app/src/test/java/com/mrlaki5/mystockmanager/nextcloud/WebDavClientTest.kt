package com.mrlaki5.mystockmanager.nextcloud

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var account: NextcloudAccount
    private lateinit var client: WebDavClient
    private val http = WebDavClient.defaultHttp()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        account = NextcloudAccount(server.url("/"), "milan@example.com", "app-pass", "milan", listOf("my-stock-manager"))
        client = WebDavClient(account, http)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun respond(code: Int, body: String = "") = server.enqueue(MockResponse(code = code, body = body))

    @Test
    fun `sends basic auth up front on every request`() = runBlocking<Unit> {
        respond(201)
        client.mkcol(listOf("Beach"))
        val request = server.takeRequest()
        assertEquals(Credentials.basic("milan@example.com", "app-pass"), request.headers["Authorization"])
        assertEquals("MKCOL", request.method)
        assertEquals("/remote.php/dav/files/milan/my-stock-manager/Beach", request.url.encodedPath)
    }

    @Test
    fun `treats an existing collection as success`() = runBlocking<Unit> {
        respond(201)
        respond(405)
        respond(409)
        assertEquals(DavResult.Ok(MkcolResult.CREATED), client.mkcol(listOf("Beach")))
        assertEquals(DavResult.Ok(MkcolResult.EXISTS), client.mkcol(listOf("Beach")))
        assertTrue(client.mkcol(listOf("Beach")) is DavResult.ParentMissing)
    }

    @Test
    fun `creates each segment of the sync root`() = runBlocking<Unit> {
        val nested = WebDavClient(account.copy(root = listOf("Photos", "Stock")), http)
        respond(405)
        respond(201)
        assertEquals(DavResult.Ok(Unit), nested.ensureRoot())
        assertEquals("/remote.php/dav/files/milan/Photos", server.takeRequest().url.encodedPath)
        assertEquals("/remote.php/dav/files/milan/Photos/Stock", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `uploads with a fixed length, never chunked, and only creates when asked`() = runBlocking<Unit> {
        respond(201)
        val result = client.put(listOf("Beach Day", "a.jpg"), 5, createOnly = true) { "hello".byteInputStream() }
        assertEquals(DavResult.Ok(PutResult.WRITTEN), result)

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/remote.php/dav/files/milan/my-stock-manager/Beach%20Day/a.jpg", request.url.encodedPath)
        assertEquals("5", request.headers["Content-Length"])
        assertNull(request.headers["Transfer-Encoding"])
        assertEquals("*", request.headers["If-None-Match"])
        assertEquals("hello", request.body?.utf8())
    }

    @Test
    fun `overwrites without a precondition on a re-upload`() = runBlocking<Unit> {
        respond(204)
        client.put(listOf("Beach", "a.jpg"), 2, createOnly = false) { "hi".byteInputStream() }
        assertNull(server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun `maps upload refusals onto what the engine should do next`() = runBlocking<Unit> {
        respond(412)
        respond(413)
        respond(409)
        respond(507)
        val put = suspend { client.put(listOf("Beach", "a.jpg"), 1, createOnly = true) { "x".byteInputStream() } }
        assertEquals(DavResult.Ok(PutResult.ALREADY_EXISTS), put())
        assertTrue(put() is DavResult.ItemRejected)
        assertTrue(put() is DavResult.ParentMissing)
        assertTrue(put() is DavResult.Fatal)
    }

    @Test
    fun `moves without overwriting and names the destination in full`() = runBlocking<Unit> {
        respond(201)
        respond(412)
        respond(404)
        assertEquals(DavResult.Ok(MoveResult.MOVED), client.move(listOf("Beach"), listOf("Sunset #2")))
        assertEquals(DavResult.Ok(MoveResult.DESTINATION_EXISTS), client.move(listOf("Beach"), listOf("Sunset")))
        assertEquals(DavResult.Ok(MoveResult.SOURCE_MISSING), client.move(listOf("Beach"), listOf("Sunset")))

        val request = server.takeRequest()
        assertEquals("MOVE", request.method)
        assertEquals("F", request.headers["Overwrite"])
        assertEquals(account.davUrl(listOf("Sunset #2")).toString(), request.headers["Destination"])
        assertTrue(request.headers["Destination"]!!.endsWith("/Sunset%20%232"))
    }

    @Test
    fun `counts a path that is already gone as deleted`() = runBlocking<Unit> {
        respond(204)
        respond(404)
        respond(403)
        assertEquals(DavResult.Ok(Unit), client.delete(listOf("Beach", "a.jpg")))
        assertEquals(DavResult.Ok(Unit), client.delete(listOf("Beach", "a.jpg")))
        assertTrue(client.delete(listOf("Beach", "a.jpg")) is DavResult.ItemRejected)
    }

    @Test
    fun `lists a folder with depth 1 and reports a missing one as null`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse(
                code = 207,
                body = """
                    <?xml version="1.0"?>
                    <d:multistatus xmlns:d="DAV:">
                      <d:response><d:href>/remote.php/dav/files/milan/my-stock-manager/Beach/</d:href>
                        <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                      <d:response><d:href>/remote.php/dav/files/milan/my-stock-manager/Beach/a.jpg</d:href>
                        <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat></d:response>
                    </d:multistatus>
                """.trimIndent(),
            ),
        )
        respond(404)

        assertEquals(DavResult.Ok(listOf(DavEntry("a.jpg", false, 42))), client.list(listOf("Beach")))
        assertEquals(DavResult.Ok(null), client.list(listOf("Beach")))
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.headers["Depth"])
    }

    @Test
    fun `classifies failures by whether retrying can help`() = runBlocking<Unit> {
        respond(401)
        respond(503)
        respond(429)
        assertTrue(client.mkcol(listOf("Beach")) is DavResult.AuthRejected)
        assertTrue(client.mkcol(listOf("Beach")) is DavResult.Transient)
        assertTrue(client.mkcol(listOf("Beach")) is DavResult.Transient)
    }

    @Test
    fun `does not follow a redirect`() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/login").build())
        assertTrue(client.put(listOf("Beach", "a.jpg"), 1, createOnly = false) { "x".byteInputStream() } is DavResult.Fatal)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `reports an unreachable server as transient`() = runBlocking<Unit> {
        server.close()
        assertTrue(client.mkcol(listOf("Beach")) is DavResult.Transient)
    }

    @Test
    fun `resolves the user id through OCS`() = runBlocking<Unit> {
        respond(200, """{"ocs":{"meta":{"status":"ok"},"data":{"id":"milan","display-name":"Milan"}}}""")
        val result = WebDavClient.resolveUserId(http, server.url("/"), "milan@example.com", "app-pass")
        assertEquals(DavResult.Ok("milan"), result)

        val request = server.takeRequest()
        assertEquals("/ocs/v2.php/cloud/user", request.url.encodedPath)
        assertEquals("json", request.url.queryParameter("format"))
        assertEquals("true", request.headers["OCS-APIRequest"])
    }

    @Test
    fun `falls back to the login when OCS is unavailable, but not when it refuses`() = runBlocking<Unit> {
        respond(404)
        respond(401)
        assertEquals(DavResult.Ok("milan"), WebDavClient.resolveUserId(http, server.url("/"), "milan", "x"))
        assertTrue(WebDavClient.resolveUserId(http, server.url("/"), "milan", "x") is DavResult.AuthRejected)
    }

    @Test
    fun `downloads a file and reports one that is gone`() = runBlocking<Unit> {
        respond(200, "jpeg bytes")
        respond(404)
        val file = java.io.File.createTempFile("download", ".jpg").apply { deleteOnExit() }

        assertEquals(DavResult.Ok(true), client.download(listOf("Beach", "a.jpg"), file))
        assertEquals("jpeg bytes", file.readText())
        assertEquals(DavResult.Ok(false), client.download(listOf("Beach", "a.jpg"), file))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/remote.php/dav/files/milan/my-stock-manager/Beach/a.jpg", request.url.encodedPath)
    }

    @Test
    fun `lists the sync root itself for an empty path`() = runBlocking<Unit> {
        respond(207, """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"></d:multistatus>""")
        assertEquals(DavResult.Ok(emptyList<DavEntry>()), client.list(emptyList()))
        assertEquals("/remote.php/dav/files/milan/my-stock-manager", server.takeRequest().url.encodedPath)
    }
}
