package com.vibemusic.android.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.vibemusic.android.data.DISMISSED_UPDATE_KEY
import com.vibemusic.android.data.appDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Проверка обновлений через GitHub Releases и установка через системный
 * DownloadManager (прогресс в шторке, после — системный установщик APK).
 */
class UpdateManager(private val context: Context) {

    data class UpdateInfo(val tag: String, val version: String, val apkUrl: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _dismissedTag = MutableStateFlow("")
    val dismissedTag: StateFlow<String> = _dismissedTag.asStateFlow()

    private var downloadId = -1L
    private var checked = false

    init {
        scope.launch { _dismissedTag.value = context.appDataStore.data.first()[DISMISSED_UPDATE_KEY] ?: "" }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: return
                    if (id == downloadId) installApk()
                }
            },
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Один вызов на сессию: тихо проверяет последний релиз GitHub. */
    suspend fun check() = withContext(Dispatchers.IO) {
        if (checked) return@withContext
        checked = true
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/hydrogenua/hydrogenmp3/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "hydrogen-app")
                .build()
            http.newCall(request).execute().use { resp ->
                check(resp.isSuccessful) { "update: HTTP ${resp.code}" }
                val json = JSONObject(resp.body?.string() ?: error("пустой ответ"))
                val remoteTag = json.optString("tag_name").removePrefix("v")
                val local = localVersion()
                Log.d(TAG, "check: local=$local remote=$remoteTag")
                if (!isNewer(local, remoteTag)) return@use

                val apkAsset = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk") }
                }
                val apkUrl = apkAsset?.optString("browser_download_url")
                    ?: "https://github.com/hydrogenua/hydrogenmp3/releases/latest"
                if (_dismissedTag.value != remoteTag) {
                    _available.value = UpdateInfo(tag = "v$remoteTag", version = remoteTag, apkUrl = apkUrl)
                }
            }
        }.onFailure { Log.d(TAG, "check failed: ${it.message}") }
    }

    fun downloadAndInstall(info: UpdateInfo) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("hydrogen ${info.version}")
            .setDescription("Скачивание обновления")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update-${info.version}.apk")
            .setMimeType("application/vnd.android.package-archive")
        downloadId = dm.enqueue(request)
    }

    suspend fun dismiss(tag: String) {
        _dismissedTag.value = tag
        context.appDataStore.edit { it[DISMISSED_UPDATE_KEY] = tag }
    }

    private fun installApk() {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.e(TAG, "не удалось открыть установщик", it) }
    }

    private fun localVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    companion object {
        private const val TAG = "UpdateManager"

        /** 0.1.0 < 0.2.0; недостающие сегменты считаются нулями. */
        fun isNewer(local: String, remote: String): Boolean {
            val l = local.split(".").map { it.toIntOrNull() ?: 0 }
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(l.size, r.size)) {
                val a = l.getOrElse(i) { 0 }
                val b = r.getOrElse(i) { 0 }
                if (a != b) return a < b
            }
            return false
        }
    }
}
