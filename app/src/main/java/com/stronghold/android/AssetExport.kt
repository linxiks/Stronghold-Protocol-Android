package com.stronghold.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * 公共"下载"目录中的一个新文件。写完调用 [commit] 让它对其他应用可见；失败或取消时调用 [abort] 删除半成品。
 * [displayName] 是实际文件名（重名时系统会自动改名）。
 */
internal class DownloadTarget(
    val displayName: String,
    val output: OutputStream,
    private val onCommit: () -> Unit,
    private val onAbort: () -> Unit,
) {
    fun commit() = onCommit()

    fun abort() {
        try {
            output.close()
        } catch (_: IOException) {
        }
        onAbort()
    }
}

/** API 29+ 无需权限：MediaStore.Downloads；API 26–28 需要 WRITE_EXTERNAL_STORAGE，直接写公共 Download 目录。 */
internal fun createDownloadTarget(context: Context, name: String, mimeType: String): DownloadTarget =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(context, name, mimeType) else legacyTarget(context, name, mimeType)

private fun mediaStoreTarget(context: Context, name: String, mimeType: String): DownloadTarget {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        // 写完之前对其他应用不可见。
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("无法在下载目录创建文件")
    val output = try {
        resolver.openOutputStream(uri) ?: throw IOException("无法写入下载目录")
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
    val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null } ?: name
    return DownloadTarget(
        displayName = actualName,
        output = output,
        onCommit = {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        },
        onAbort = { resolver.delete(uri, null, null) },
    )
}

@Suppress("DEPRECATION")
private fun legacyTarget(context: Context, name: String, mimeType: String): DownloadTarget {
    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法访问下载目录")
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "")
    var file = File(dir, name)
    var n = 1
    while (file.exists()) file = File(dir, "$base ($n).$ext").also { n++ }
    val target = file
    return DownloadTarget(
        displayName = target.name,
        output = FileOutputStream(target),
        onCommit = {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        },
        onAbort = { target.delete() },
    )
}
