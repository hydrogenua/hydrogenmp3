package com.vibemusic.android.source.ytm

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Генератор PoToken (proof-of-origin) для YouTube: запускает BotGuard в невидимом
 * WebView — настоящем браузерном движке, чьи токены YouTube принимает даже с
 * «замученных» IP. Библиотека bgutils-js (MIT) лежит в assets/pot, её JS
 * дергает сеть через мост [Bridge] (OkHttp), чтобы не зависеть от CORS.
 *
 * Майнер живёт в странице: первый вызов дорогой (challenge + integrity token),
 * последующие — мгновенные.
 */
class PoTokenProvider(context: Context) {

    data class Pots(val visitorPot: String, val contentPot: String)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    @Volatile
    private var cached: Pots? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var pageReady = false

    private var webView: WebView? = null

    @Volatile
    private var pending: CompletableDeferred<Pots>? = null

    /** Талоны с кэшем на TTL; contentPot обновляется под каждый трек. */
    suspend fun pots(visitorData: String, videoId: String): Pots = mutex.withLock {
        cached?.takeIf { SystemClock.elapsedRealtime() - cachedAt < TTL_MS }?.let { return it }
        withTimeout(TIMEOUT_MS) {
            ensureWebView()
            val deferred = CompletableDeferred<Pots>()
            pending = deferred
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript(
                    "window.mintPots(${JSONObject.quote(visitorData)}, ${JSONObject.quote(videoId)})",
                    null,
                )
            }
            deferred.await().also {
                cached = it
                cachedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun shutdown() {
        scope.launch(Dispatchers.Main) {
            webView?.destroy()
            webView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView() = withContext(Dispatchers.Main) {
        if (webView != null) return@withContext
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
        val view = WebView(appContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(Bridge(), "VibePot")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        webView = view
        view.loadUrl(PAGE_URL)
        withTimeout(30_000L) {
            while (!pageReady) delay(100)
        }
    }

    private inner class Bridge {

        /** Синхронный HTTP для JS: мостовые вызовы идут в отдельном потоке, не в UI. */
        @JavascriptInterface
        fun http(url: String, method: String, body: String?, headersJson: String?): String {
            val builder = Request.Builder().url(url)
            var contentType = "application/json"
            runCatching {
                headersJson?.let { headers ->
                    val obj = JSONObject(headers)
                    for (key in obj.keys()) {
                        val value = obj.getString(key)
                        // content-type живёт в RequestBody: иначе OkHttp перетирает его на application/json,
                        // а api/jnn требует application/json+protobuf (protobuf-массив).
                        if (key.equals("content-type", ignoreCase = true)) {
                            contentType = value
                        } else {
                            builder.header(key, value)
                        }
                    }
                }
            }
            if (body != null) builder.post(body.toRequestBody(contentType.toMediaType()))
            return try {
                http.newCall(builder.build()).execute().use { resp ->
                    JSONObject()
                        .put("code", resp.code)
                        .put("body", resp.body?.string() ?: "")
                        .toString()
                }
            } catch (e: Exception) {
                JSONObject().put("err", e.message ?: "network error").toString()
            }
        }

        @JavascriptInterface
        fun onResult(visitorPot: String, contentPot: String) {
            pending?.complete(Pots(visitorPot, contentPot))
            pending = null
        }

        @JavascriptInterface
        fun onError(message: String) {
            pending?.completeExceptionally(IllegalStateException("PoToken: $message"))
            pending = null
        }

        @JavascriptInterface
        fun onReady() {
            pageReady = true
        }
    }

    companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/pot/index.html"
        const val TTL_MS = 20 * 60_000L
        const val TIMEOUT_MS = 60_000L

        /** Синглтон: WebView и майнер общие для всего процесса (VM и сервис). */
        @Volatile
        private var instance: PoTokenProvider? = null

        fun get(context: Context): PoTokenProvider = instance ?: synchronized(this) {
            instance ?: PoTokenProvider(context.applicationContext).also { instance = it }
        }
    }
}
