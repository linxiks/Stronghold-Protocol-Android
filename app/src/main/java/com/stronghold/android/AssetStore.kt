package com.stronghold.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class ImportInfo(val uri: Uri, val name: String, val bytes: Long, val count: Int)

/** 一个已下载文件的来源：实际使用的 URL、响应 ETag、原始字节数（atlas 为改写前）。 */
internal data class LedgerEntry(val url: String, val etag: String?, val bytes: Long)

internal data class CheckResult(
    val checkedAt: Long,
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
 * 游戏资源的本地状态。下载的文件放在 filesDir/stronghold-assets（不在运行时目录内，APK 更新不会清掉），
 * 导入的资源包通过持久授权的 URI 直接读取：两者只是同一份「本地已有资源」的两个来源，下载目录优先。
 */
internal class AssetStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val baseDir = File(appContext.filesDir, "stronghold-assets")
    private val ledgerFile = File(baseDir, "ledger.json")

    val filesRoot: File = File(baseDir, "files")

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
            .put("changed", JSONArray(result.changed))
            .put("added", JSONArray(result.added))
            .put("removed", result.removed)
            .put("failed", result.failed)
            .put("unknown", result.unknown)
        prefs.edit().putString(KEY_CHECK, json.toString()).apply()
    }

    /**
     * 统计本地已有的文件：先看 [filesRoot]，再回落到导入的资源包。会逐个 stat / 查 zip 条目，
     * 必须在后台线程调用。[includeImportPack] 为 false 时只算已下载的文件。
     */
    fun summary(index: AssetIndex, includeImportPack: Boolean = true): AssetSummary {
        val pack = if (includeImportPack) importSource() else null
        try {
            var present = 0
            var bytes = 0L
            for (file in index.files) {
                val length = File(filesRoot, file.rel).length()
                val size = if (length > 0) length else pack?.size(file.rel) ?: -1L
                if (size > 0) {
                    present++
                    bytes += size
                }
            }
            return AssetSummary(present, bytes)
        } finally {
            pack?.close()
        }
    }

    /** 是否装过资源：下载目录或导入的资源包任一存在。 */
    fun hasAssets(): Boolean = filesRoot.isDirectory || importInfo() != null

    /** 连接外部服务器时，即使资源版本与本地不一致也优先使用本地资源（连接页的开关）。 */
    fun preferLocalAlways(): Boolean = prefs.getBoolean(KEY_PREFER_LOCAL, false)

    fun setPreferLocalAlways(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_LOCAL, value).apply()
    }

    /** 本地模式使用的来源：下载目录优先、资源包兜底；都没有时返回 null（请求交给 Node，返回 404，客户端显示占位）。 */
    fun openSource(): AssetSource? {
        val downloaded = if (filesRoot.isDirectory) DirectoryAssetSource(filesRoot) else null
        val overlay = importSource()
        return if (downloaded != null) OverlayAssetSource(downloaded, overlay) else overlay
    }

    /** 打开导入的资源包；没导入过、或授权已失效时返回 null。调用方负责 close。 */
    fun importSource(): ZipAssetSource? {
        val info = importInfo() ?: return null
        return try {
            ZipAssetSource.open(appContext, info.uri)
        } catch (e: Exception) {
            Log.w(TAG, "Imported asset pack unavailable: ${info.uri}", e)
            null
        }
    }

    /** 安装资源包：删掉已下载的文件，让资源包成为本地资源的来源。会递归删除文件，必须在后台线程调用。 */
    fun useImport(info: ImportInfo) {
        baseDir.deleteRecursively()
        importInfo()?.takeIf { it.uri != info.uri }?.let { releaseUri(it.uri) }
        prefs.edit()
            .putString(KEY_IMPORT_URI, info.uri.toString())
            .putString(KEY_IMPORT_NAME, info.name)
            .putLong(KEY_IMPORT_BYTES, info.bytes)
            .putInt(KEY_IMPORT_COUNT, info.count)
            .remove(KEY_ORIGIN)
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
        /** 1.1 之前的「在线下载 / 资源包」模式标记，只用于清理旧值。 */
        const val KEY_ORIGIN = "origin"
        const val KEY_PREFER_LOCAL = "prefer_local_assets"
        const val KEY_IMPORT_URI = "import_uri"
        const val KEY_IMPORT_NAME = "import_name"
        const val KEY_IMPORT_BYTES = "import_bytes"
        const val KEY_IMPORT_COUNT = "import_count"
        const val KEY_CHECK = "check"
    }
}
