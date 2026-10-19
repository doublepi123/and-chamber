package io.andchamber.app.data

import io.andchamber.app.runtime.WebSessionCookies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Where a download's bytes go. Implementations: [MediaStoreDownloadDestination], [FileDownloadDestination]. */
internal interface HttpDownloadDestination {
    fun create(
        fileName: String,
        mimeType: String,
    ): PendingDownload
}

/**
 * One file being written. Exactly one of [commit] (publish under the final name) or [abort] (remove
 * everything written) must end it; the transfer calls [abort] whenever commit has not succeeded.
 */
internal interface PendingDownload {
    fun openStream(): OutputStream

    /** Publishes the file and returns the name it was saved under. */
    fun commit(): String

    /** Removes partial output. Must be safe to call when nothing was written. */
    fun abort()
}

/**
 * Client for the download lane. Holds no cookies, follows no redirects automatically, and has no
 * interceptors that could add credentials. Redirects are followed by [HttpDownloadTransfer].
 * There is no overall call timeout, because large files can take a long time; a stalled connection
 * is caught by the read timeout.
 */
internal fun newHttpDownloadClient(): OkHttpClient =
    OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

/**
 * Downloads one URL with the session cookie only when it is safe (see [DownloadRedirectPolicy]),
 * then streams the body into a [PendingDownload]. Cancellation is rethrown; partial output is removed.
 */
internal class HttpDownloadTransfer(
    private val client: OkHttpClient,
    private val maxRedirects: Int = MAX_DOWNLOAD_REDIRECTS,
) {
    suspend fun download(
        cookies: WebSessionCookies,
        request: HttpDownloadRequest,
        destination: HttpDownloadDestination,
    ): HttpDownloadResult {
        val name = sanitizeDownloadFileName(request.fileName)
        return try {
            val initial = request.url.toHttpUrlOrNull() ?: throw HttpDownloadRejected("Invalid download URL")
            val policy = DownloadRedirectPolicy(cookies.identity.origin, initial, maxRedirects)
            val (call, response) = fetchFinal(cookies, request.userAgent, policy)
            response.use { writeBody(call, it, request.mimeType, name, destination) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A cancelled job also surfaces as an IOException from the cancelled call; report that as cancellation.
            currentCoroutineContext().ensureActive()
            HttpDownloadResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /** Follows redirects one hop at a time. Each redirect response is closed before the next call. */
    private suspend fun fetchFinal(
        cookies: WebSessionCookies,
        userAgent: String?,
        policy: DownloadRedirectPolicy,
    ): Pair<Call, Response> {
        while (true) {
            val hop = policy.url.toString()
            val builder = Request.Builder().url(hop).get()
            userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
            if (policy.cookiesAllowed) {
                cookies.cookieHeader(hop)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            }
            val call = client.newCall(builder.build())
            val response = call.await()
            if (response.code in REDIRECT_STATUS_CODES) {
                val location = response.header("Location")
                response.close()
                policy.nextLocation(location)
                continue
            }
            if (!response.isSuccessful) {
                response.close()
                throw HttpDownloadRejected("HTTP ${response.code}")
            }
            return call to response
        }
    }

    private suspend fun writeBody(
        call: Call,
        response: Response,
        mimeHint: String?,
        name: String,
        destination: HttpDownloadDestination,
    ): HttpDownloadResult {
        val body = response.body ?: throw HttpDownloadRejected("Empty response body")
        val mime =
            mimeHint?.takeIf { it.isNotBlank() }
                ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
                ?: "application/octet-stream"
        val pending = destination.create(name, mime)
        var committed = false
        try {
            copyCancellable(call, body.byteStream(), pending)
            val saved = pending.commit()
            committed = true
            return HttpDownloadResult.Saved(saved)
        } finally {
            if (!committed) {
                withContext(NonCancellable) { runCatching { pending.abort() } }
            }
        }
    }

    /**
     * Streams the body on the IO dispatcher. Cancelling the caller cancels the OkHttp call, which
     * unblocks a read that would otherwise wait for the network.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun copyCancellable(
        call: Call,
        input: InputStream,
        pending: PendingDownload,
    ) {
        // onCancelling fires as soon as the caller is cancelled, while the blocking read is still in progress.
        val handle =
            currentCoroutineContext()[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                call.cancel()
            }
        try {
            withContext(Dispatchers.IO) {
                input.use { source ->
                    pending.openStream().use { sink ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                        }
                    }
                }
            }
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        cont.resume(response) { response.close() }
                    }
                },
            )
        }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
