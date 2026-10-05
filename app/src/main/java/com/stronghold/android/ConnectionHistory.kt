package com.stronghold.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ServerRecord(val url: String, val lastConnectedAt: Long)

/**
 * 成功连接过的外部服务器，按 url 去重，按 lastConnectedAt 倒序。
 * UI 线程（删除/清空）与连接线程（record）都会调用，因此读写加锁。
 */
class ConnectionHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun entries(): List<ServerRecord> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .map { ServerRecord(it.getString("url"), it.getLong("lastConnectedAt")) }
                .sortedByDescending { it.lastConnectedAt }
        } catch (e: JSONException) {
            Log.w(TAG, "Discarding unreadable connection history", e)
            emptyList()
        }
    }

    @Synchronized
    fun record(url: String, at: Long = System.currentTimeMillis()) {
        save(listOf(ServerRecord(url, at)) + entries().filterNot { it.url == url })
    }

    @Synchronized
    fun remove(url: String) {
        save(entries().filterNot { it.url == url })
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun save(records: List<ServerRecord>) {
        val array = JSONArray()
        records.forEach {
            array.put(JSONObject().put("url", it.url).put("lastConnectedAt", it.lastConnectedAt))
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val TAG = "StrongholdHistory"
        const val PREFS_NAME = "stronghold_connection"
        const val KEY_HISTORY = "history"
    }
}

/** 补全 http:// 并把协议统一为小写，保证同一地址在历史里只出现一次。 */
fun normalizeServerUrl(input: String): String {
    val value = input.trim().trimEnd('/')
    return when {
        value.startsWith("https://", ignoreCase = true) -> "https://" + value.substring(8)
        value.startsWith("http://", ignoreCase = true) -> "http://" + value.substring(7)
        else -> "http://$value"
    }
}

/** 列表与输入框中显示的地址：省略默认的 http://，保留 https://。 */
fun displayServerUrl(url: String): String = url.removePrefix("http://")
