package com.stronghold.android

// 下载校验与 atlas 改写：逐条照搬上游 tools/assets/formats.mjs、atlas.mjs、sources.mjs，
// 保证设备上下载得到的文件与上游 fetch-assets.mjs 产出的 public/assets 逐字节一致。

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun ByteArray.latin1(from: Int, to: Int): String = String(this, from, to - from, Charsets.ISO_8859_1)

private fun ByteArray.u32(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

/** PNG IHDR 中的宽高；不是 PNG 或宽高为 0 时返回 null。只需要文件前 24 字节。 */
internal fun pngSize(head: ByteArray): Pair<Int, Int>? {
    if (head.size < 24) return null
    for (i in PNG_SIGNATURE.indices) if (head[i] != PNG_SIGNATURE[i]) return null
    if (head.latin1(12, 16) != "IHDR") return null
    val width = head.u32(16)
    val height = head.u32(20)
    if (width == 0L || height == 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
    return width.toInt() to height.toInt()
}

private val ATLAS_PAGE_LINE = Regex("^[^\\s:][^\\r\\n:]*\\.(png|webp|jpg)\\s*$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

/** 上游 formats.mjs 的 validate：拒绝截断的文件和 HTML 错误页。 */
internal fun isValidPayload(kind: AssetKind, buf: ByteArray): Boolean = when (kind) {
    AssetKind.PNG -> pngSize(buf) != null && buf.size >= 12 && buf.latin1(buf.size - 8, buf.size - 4) == "IEND"
    AssetKind.MP3 -> buf.size >= 128 &&
        (buf.latin1(0, 3) == "ID3" || ((buf[0].toInt() and 0xFF) == 0xFF && (buf[1].toInt() and 0xE0) == 0xE0))
    AssetKind.ATLAS -> {
        val text = String(buf, Charsets.UTF_8)
        !text.contains('\u0000') && ATLAS_PAGE_LINE.containsMatchIn(text)
    }
    AssetKind.SKEL -> buf.size >= 32 && buf.latin1(0, 16).lowercase().let {
        !it.startsWith("<!doctype") && !it.startsWith("<html") && !it.startsWith("404")
    }
    AssetKind.OTHER -> buf.isNotEmpty()
}

private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

/** 上游 sources.mjs 的 safeName：本地文件名只保留 [A-Za-z0-9._-]。 */
internal fun safeName(name: String): String = name.replace(UNSAFE_NAME_CHARS, "_").ifEmpty { "_" }

/** [changed] 为 false 时文件无需写回（与上游一样保留原文件的 BOM/CRLF）。 */
internal data class NormalizedAtlas(val text: String, val changed: Boolean)

private class AtlasPage(val name: String, val line: Int) {
    val fields = HashMap<String, String>()
    val fieldLines = HashMap<String, Int>()
    var lastFieldLine = line
}

private fun splitEntry(line: String): Pair<String, String>? {
    val t = line.trim()
    if (t.isEmpty()) return null
    val colon = t.indexOf(':')
    if (colon == -1) return null
    return t.substring(0, colon).trim() to t.substring(colon + 1).trim()
}

private fun preprocessAtlas(text: String): String = text.removePrefix("\uFEFF").replace(Regex("\r\n?"), "\n")

/** 上游 atlas.mjs 的 parseAtlas：空行后（或文件开头）的第一个非空行是页面名，其后的 `key: value` 行是页面字段。 */
private fun parseAtlas(lines: List<String>): List<AtlasPage> {
    val pages = ArrayList<AtlasPage>()
    var page: AtlasPage? = null
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            page = null
            i++
            continue
        }
        if (page == null) {
            page = AtlasPage(line.trim(), i).also(pages::add)
            i++
            while (i < lines.size) {
                val (key, value) = splitEntry(lines[i]) ?: break
                page.fields[key] = value
                page.fieldLines[key] = i
                page.lastFieldLine = i
                i++
            }
            continue
        }
        // 区域：名称行加若干字段行。
        i++
        while (i < lines.size && splitEntry(lines[i]) != null) i++
    }
    return pages
}

private val SIZE_VALUE = Regex("^(\\d+)\\s*,\\s*(\\d+)$")

/**
 * 上游 spine.mjs 调用的 `normalizeAtlas(text, { pageSize, pma, renamePage: safeName })`：
 * 页面名改为 safeName，补上或修正 `size: W,H`，pma 模型补 `pma: true`。幂等。
 * 任一页面拿不到实际尺寸（PNG 缺失或无效）时返回 null，与上游一样不改写。
 */
internal fun normalizeAtlas(text: String, pma: Boolean, pageSize: (String) -> Pair<Int, Int>?): NormalizedAtlas? {
    val original = preprocessAtlas(text)
    val lines = original.split("\n")
    val pages = parseAtlas(lines)
    if (pages.isEmpty()) return null
    val inserts = HashMap<Int, MutableList<String>>()
    val replace = HashMap<Int, String>()
    fun addAfter(index: Int, line: String) = inserts.getOrPut(index) { ArrayList() }.add(line)

    for (page in pages) {
        val local = safeName(page.name)
        if (local != page.name) replace[page.line] = local
        val (width, height) = pageSize(page.name) ?: return null
        val size = page.fields["size"]
        if (size == null) {
            addAfter(page.line, "size: $width,$height")
        } else {
            val match = SIZE_VALUE.find(size)
            if (match == null ||
                match.groupValues[1].toLongOrNull() != width.toLong() ||
                match.groupValues[2].toLongOrNull() != height.toLong()
            ) {
                replace[page.fieldLines.getValue("size")] = "size: $width,$height"
            }
        }
        if (pma && "pma" !in page.fields) addAfter(page.lastFieldLine, "pma: true")
    }

    val out = ArrayList<String>(lines.size + inserts.size)
    for (i in lines.indices) {
        out += replace[i] ?: lines[i]
        // size 必须紧跟页面名，pma 排在其他字段之后。
        inserts[i]?.let { extra -> out += extra.sortedBy { if (it.startsWith("size:")) 0 else 1 } }
    }
    val result = out.joinToString("\n")
    return NormalizedAtlas(result, result != original)
}

/** atlas 中所有页面名（原始写法，未经 safeName）。 */
internal fun atlasPages(text: String): List<String> = parseAtlas(preprocessAtlas(text).split("\n")).map { it.name }
