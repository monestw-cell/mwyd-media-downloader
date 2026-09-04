package com.deniscerri.ytdl.util.extractors.newpipe

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

class NewPipeDownloaderImpl private constructor(private val client: OkHttpClient) : Downloader() {

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        val instance: NewPipeDownloaderImpl by lazy {
            NewPipeDownloaderImpl(sharedClient)
        }

        fun create(builder: OkHttpClient.Builder? = null): NewPipeDownloaderImpl {
            return if (builder == null) instance else NewPipeDownloaderImpl(builder.build())
        }
    }

    constructor(builder: OkHttpClient.Builder) : this(
        builder
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    )

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        var requestBody: RequestBody? = null
        if (dataToSend != null) {
            requestBody = dataToSend.toRequestBody(null)
        }

        val requestBuilder: okhttp3.Request.Builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)

        var hasUserAgent = false
        for ((headerName, headerValueList) in headers) {
            if (headerName.equals("User-Agent", ignoreCase = true)) {
                hasUserAgent = true
            }
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                for (headerValue in headerValueList) {
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        val response: okhttp3.Response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = response.body
        val responseBodyToReturn = body?.string() ?: ""
        val latestUrl = response.request.url.toString()
        return Response(
            response.code, response.message, response.headers.toMultimap(),
            responseBodyToReturn, latestUrl
        )
    }
}