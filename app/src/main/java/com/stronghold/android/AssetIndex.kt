package com.stronghold.android

import android.content.Context
import org.json.JSONObject

internal enum class AssetKind { PNG, MP3, ATLAS, SKEL, OTHER }

/**
 * 上游 data/assets.json 引用的一个 `/assets/<rel>` 文件。
 * [urls] 为上游 tools/fetch-assets.mjs 使用的原始下载地址（已百分号编码，按优先级排列）；
 * [bytes] 为上游账本记录的源文件大小（atlas 为改写前的大小），未知时为 null。
 */
internal data class AssetFile(
    val rel: String,
    val urls: List<String>,
    val kind: AssetKind,
    val bytes: Long?,
    val pma: Boolean,
)

/** APK 内 assets/asset-index.json：构建时由 tools/build-asset-index.mjs 生成。 */
internal class AssetIndex(val manifestHash: String, val files: List<AssetFile>) {
    val byRel: Map<String, AssetFile> = files.associateBy { it.rel }
    val totalBytes: Long = files.sumOf { it.bytes ?: 0L }

    companion object {
        private const val FILE_NAME = "asset-index.json"

        @Volatile
        private var cached: AssetIndex? = null

        /** 读取 APK 中的 assets/asset-index.json，进程内只解析一次；解析失败说明构建有误，直接抛出。 */
        fun get(context: Context): AssetIndex {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val text = context.applicationContext.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
                return parse(text).also { cached = it }
            }
        }

        private fun parse(text: String): AssetIndex {
            val root = JSONObject(text)
            val array = root.getJSONArray("files")
            val files = ArrayList<AssetFile>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val urls = o.getJSONArray("urls")
                files += AssetFile(
                    rel = o.getString("rel"),
                    urls = List(urls.length()) { urls.getString(it) },
                    kind = AssetKind.valueOf(o.getString("kind").uppercase()),
                    bytes = if (o.has("bytes")) o.getLong("bytes") else null,
                    pma = o.optBoolean("pma", false),
                )
            }
            return AssetIndex(root.getString("manifestHash"), files)
        }
    }
}

private val RAW_URL = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$")

/** 照搬上游 tools/assets/sources.mjs 的 mirrorUrl：raw.githubusercontent.com → jsDelivr；ArknightsAssets 的 voice 分支没有镜像。 */
internal fun mirrorUrl(url: String): String? {
    val (owner, repo, branch, path) = RAW_URL.matchEntire(url)?.destructured ?: return null
    if (owner == "ArknightsAssets" && branch == "voice") return null
    return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
}

private val SAFE_REL = Regex("[A-Za-z0-9._/-]+")

/** 只允许 [A-Za-z0-9._/-]；不得有空段或以 "." 开头的段（挡住 "."、".." 和点文件）。 */
internal fun isSafeAssetRel(rel: String): Boolean =
    SAFE_REL.matches(rel) && rel.split('/').all { it.isNotEmpty() && !it.startsWith('.') }
