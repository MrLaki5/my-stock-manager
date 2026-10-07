package com.mrlaki5.mystockmanager.nextcloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mirrors [com.mrlaki5.mystockmanager.generation.GenerationResult]: the class decides retry versus stop, not the caller. */
sealed interface DavResult<out T> {
    data class Ok<T>(val value: T) : DavResult<T>

    sealed interface Failure : DavResult<Nothing> {
        val message: String
    }

    data class Transient(override val message: String) : Failure
    data class AuthRejected(override val message: String) : Failure
    data class Fatal(override val message: String) : Failure

    /** NextCloud refused this one file; the rest of the sync can carry on. */
    data class ItemRejected(override val message: String) : Failure
    data class ParentMissing(override val message: String) : Failure
}

enum class MkcolResult { CREATED, EXISTS }
enum class MoveResult { MOVED, DESTINATION_EXISTS, SOURCE_MISSING }
enum class PutResult { WRITTEN, ALREADY_EXISTS }

/** Paths are segments below the sync root. */
interface RemoteDrive {
    suspend fun ensureRoot(): DavResult<Unit>
    suspend fun mkcol(path: List<String>): DavResult<MkcolResult>
    suspend fun move(from: List<String>, to: List<String>): DavResult<MoveResult>
    suspend fun put(path: List<String>, length: Long, createOnly: Boolean, open: () -> InputStream): DavResult<PutResult>

    /** A path that is already gone counts as deleted. */
    suspend fun delete(path: List<String>): DavResult<Unit>

    /** Null when the collection does not exist; an empty [path] lists the sync root itself. */
    suspend fun list(path: List<String>): DavResult<List<DavEntry>?>
    suspend fun stat(path: List<String>): DavResult<DavEntry?>

    /** Streams the file into [to]; false when it is gone from the cloud. */
    suspend fun download(path: List<String>, to: File): DavResult<Boolean>
}

class WebDavClient(
    private val account: NextcloudAccount,
    private val http: OkHttpClient,
) : RemoteDrive {

    private val auth = Credentials.basic(account.login, account.appPassword, Charsets.UTF_8)

    override suspend fun ensureRoot(): DavResult<Unit> {
        for (depth in 1..account.root.size) {
            val url = account.homeUrl(account.root.take(depth))
            when (val result = mkcolAt(url)) {
                is DavResult.Ok -> Unit
                is DavResult.Failure -> return result
            }
        }
        return DavResult.Ok(Unit)
    }

    override suspend fun mkcol(path: List<String>): DavResult<MkcolResult> = mkcolAt(account.davUrl(path))

    private suspend fun mkcolAt(url: HttpUrl): DavResult<MkcolResult> =
        send(request(url).method("MKCOL", null).build()) { response ->
            when (response.code) {
                201 -> DavResult.Ok(MkcolResult.CREATED)
                405 -> DavResult.Ok(MkcolResult.EXISTS)
                else -> classify(response.code)
            }
        }

    override suspend fun move(from: List<String>, to: List<String>): DavResult<MoveResult> {
        val request = request(account.davUrl(from))
            .method("MOVE", null)
            .header("Destination", account.davUrl(to).toString())
            // The default, T, would delete whatever already sits at the destination.
            .header("Overwrite", "F")
            .build()
        return send(request) { response ->
            when (response.code) {
                201, 204 -> DavResult.Ok(MoveResult.MOVED)
                412 -> DavResult.Ok(MoveResult.DESTINATION_EXISTS)
                404 -> DavResult.Ok(MoveResult.SOURCE_MISSING)
                else -> classify(response.code)
            }
        }
    }

    override suspend fun put(
        path: List<String>,
        length: Long,
        createOnly: Boolean,
        open: () -> InputStream,
    ): DavResult<PutResult> {
        val request = request(account.davUrl(path))
            .put(StreamBody(length, open))
            .apply { if (createOnly) header("If-None-Match", "*") }
            .build()
        return send(request) { response ->
            when (response.code) {
                200, 201, 204 -> DavResult.Ok(PutResult.WRITTEN)
                412 -> DavResult.Ok(PutResult.ALREADY_EXISTS)
                else -> classify(response.code, item = true)
            }
        }
    }

    override suspend fun delete(path: List<String>): DavResult<Unit> =
        send(request(account.davUrl(path)).delete().build()) { response ->
            when (response.code) {
                200, 204, 404 -> DavResult.Ok(Unit)
                else -> classify(response.code, item = true)
            }
        }

    override suspend fun list(path: List<String>): DavResult<List<DavEntry>?> {
        val url = account.davUrl(path)
        return send(propfind(url, depth = 1)) { response ->
            when (response.code) {
                207 -> DavResult.Ok(PropfindParser.parseChildren(response.body.byteStream(), url))
                404 -> DavResult.Ok(null)
                else -> classify(response.code)
            }
        }
    }

    override suspend fun stat(path: List<String>): DavResult<DavEntry?> {
        val url = account.davUrl(path)
        return send(propfind(url, depth = 0)) { response ->
            when (response.code) {
                207 -> DavResult.Ok(PropfindParser.parseSelf(response.body.byteStream(), url))
                404 -> DavResult.Ok(null)
                else -> classify(response.code)
            }
        }
    }

    override suspend fun download(path: List<String>, to: File): DavResult<Boolean> =
        send(request(account.davUrl(path)).get().build()) { response ->
            when (response.code) {
                200 -> {
                    to.outputStream().use { response.body.byteStream().copyTo(it) }
                    DavResult.Ok(true)
                }
                404 -> DavResult.Ok(false)
                else -> classify(response.code, item = true)
            }
        }

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth)

    private fun propfind(url: HttpUrl, depth: Int) = request(url)
        .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
        .header("Depth", depth.toString())
        .build()

    /** Reopens the source on every write, because OkHttp resends the body when it retries a connection. */
    private class StreamBody(private val length: Long, private val open: () -> InputStream) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = length
        override fun isOneShot() = false
        override fun writeTo(sink: BufferedSink) {
            open().source().use { sink.writeAll(it) }
        }
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            // Following a redirect turns a PUT into a GET that can "succeed" on an HTML page.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** The WebDAV user id, which differs from the login name for e-mail and LDAP logins. */
        suspend fun resolveUserId(
            http: OkHttpClient,
            baseUrl: HttpUrl,
            login: String,
            appPassword: String,
        ): DavResult<String> {
            val url = baseUrl.newBuilder()
                .addPathSegments("ocs/v2.php/cloud/user")
                .addQueryParameter("format", "json")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(login, appPassword, Charsets.UTF_8))
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .build()
            return http.send(request) { response ->
                when {
                    response.isSuccessful -> DavResult.Ok(userIdFrom(response.body.string()) ?: login)
                    response.code == 404 -> DavResult.Ok(login)
                    else -> classify(response.code)
                }
            }
        }

        private fun userIdFrom(body: String): String? = runCatching {
            json.parseToJsonElement(body).jsonObject["ocs"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("id")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        internal fun classify(code: Int, item: Boolean = false): DavResult.Failure = when {
            code == 401 -> DavResult.AuthRejected("NextCloud rejected the username or app password.")
            code in 300..399 -> DavResult.Fatal("NextCloud answered with a redirect ($code). Check the server address.")
            code == 507 -> DavResult.Fatal("Your NextCloud storage is full.")
            code == 408 || code == 423 || code == 429 || code >= 500 ->
                DavResult.Transient("NextCloud is not responding right now ($code).")
            code == 404 || code == 409 -> DavResult.ParentMissing("A NextCloud folder is missing ($code).")
            item && code in setOf(400, 403, 413, 415) -> DavResult.ItemRejected("NextCloud refused this file ($code).")
            code == 403 -> DavResult.Fatal("NextCloud denied access to the sync folder ($code).")
            else -> DavResult.Fatal("Unexpected response from NextCloud ($code).")
        }

        private fun failureOf(e: Exception): DavResult.Failure = when (e) {
            is SSLPeerUnverifiedException, is SSLHandshakeException ->
                DavResult.Fatal("Could not verify NextCloud's certificate: ${e.message}")
            is IOException -> DavResult.Transient("Could not reach NextCloud: ${e.message ?: e.javaClass.simpleName}")
            else -> DavResult.Fatal("Unexpected response from NextCloud: ${e.message ?: e.javaClass.simpleName}")
        }

        private suspend fun <T> OkHttpClient.send(
            request: Request,
            handle: (Response) -> DavResult<T>,
        ): DavResult<T> {
            val response = try {
                await(request)
            } catch (e: IOException) {
                return failureOf(e)
            }
            return withContext(Dispatchers.IO) {
                try {
                    response.use(handle)
                } catch (e: IOException) {
                    failureOf(e)
                } catch (e: org.xml.sax.SAXException) {
                    failureOf(e)
                }
            }
        }

        /** Cancelling the coroutine cancels the call, so a stopped worker does not keep uploading. */
        private suspend fun OkHttpClient.await(request: Request): Response =
            suspendCancellableCoroutine { continuation ->
                val call = newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
    }

    private suspend fun <T> send(request: Request, handle: (Response) -> DavResult<T>): DavResult<T> =
        http.send(request, handle)
}
