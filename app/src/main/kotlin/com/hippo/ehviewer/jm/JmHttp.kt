package com.hippo.ehviewer.jm

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 禁漫专用的小 HTTP 客户端。
 *
 * 不走应用全局的 ktor/Cronet：那套在代理下会遇到「Content-Length mismatch」
 * （代理把 gzip 解压了但保留了长度），而且禁漫接口本身也更适合用 OkHttp 直连，
 * 解压、重定向都交给它自己处理。
 */
object JmHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 一次请求的结果：状态码、body、Set-Cookie 原样带回来。 */
    class Result(
        val code: Int,
        val body: ByteArray,
        val setCookies: List<String>,
    )

    fun getText(url: String, headers: Map<String, String>): String =
        getBytes(url, headers).toString(Charsets.UTF_8)

    fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        val result = request(url, headers)
        if (result.code !in 200..299) throw IOException("HTTP ${result.code}")
        return result.body
    }

    /** GET（form 为 null）或 POST 表单；状态码不抛异常，登录、收藏这类要看 body 判断成败。 */
    fun request(
        url: String,
        headers: Map<String, String>,
        form: Map<String, String>? = null,
    ): Result {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (form == null) {
            builder.get()
        } else {
            val body = FormBody.Builder()
            form.forEach { (k, v) -> body.add(k, v) }
            builder.post(body.build())
        }
        client.newCall(builder.build()).execute().use { response ->
            return Result(
                code = response.code,
                body = response.body.bytes(),
                setCookies = response.headers("Set-Cookie"),
            )
        }
    }
}
