package com.stronghold.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class AssetOrigin { DOWNLOAD, IMPORT }

internal data class ImportInfo(val uri: Uri, val name: String, val bytes: Long, val count: Int)

/** 一个已下载文件的来源：实际使用的 URL、响应 ETag、原始字节数（atlas 为改写前）。 */
internal data class LedgerEntry(val url: String, val etag: String?, val bytes: Long)

internal data class CheckResult(
    val checkedAt: Long,
    val origin: AssetOrigin,
    /** 已安装、但源上内容已变化的文件。 */
    val changed: List<String>,
    /** index 里有、本机却拿不出副本的文件（新增资源，或文件被清理过）。 */
    val added: List<String>,
    val removed: Int,
    val failed: Int,
    val unknown: Int,
)

internal data class AssetSummary(val present: Int, val bytes: Long)

/**
 * 游戏资源的当前来源与状态。下载的文件放在 filesDir/stronghold-assets（不在运行时目录内，APK 更新不会清掉）；
 * 导入的资源包只记录 URI 并持有读权限。
 */
internal class AssetStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val baseDir = File(appContext.filesDir, "stronghold-assets")
    private val ledgerFile = File(baseDir, "ledger.json")

    val filesRoot: File = File(baseDir, "files")

    fun origin(): AssetOrigin? = when (prefs.getString(KEY_ORIGIN, null)) {
        "download" -> AssetOrigin.DOWNLOAD
        "import" -> AssetOrigin.IMPORT
        else -> null
    }

    fun importInfo(): ImportInfo? {
        val uri = prefs.getString(KEY_IMPORT_URI, null) ?: return null
        return ImportInfo(
            uri = Uri.parse(uri),
            name = prefs.getString(KEY_IMPORT_NAME, null) ?: "资源包",
            bytes = prefs.getLong(KEY_IMPORT_BYTES, -1),
            count = prefs.getInt(KEY_IMPORT_COUNT, 0),
        )
    }

    fun lastCheck(): CheckResult? {
        val raw = prefs.getString(KEY_CHECK, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val changed = o.getJSONArray("changed")
            val added = o.optJSONArray("added")
            CheckResult(
                checkedAt = o.getLong("checkedAt"),
                origin = AssetOrigin.valueOf(o.getString("origin")),
                changed = List(changed.length()) { changed.getString(it) },
                added = if (added == null) emptyList() else List(added.length()) { added.getString(it) },
                removed = o.getInt("removed"),
                failed = o.getInt("failed"),
                unknown = o.getInt("unknown"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable check result", e)
            null
        }
    }

    /** null 表示清除。 */
    fun saveCheck(result: CheckResult?) {
        if (result == null) {
            prefs.edit().remove(KEY_CHECK).apply()
            return
        }
        val json = JSONObject()
            .put("checkedAt", result.checkedAt)
            .put("origin", result.origin.name)
            .put("changed", JSONArray(result.changed))
            .put("added", JSONArray(result.added))
            .put("removed", result.removed)
            .put("failed", result.failed)
            .put("unknown", result.unknown)
        prefs.edit().putString(KEY_CHECK, json.toString()).apply()
    }

    /** 只统计 [filesRoot] 中 [index] 列出且非空的文件。会逐个 stat，必须在后台线程调用。 */
    fun summary(index: AssetIndex): AssetSummary {
        var present = 0
        var bytes = 0L
        for (file in index.files) {
            val length = File(filesRoot, file.rel).length()
            if (length > 0) {
                present++
                bytes += length
            }
        }
        return AssetSummary(present, bytes)
    }

    /** 本地模式使用的来源；没有可用资源时返回 null（请求交给 Node，返回 404，客户端显示占位）。 */
    fun openSource(): AssetSource? = when (origin()) {
        AssetOrigin.DOWNLOAD -> if (filesRoot.isDirectory) DirectoryAssetSource(filesRoot) else null
        AssetOrigin.IMPORT -> importInfo()?.let { info ->
            try {
                ZipAssetSource.open(appContext, info.uri)
            } catch (e: Exception) {
                Log.w(TAG, "Imported asset pack unavailable: ${info.uri}", e)
                null
            }
        }
        null -> null
    }

    /** 改为在线下载：释放导入包的持久授权（资源包文件本身不删除）。 */
    fun useDownload() {
        importInfo()?.let { releaseUri(it.uri) }
        prefs.edit()
            .putString(KEY_ORIGIN, "download")
            .remove(KEY_IMPORT_URI)
            .remove(KEY_IMPORT_NAME)
            .remove(KEY_IMPORT_BYTES)
            .remove(KEY_IMPORT_COUNT)
            .remove(KEY_CHECK)
            .apply()
    }

    /** 改为读取资源包：删除已下载的资源。会递归删除文件，必须在后台线程调用。 */
    fun useImport(info: ImportInfo) {
        baseDir.deleteRecursively()
        importInfo()?.takeIf { it.uri != info.uri }?.let { releaseUri(it.uri) }
        prefs.edit()
            .putString(KEY_ORIGIN, "import")
            .putString(KEY_IMPORT_URI, info.uri.toString())
            .putString(KEY_IMPORT_NAME, info.name)
            .putLong(KEY_IMPORT_BYTES, info.bytes)
            .putInt(KEY_IMPORT_COUNT, info.count)
            .remove(KEY_CHECK)
            .apply()
    }

    fun releaseUri(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    @Synchronized
    fun readLedger(): MutableMap<String, LedgerEntry> {
        val result = HashMap<String, LedgerEntry>()
        if (!ledgerFile.isFile) return result
        try {
            val files = JSONObject(ledgerFile.readText()).getJSONObject("files")
            for (rel in files.keys()) {
                val o = files.getJSONObject(rel)
                result[rel] = LedgerEntry(
                    url = o.getString("url"),
                    etag = if (o.isNull("etag")) null else o.getString("etag"),
                    bytes = o.getLong("bytes"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable ledger ${ledgerFile.absolutePath}", e)
            result.clear()
        }
        return result
    }

    @Synchronized
    fun writeLedger(entries: Map<String, LedgerEntry>) {
        val files = JSONObject()
        for ((rel, entry) in entries) {
            files.put(
                rel,
                JSONObject()
                    .put("url", entry.url)
                    .put("etag", entry.etag ?: JSONObject.NULL)
                    .put("bytes", entry.bytes),
            )
        }
        baseDir.mkdirs()
        val tmp = File(baseDir, "ledger.json.tmp")
        tmp.writeText(JSONObject().put("files", files).toString())
        if (!tmp.renameTo(ledgerFile)) {
            ledgerFile.delete()
            if (!tmp.renameTo(ledgerFile)) Log.w(TAG, "Failed to write ledger ${ledgerFile.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "StrongholdAssets"
        const val PREFS_NAME = "stronghold_assets"
        const val KEY_ORIGIN = "origin"
        const val KEY_IMPORT_URI = "import_uri"
        const val KEY_IMPORT_NAME = "import_name"
        const val KEY_IMPORT_BYTES = "import_bytes"
        const val KEY_IMPORT_COUNT = "import_count"
        const val KEY_CHECK = "check"
    }
}
