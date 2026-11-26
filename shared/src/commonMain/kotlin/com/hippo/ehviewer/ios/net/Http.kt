package com.hippo.ehviewer.ios.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/** 网络层统一异常。不用 java.io.IOException：Kotlin/Native 上没有这个类。 */
class NetException(message: String, cause: Throwable? = null) : Exception(message, cause)

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

internal expect fun createHttpClient(): HttpClient

private val clientLazy = lazy { createHttpClient() }

val httpClient: HttpClient get() = clientLazy.value

/** 常用 UA，伪装成 iPhone Safari，站点对非浏览器 UA 会拒绝。 */
const val IOS_USER_AGENT =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 15_7 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1"

/**
 * GET 一段文本。状态码非 2xx 抛 NetException，正文截断在异常消息里便于排查。
 */
suspend fun getText(
    url: String,
    page: Int? = null,
    params: Map<String, String> = emptyMap(),
): String {
    val response = httpClient.get(url) {
        page?.let { parameter("page", it) }
        params.forEach { (name, value) -> parameter(name, value) }
    }
    val text = response.bodyAsText()
    if (!response.status.isSuccess()) {
        throw NetException("HTTP ${response.status.value}: ${text.take(200)}")
    }
    return text
}

/** 带方法、头、正文的请求，返回状态码和正文。 */
suspend fun requestText(
    url: String,
    method: HttpMethod = HttpMethod.Get,
    page: Int? = null,
    params: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): Pair<Int, String> {
    val response = httpClient.request(url) {
        this.method = method
        page?.let { parameter("page", it) }
        params.forEach { (name, value) -> parameter(name, value) }
        headers.forEach { (name, value) -> header(name, value) }
        body?.let { setBody(it) }
    }
    return response.status.value to response.bodyAsText()
}
