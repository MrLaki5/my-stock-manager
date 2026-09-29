package com.mrlaki5.mystockmanager.nextcloud

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudAccountTest {

    private fun server(input: String) = NextcloudAccount.parseServer(input).getOrThrow().toString()

    private fun error(result: Result<*>) = result.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `adds https when the scheme is missing`() {
        assertEquals("https://cloud.example.com/", server("cloud.example.com"))
        assertEquals("https://cloud.example.com/", server("  https://Cloud.Example.com  "))
    }

    @Test
    fun `refuses plain http`() {
        assertTrue(error(NextcloudAccount.parseServer("http://cloud.example.com")).contains("https"))
    }

    @Test
    fun `rejects blank and malformed addresses`() {
        assertTrue(NextcloudAccount.parseServer("").isFailure)
        assertTrue(NextcloudAccount.parseServer("not a server").isFailure)
    }

    @Test
    fun `trims a pasted WebDAV or web UI address back to the install`() {
        assertEquals(
            "https://cloud.example.com/nextcloud/",
            server("https://cloud.example.com/nextcloud/remote.php/dav/files/milan/"),
        )
        assertEquals("https://cloud.example.com/", server("https://cloud.example.com/index.php/apps/files/?dir=/"))
    }

    @Test
    fun `keeps a subpath install and port but drops credentials in the address`() {
        assertEquals("https://cloud.example.com:8443/nc/", server("https://user:pw@cloud.example.com:8443/nc"))
    }

    @Test
    fun `defaults the folder and collapses stray slashes`() {
        assertEquals(listOf("my-stock-manager"), NextcloudAccount.parseRoot("").getOrThrow())
        assertEquals(listOf("Photos", "Stock"), NextcloudAccount.parseRoot("//Photos//Stock/").getOrThrow())
    }

    @Test
    fun `refuses the top level and dot segments as the sync folder`() {
        assertTrue(NextcloudAccount.parseRoot("/").isFailure)
        assertTrue(NextcloudAccount.parseRoot("/a/../b").isFailure)
    }

    @Test
    fun `encodes every path segment`() {
        val account = account(userId = "milan@example.com", root = listOf("my stock"))
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/milan@example.com/my%20stock/" +
                "Beach%20%231%20100%25/Caf%C3%A9%3F.jpg",
            account.davUrl(listOf("Beach #1 100%", "Café?.jpg")).toString(),
        )
    }

    @Test
    fun `keeps a subpath install in front of the WebDAV path`() {
        val account = account(baseUrl = "https://cloud.example.com/nextcloud/")
        assertEquals(
            "https://cloud.example.com/nextcloud/remote.php/dav/files/milan/my-stock-manager",
            account.davUrl().toString(),
        )
    }

    @Test
    fun `target key ignores a trailing slash and the password but not the folder`() {
        val a = account(baseUrl = server("cloud.example.com/"), password = "one")
        val b = account(baseUrl = server("https://cloud.example.com"), password = "two")
        assertEquals(a.targetKey, b.targetKey)
        assertNotEquals(a.targetKey, account(root = listOf("elsewhere")).targetKey)
    }

    private fun account(
        baseUrl: String = "https://cloud.example.com/",
        userId: String = "milan",
        password: String = "secret",
        root: List<String> = listOf("my-stock-manager"),
    ) = NextcloudAccount(baseUrl.toHttpUrl(), "milan", password, userId, root)
}
