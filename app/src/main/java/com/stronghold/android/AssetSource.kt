package com.stronghold.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/** 本地模式下 `/assets/<rel>` 的来源；WebView 会在多个线程上并发调用 [open]。 */
internal interface AssetSource : Closeable {
    /** 文件不存在时返回 null。 */
    fun open(rel: String): InputStream?

    /** 文件大小；不存在时返回 -1。 */
    fun size(rel: String): Long
}

/** 在线下载到 filesDir 的资源。 */
internal class DirectoryAssetSource(private val root: File) : AssetSource {
    override fun open(rel: String): InputStream? {
        val file = File(root, rel)
        return if (file.isFile && file.length() > 0) FileInputStream(file) else null
    }

    override fun size(rel: String): Long {
        val file = File(root, rel)
        return if (file.isFile && file.length() > 0) file.length() else -1
    }

    override fun close() = Unit
}

/** message 直接显示给用户。 */
internal class AssetPackException(message: String) : IOException(message)

internal const val NO_ASSETS_IN_PACK = "压缩包中没有找到游戏资源（需要与上游 public/assets 相同的 assets/ 目录）"

private val ASSET_ENTRY = Regex("^(.*?)assets/[^/]+/.+")

/**
 * 用户导入的 zip：通过持久授权的 SAF URI 直接读取，不解压。
 * 条目可以位于 `assets/…`、`public/assets/…` 或任意外层目录下，前缀在打开时识别。
 */
internal class ZipAssetSource private constructor(
    private val zip: ChannelZip,
    private val prefix: String,
) : AssetSource {

    /** 条目大小；不存在或是目录时返回 -1。 */
    override fun size(rel: String): Long {
        val entry = zip.entries[prefix + rel] ?: return -1
        return if (entry.isDirectory) -1 else entry.size
    }

    override fun open(rel: String): InputStream? {
        val entry = zip.entries[prefix + rel] ?: return null
        return if (entry.isDirectory) null else zip.open(entry)
    }

    override fun close() = zip.close()

    companion object {
        private const val TAG = "StrongholdAssets"

        fun open(context: Context, uri: Uri): ZipAssetSource {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            } ?: throw AssetPackException("无法打开该文件")

            // 不能按 /proc/self/fd 路径重新打开：分区存储下应用无权直接打开 Download 中的真实路径。
            // 直接在已授权的文件描述符上定位读取；关闭流时一并关闭 pfd。
            val zip = try {
                ChannelZip.open(ParcelFileDescriptor.AutoCloseInputStream(pfd))
            } catch (e: IOException) {
                Log.w(TAG, "Cannot read zip $uri", e)
                throw AssetPackException(
                    "无法读取该压缩包。请确认是 zip 格式，并已保存到手机本地存储（网盘中的文件可能无法直接读取）"
                )
            }

            val prefix = zip.entries.values
                .filterNot { it.isDirectory }
                .firstNotNullOfOrNull { ASSET_ENTRY.find(it.name)?.groupValues?.get(1) }
            if (prefix == null) {
                zip.close()
                throw AssetPackException(NO_ASSETS_IN_PACK)
            }
            return ZipAssetSource(zip, prefix + "assets/")
        }
    }
}

/**
 * 只读 zip：在 [FileChannel] 上按位置读取（可多线程并发），支持 stored / deflate 和 zip64。
 * 条目按中央目录顺序保存在 [entries] 中。
 */
internal class ChannelZip private constructor(
    private val input: FileInputStream,
    private val channel: FileChannel,
) : Closeable {

    class Entry(
        val name: String,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith('/')
    }

    lateinit var entries: Map<String, Entry>
        private set

    /** 不支持的压缩方式返回 null。 */
    fun open(entry: Entry): InputStream? {
        val local = read(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (local.getInt(0) != LOCAL_SIG) throw ZipException("bad local header: ${entry.name}")
        val start = entry.localHeaderOffset + LOCAL_HEADER_SIZE + local.u16(26) + local.u16(28)
        return when (entry.method) {
            METHOD_STORED -> RangeStream(channel, start, entry.size, pad = false)
            METHOD_DEFLATED -> {
                // nowrap 模式的 Inflater 需要在数据末尾多给一个占位字节（与 java.util.zip.ZipFile 相同）。
                val raw = RangeStream(channel, start, entry.compressedSize, pad = true)
                object : InflaterInputStream(raw, Inflater(true), 8192) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inf.end()
                        }
                    }
                }
            }
            else -> null
        }
    }

    override fun close() {
        try {
            input.close()
        } catch (_: IOException) {
        }
    }

    private fun read(position: Long, length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var p = position
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, p)
            if (n < 0) throw EOFException("unexpected end of zip")
            p += n
        }
        buffer.flip()
        return buffer
    }

    private fun readCentralDirectory(): Map<String, Entry> {
        val fileSize = channel.size()
        if (fileSize < EOCD_SIZE) throw ZipException("not a zip file")
        val tailLength = minOf(fileSize, EOCD_SIZE + 0xFFFFL).toInt()
        val tailStart = fileSize - tailLength
        val tail = read(tailStart, tailLength)
        var eocd = -1
        for (i in tailLength - EOCD_SIZE downTo 0) {
            if (tail.getInt(i) == EOCD_SIG) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw ZipException("end of central directory not found")

        var count = tail.u16(eocd + 10).toLong()
        var cdSize = tail.u32(eocd + 12)
        var cdOffset = tail.u32(eocd + 16)
        if (count == 0xFFFFL || cdSize == MAX32 || cdOffset == MAX32) {
            val locatorPos = tailStart + eocd - ZIP64_LOCATOR_SIZE
            if (locatorPos < 0) throw ZipException("zip64 locator missing")
            val locator = read(locatorPos, ZIP64_LOCATOR_SIZE)
            if (locator.getInt(0) != ZIP64_LOCATOR_SIG) throw ZipException("zip64 locator missing")
            val zip64 = read(locator.getLong(8), ZIP64_EOCD_SIZE)
            if (zip64.getInt(0) != ZIP64_EOCD_SIG) throw ZipException("zip64 end record missing")
            count = zip64.getLong(32)
            cdSize = zip64.getLong(40)
            cdOffset = zip64.getLong(48)
        }
        if (count < 0 || count > Int.MAX_VALUE || cdSize < 0 || cdSize > Int.MAX_VALUE ||
            cdOffset < 0 || cdOffset + cdSize > fileSize
        ) {
            throw ZipException("bad central directory bounds")
        }

        val cd = read(cdOffset, cdSize.toInt())
        val result = LinkedHashMap<String, Entry>(count.toInt() * 2)
        var p = 0
        repeat(count.toInt()) {
            if (p + CD_HEADER_SIZE > cd.limit() || cd.getInt(p) != CD_SIG) throw ZipException("bad central directory entry")
            val method = cd.u16(p + 10)
            val crc = cd.u32(p + 16)
            var compressed = cd.u32(p + 20)
            var size = cd.u32(p + 24)
            val nameLength = cd.u16(p + 28)
            val extraLength = cd.u16(p + 30)
            val commentLength = cd.u16(p + 32)
            var localOffset = cd.u32(p + 42)
            val nameStart = p + CD_HEADER_SIZE
            val extraEnd = nameStart + nameLength + extraLength
            if (extraEnd + commentLength > cd.limit()) throw ZipException("bad central directory entry")
            val nameBytes = ByteArray(nameLength)
            for (i in 0 until nameLength) nameBytes[i] = cd.get(nameStart + i)

            // zip64 扩展字段：只按顺序列出头部中为 0xFFFFFFFF 的值。
            var e = nameStart + nameLength
            while (e + 4 <= extraEnd) {
                val id = cd.u16(e)
                val length = cd.u16(e + 2)
                if (id == ZIP64_EXTRA_ID) {
                    var q = e + 4
                    val end = q + length
                    if (size == MAX32 && q + 8 <= end) { size = cd.getLong(q); q += 8 }
                    if (compressed == MAX32 && q + 8 <= end) { compressed = cd.getLong(q); q += 8 }
                    if (localOffset == MAX32 && q + 8 <= end) { localOffset = cd.getLong(q) }
                }
                e += 4 + length
            }

            val name = String(nameBytes, Charsets.UTF_8)
            result[name] = Entry(name, method, crc, compressed, size, localOffset)
            p = extraEnd + commentLength
        }
        return result
    }

    /** 文件中 [position] 起的 [remaining] 字节；[pad] 为 true 时末尾多返回一个 0 字节。 */
    private class RangeStream(
        private val channel: FileChannel,
        private var position: Long,
        private var remaining: Long,
        private var pad: Boolean,
    ) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) {
                if (!pad) return -1
                pad = false
                b[off] = 0
                return 1
            }
            val n = channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), remaining).toInt()), position)
            if (n < 0) throw EOFException("truncated zip entry")
            position += n
            remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private const val LOCAL_SIG = 0x04034b50
        private const val CD_SIG = 0x02014b50
        private const val EOCD_SIG = 0x06054b50
        private const val ZIP64_LOCATOR_SIG = 0x07064b50
        private const val ZIP64_EOCD_SIG = 0x06064b50
        private const val ZIP64_EXTRA_ID = 0x0001
        private const val LOCAL_HEADER_SIZE = 30
        private const val CD_HEADER_SIZE = 46
        private const val EOCD_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_SIZE = 56
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val MAX32 = 0xFFFFFFFFL

        private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
        private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and MAX32

        /** 读取中央目录；失败时关闭 [input] 并抛出 IOException。 */
        fun open(input: FileInputStream): ChannelZip {
            val zip = ChannelZip(input, input.channel)
            try {
                zip.entries = zip.readCentralDirectory()
            } catch (e: IOException) {
                zip.close()
                throw e
            } catch (e: RuntimeException) {
                // 越界等格式错误统一视为无法读取。
                zip.close()
                throw ZipException("malformed zip: $e")
            }
            return zip
        }
    }
}

internal fun assetMimeType(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "mp3" -> "audio/mpeg"
    "atlas" -> "text/plain"
    "json" -> "application/json"
    else -> "application/octet-stream"
}
