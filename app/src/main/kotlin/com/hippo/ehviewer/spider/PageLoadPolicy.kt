package com.hippo.ehviewer.spider

import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.client.exception.QuotaExceededException
import com.hippo.ehviewer.util.LowSpeedException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import javax.net.ssl.SSLException
import kotlinx.io.IOException

/** 图片下载的失败分类：哪些值得换个 H@H 节点再试，哪些直接报给用户。 */
internal object PageLoadPolicy {
    /** 一轮阅读最多尝试几次（含首次）。每次重试前都会换一个新的 H@H 节点。 */
    const val MAX_ATTEMPTS = 4

    fun isRetryable(error: Throwable): Boolean = when (error) {
        // 配额用完、被 ban、token 彻底失效：再试也是白等
        is FatalException, is QuotaExceededException -> false
        // 慢、超时、连接被掐：换节点大概率就好了
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException, is LowSpeedException -> true
        is HashMismatchException, is ResponseBodyBrokenException -> true
        is SSLException, is IOException, is java.io.InterruptedIOException -> true
        is ResponseException -> {
            val code = error.response.status.value
            code == 408 || code == 429 || code >= 500
        }
        else -> {
            val message = error.message.orEmpty()
            // Cronet 自己标的 Retryable 有时是 false，但 H@H 节点掐连接这种重试一次通常就好
            RETRYABLE_NETWORK_ERRORS.any { message.contains(it, ignoreCase = true) } ||
                message.contains("hash mismatch", ignoreCase = true) ||
                message.contains("no progress", ignoreCase = true) ||
                message.contains("Invalid page", ignoreCase = true) ||
                message.contains("Unexpected end of", ignoreCase = true)
        }
    }

    /** 第 n 次失败后等多久再试（毫秒）：越往后等得越久，给慢节点一点缓冲，也不至于让用户干等。 */
    fun backoffMs(attempt: Int): Long = when (attempt) {
        1 -> 1_200L
        2 -> 3_000L
        else -> 5_000L
    }
}

/** 这些网络错误换一个 H@H 节点重试基本都能救回来，别直接当成失败报给用户。 */
private val RETRYABLE_NETWORK_ERRORS = listOf(
    "ERR_CONNECTION_ABORTED",
    "ERR_CONNECTION_CLOSED",
    "ERR_CONNECTION_RESET",
    "ERR_CONNECTION_REFUSED",
    "ERR_CONNECTION_FAILED",
    "ERR_TIMED_OUT",
    "ERR_EMPTY_RESPONSE",
    "ERR_NETWORK_CHANGED",
    "ERR_SOCKET_NOT_CONNECTED",
    "ERR_CONTENT_LENGTH_MISMATCH",
    "ERR_INCOMPLETE_CHUNKED_ENCODING",
    "ERR_HTTP2_PROTOCOL_ERROR",
    "ERR_HTTP2_PING_FAILED",
    "ERR_SSL_PROTOCOL_ERROR",
    "ERR_QUIC_PROTOCOL_ERROR",
    "ERR_RESPONSE_HEADERS_TRUNCATED",
)

/** 极速模式下主请求跑这么久还没结果，就再要一个节点并行下。 */
internal const val RACE_DELAY_MS = 1_500L

/** 备援请求根本没发出去（主请求已经完成了），不是错误。 */
internal class RaceSkipped : Exception()

/** 下载完的图和 URL 里的 sha1 对不上：内容被中间层改了，换个节点重下即可。 */
internal class HashMismatchException(message: String) : IOException(message)

/** body 读到一半断了（httparse/okio 抛的 EOF 会落到这里）。 */
internal class ResponseBodyBrokenException(message: String, cause: Throwable? = null) : IOException(message, cause)
