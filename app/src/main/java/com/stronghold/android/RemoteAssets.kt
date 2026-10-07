package com.stronghold.android

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * `/media/…` 扩展名省略路由能还原的音频后缀，顺序与上游 `shared/media.js` 的 `AUDIO_EXTS` 一致。
 * 上游客户端把 `/assets/audio/….mp3` 改写成 `/media/…`（躲开下载管理器对媒体后缀 fetch 的劫持），
 * 这里负责反向还原；两边若漂移，还原失败会退化成放行走网络。
 */
private val AUDIO_EXTS = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav")

/**
 * 外部服务器模式下，判断能不能把 `/assets/…` 交给本机已有的资源。
 *
 * 上游对资源没有内容寻址（`public/assets/` 不进仓库、由每台机器自己下载，ETag 是 size+mtime，
 * `/healthz` 的 build 标签不含 assets），唯一能表示"这份资源是哪一版"的是 `data/assets.json`
 * 顶层的清单 hash —— 它只摘要 URL 集合、不含文件字节，所以一致意味着同名文件来自同一版清单，
 * 而不是字节级相同。
 */
internal object RemoteAssets {
    private const val TAG = "Stronghold"

    /** 远程服务器 `data/assets.json` 的清单 hash；不可达、或返回的不是清单（例如 SPA 的 index.html）时返回 null。 */
    fun manifestHash(serverUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${serverUrl.trimEnd('/')}/data/assets.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 5_000
                requestMethod = "GET"
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(body).optString("hash").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the remote asset manifest of $serverUrl", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 把页面请求的路径还原成索引里的 rel：`/assets/<rel>` 直接对应；音频另有一条扩展名省略的
     * `/media/<rest>`，按 [AUDIO_EXTS] 逐个试本机有没有。两者都不成立、或本机没有该文件时返回 null
     * （调用方放行，交回网络）。
     */
    fun relFor(path: String, source: AssetSource): String? {
        path.removePrefix("/assets/")
            .takeIf { it != path && isSafeAssetRel(it) }
            ?.let { return it }
        val media = path.removePrefix("/media/").takeIf { it != path && it.isNotEmpty() } ?: return null
        if (!isSafeAssetRel(media)) return null
        return AUDIO_EXTS.firstNotNullOfOrNull { ext ->
            "audio/$media$ext".takeIf { isSafeAssetRel(it) && source.size(it) > 0 }
        }
    }
}
