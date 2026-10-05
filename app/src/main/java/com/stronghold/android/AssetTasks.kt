package com.stronghold.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random

internal enum class TaskKind { DOWNLOAD, UPDATE, CHECK, EXPORT }

internal sealed interface TaskState {
    data object Idle : TaskState

    data class Running(
        val kind: TaskKind,
        val done: Int,
        val total: Int,
        val bytes: Long,
        val failed: Int,
        val bytesPerSec: Long,
        /** 已点取消，正在断开进行中的请求。 */
        val cancelling: Boolean = false,
    ) : TaskState

    data class Finished(val kind: TaskKind, val success: Boolean, val message: String) : TaskState
}

/** 检查结论文字："已是最新"，或用 "，" 连接的非零项。 */
internal fun CheckResult.summaryText(): String {
    if (changed.isEmpty() && removed == 0 && failed == 0) return "已是最新"
    return buildList {
        if (changed.isNotEmpty()) add("有 ${changed.size} 个文件更新")
        if (removed > 0) add("$removed 个文件在源上已移除")
        if (failed > 0) add("$failed 个文件无法检查（网络）")
    }.joinToString("，")
}

internal fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1048576.0)

/**
 * 进程级的资源任务：在线下载、更新有变化的文件、检查原项目下载地址是否有更新、把已下载的资源导出为 zip。
 * 下载规则照搬上游 tools/assets/downloader.mjs 的 runJob：逐个候选 URL，先原地址再 jsDelivr 镜像，
 * 每个来源重试 3 次，404 直接换下一个来源，校验内容后先写 .part 再改名。
 * 一次只运行一个任务；状态回调都在主线程。
 */
internal object AssetTasks {
    private const val TAG = "StrongholdAssets"
    private const val CONCURRENCY = 8
    private const val DOWNLOAD_ATTEMPTS = 3
    private const val CHECK_ATTEMPTS = 2
    private const val BACKOFF_MS = 400L
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val LEDGER_FLUSH_EVERY = 200
    private const val SPACE_MARGIN_BYTES = 64L shl 20
    private const val USER_AGENT = "stronghold-android-assets/1.0"

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<(TaskState) -> Unit>()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** 进行中的请求；取消时逐个 disconnect，让阻塞的读取立刻以 IOException 结束。 */
    private val connections: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var state: TaskState = TaskState.Idle

    fun state(): TaskState = state

    /** 只能在主线程调用；注册时立即回调一次当前状态。 */
    fun addListener(listener: (TaskState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (TaskState) -> Unit) {
        listeners -= listener
    }

    /** 尚未开始的文件直接跳过；进行中的请求立即断开，已下载完成的文件保留。只能在主线程调用。 */
    fun cancel() {
        if (!running.get() || !cancelled.compareAndSet(false, true)) return
        (state as? TaskState.Running)?.let { publish(it.copy(cancelling = true)) }
        // disconnect 会关闭 socket，放到后台线程，避免主线程网络 I/O。
        thread(name = "Stronghold-AssetsCancel") { connections.toList().forEach { it.disconnect() } }
    }

    fun startDownload(context: Context, force: Boolean): Boolean {
        val app = context.applicationContext
        return start(TaskKind.DOWNLOAD) {
            val store = AssetStore(app)
            val index = AssetIndex.get(app)
            store.useDownload()
            val ledger = store.readLedger()
            val targets = if (force) index.files else index.files.filter { needsDownload(store.filesRoot, it, ledger) }
            insufficientSpace(app, TaskKind.DOWNLOAD, targets)?.let { return@start it }
            val result = downloadAll(TaskKind.DOWNLOAD, store, ledger, targets)
            normalizeAtlases(store.filesRoot, index)
            when {
                cancelled.get() -> TaskState.Finished(TaskKind.DOWNLOAD, false, "已取消，已下载的文件会保留")
                result.failed > 0 ->
                    TaskState.Finished(TaskKind.DOWNLOAD, false, "下载结束：${result.failed} 个文件失败，可点「继续下载」重试")
                else -> TaskState.Finished(TaskKind.DOWNLOAD, true, "下载完成：${targets.size} 个文件")
            }
        }
    }

    fun startUpdate(context: Context, rels: List<String>): Boolean {
        val app = context.applicationContext
        return start(TaskKind.UPDATE) {
            val store = AssetStore(app)
            val index = AssetIndex.get(app)
            val targets = rels.mapNotNull(index.byRel::get)
            insufficientSpace(app, TaskKind.UPDATE, targets)?.let { return@start it }
            val result = downloadAll(TaskKind.UPDATE, store, store.readLedger(), targets)
            normalizeAtlases(store.filesRoot, index)
            store.lastCheck()?.let { check ->
                store.saveCheck(check.copy(changed = check.changed.filterNot(result.succeeded::contains)))
            }
            when {
                cancelled.get() -> TaskState.Finished(TaskKind.UPDATE, false, "已取消，已下载的文件会保留")
                result.failed > 0 -> TaskState.Finished(TaskKind.UPDATE, false, "更新结束：${result.failed} 个文件失败")
                else -> TaskState.Finished(TaskKind.UPDATE, true, "更新完成：${result.succeeded.size} 个文件")
            }
        }
    }

    fun startCheck(context: Context): Boolean {
        val app = context.applicationContext
        return start(TaskKind.CHECK) {
            val store = AssetStore(app)
            val index = AssetIndex.get(app)
            val origin = store.origin() ?: return@start TaskState.Finished(TaskKind.CHECK, false, "尚未安装游戏资源")
            val items = when (origin) {
                AssetOrigin.DOWNLOAD -> {
                    val ledger = store.readLedger()
                    index.files.mapNotNull { file ->
                        val entry = ledger[file.rel] ?: return@mapNotNull null
                        CheckItem(file.rel, listOfNotNull(entry.url, mirrorUrl(entry.url)), entry.etag, entry.bytes)
                    }
                }
                AssetOrigin.IMPORT -> {
                    val source = store.openSource() as? ZipAssetSource
                        ?: return@start TaskState.Finished(TaskKind.CHECK, false, "无法读取已导入的资源包，请重新导入")
                    source.use { zip ->
                        index.files.mapNotNull { file ->
                            val size = zip.size(file.rel)
                            if (size <= 0) return@mapNotNull null
                            // atlas 在资源包中是改写后的版本，只能和上游账本记录的原始大小比较。
                            val expected = if (file.kind == AssetKind.ATLAS) file.bytes else size
                            CheckItem(file.rel, file.urls.flatMap { listOfNotNull(it, mirrorUrl(it)) }, null, expected)
                        }
                    }
                }
            }
            checkAll(origin, items, store)
        }
    }

    /**
     * 把当前使用的完整资源（在线下载的文件或已导入的资源包）打包成 `assets/<rel>` 结构的 zip，
     * 与「导入资源包」兼容，保存到公共"下载"目录。png / mp3 本身已压缩，直接存储；其余 deflate。
     * 资源不完整时不导出；取消或出错时删除半成品。
     */
    fun startExport(context: Context): Boolean {
        val app = context.applicationContext
        return start(TaskKind.EXPORT) {
            val store = AssetStore(app)
            val index = AssetIndex.get(app)
            val origin = store.origin() ?: return@start TaskState.Finished(TaskKind.EXPORT, false, "尚未安装游戏资源")
            val source = store.openSource() ?: return@start TaskState.Finished(
                TaskKind.EXPORT, false,
                if (origin == AssetOrigin.IMPORT) "无法读取已导入的资源包，请重新导入" else "尚未下载游戏资源",
            )
            source.use { exportFrom(app, index, it) }
        }
    }

    private fun exportFrom(context: Context, index: AssetIndex, source: AssetSource): TaskState.Finished {
        val sizes = index.files.map { source.size(it.rel) }
        val present = sizes.count { it > 0 }
        if (present < index.files.size) {
            return TaskState.Finished(TaskKind.EXPORT, false, "资源不完整（$present/${index.files.size} 个文件），无法导出")
        }

        val needed = sizes.sum()
        val usable = (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace
        if (usable < needed + SPACE_MARGIN_BYTES) {
            return TaskState.Finished(
                TaskKind.EXPORT, false, "存储空间不足：需要约 ${formatMb(needed)}，可用 ${formatMb(usable)}",
            )
        }

        val target = createDownloadTarget(context, "stronghold-assets-${index.manifestHash}.zip", "application/zip")
        val progress = Progress(TaskKind.EXPORT, index.files.size)
        progress.report(force = true)
        var completed = false
        try {
            ZipOutputStream(BufferedOutputStream(target.output, 1 shl 16)).use { zip ->
                for (file in index.files) {
                    if (cancelled.get()) break
                    val bytes = source.open(file.rel)?.use { it.readBytes() }
                        ?: throw IOException("资源读取失败：${file.rel}")
                    val entry = ZipEntry("assets/" + file.rel)
                    if (file.kind == AssetKind.PNG || file.kind == AssetKind.MP3) {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.compressedSize = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                    progress.bytes.addAndGet(bytes.size.toLong())
                    progress.done.incrementAndGet()
                    progress.report()
                }
            }
            completed = !cancelled.get()
        } finally {
            if (completed) target.commit() else target.abort()
        }
        progress.report(force = true)
        if (!completed) return TaskState.Finished(TaskKind.EXPORT, false, "已取消导出")
        return TaskState.Finished(
            TaskKind.EXPORT, true,
            "已导出 ${index.files.size} 个文件到「下载/${target.displayName}」",
        )
    }

    // ---- 任务框架 ----

    private fun start(kind: TaskKind, body: () -> TaskState.Finished): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        publish(TaskState.Running(kind, 0, 0, 0, 0, 0))
        thread(name = "Stronghold-Assets") {
            val finished = try {
                body()
            } catch (t: Throwable) {
                Log.e(TAG, "Asset task $kind failed", t)
                TaskState.Finished(kind, false, "出错：${t.message ?: t.javaClass.simpleName}")
            }
            Log.i(TAG, "Asset task $kind finished: ${finished.message}")
            running.set(false)
            publish(finished)
        }
        return true
    }

    private fun publish(next: TaskState) {
        main.post {
            state = next
            for (listener in listeners.toList()) listener(next)
        }
    }

    private class Progress(val kind: TaskKind, val total: Int) {
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val bytes = AtomicLong()
        private val startedAt = SystemClock.elapsedRealtime()
        private var lastPostAt = 0L

        @Synchronized
        fun report(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastPostAt < PROGRESS_INTERVAL_MS) return
            lastPostAt = now
            val elapsed = (now - startedAt).coerceAtLeast(1)
            publish(
                TaskState.Running(
                    kind, done.get(), total, bytes.get(), failed.get(), bytes.get() * 1000 / elapsed, cancelled.get(),
                )
            )
        }
    }

    private inline fun <T> runParallel(items: List<T>, crossinline work: (T) -> Unit) {
        val pool: ExecutorService = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            for (item in items) pool.execute { if (!cancelled.get()) work(item) }
        } finally {
            pool.shutdown()
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

    private fun openConnection(url: String, method: String, ifNoneMatch: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = method
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            // 不让系统自动 gzip，否则 Content-Length 与实际字节对不上。
            setRequestProperty("Accept-Encoding", "identity")
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
        }

    /** 指数退避；取消后立即返回。 */
    private fun backoff(attempt: Int) {
        val until = SystemClock.elapsedRealtime() + BACKOFF_MS * (1L shl (attempt - 1)) + Random.nextLong(BACKOFF_MS + 1)
        while (!cancelled.get()) {
            val left = until - SystemClock.elapsedRealtime()
            if (left <= 0) return
            Thread.sleep(minOf(left, 100L))
        }
    }

    /**
     * 登记 [connection] 后再检查取消标志：cancel() 先置标志再断开已登记的连接，
     * 所以任何连接要么在这里被拦下，要么会被 cancel() 断开。
     */
    private fun track(connection: HttpURLConnection): Boolean {
        connections += connection
        return !cancelled.get()
    }

    // ---- 下载 ----

    private class DownloadResult(val succeeded: Set<String>, val failed: Int)

    /** 已有文件能否保留：账本有记录，且大小与记录一致（atlas 改写后大小会变，改为校验内容）。 */
    private fun needsDownload(root: File, file: AssetFile, ledger: Map<String, LedgerEntry>): Boolean {
        val target = File(root, file.rel)
        val length = target.length()
        if (!target.isFile || length == 0L) return true
        val entry = ledger[file.rel] ?: return true
        if (entry.bytes == length) return false
        return !(file.kind == AssetKind.ATLAS && isValidPayload(AssetKind.ATLAS, target.readBytes()))
    }

    private fun insufficientSpace(context: Context, kind: TaskKind, targets: List<AssetFile>): TaskState.Finished? {
        val needed = targets.sumOf { it.bytes ?: 0L }
        val usable = context.filesDir.usableSpace
        if (usable >= needed + SPACE_MARGIN_BYTES) return null
        return TaskState.Finished(kind, false, "存储空间不足：需要约 ${formatMb(needed)}，可用 ${formatMb(usable)}")
    }

    private fun downloadAll(
        kind: TaskKind,
        store: AssetStore,
        ledger: MutableMap<String, LedgerEntry>,
        targets: List<AssetFile>,
    ): DownloadResult {
        val progress = Progress(kind, targets.size)
        val succeeded = HashSet<String>()
        var sinceFlush = 0
        progress.report(force = true)
        runParallel(targets) { file ->
            val entry = try {
                fetchFile(file, store.filesRoot)
            } catch (t: Throwable) {
                Log.w(TAG, "Download crashed: ${file.rel}", t)
                null
            }
            if (entry == null) {
                // 取消导致的中断不算失败。
                if (!cancelled.get()) progress.failed.incrementAndGet()
            } else {
                progress.bytes.addAndGet(entry.bytes)
                synchronized(ledger) {
                    ledger[file.rel] = entry
                    succeeded += file.rel
                    if (++sinceFlush >= LEDGER_FLUSH_EVERY) {
                        sinceFlush = 0
                        store.writeLedger(HashMap(ledger))
                    }
                }
            }
            progress.done.incrementAndGet()
            progress.report()
        }
        store.writeLedger(ledger)
        progress.report(force = true)
        return DownloadResult(succeeded, progress.failed.get())
    }

    /** 成功时返回账本记录；所有来源都失败（404 或出错）时返回 null。 */
    private fun fetchFile(file: AssetFile, root: File): LedgerEntry? {
        var lastError: String? = null
        for (url in file.urls) {
            for (src in listOfNotNull(url, mirrorUrl(url))) {
                for (attempt in 1..DOWNLOAD_ATTEMPTS) {
                    if (cancelled.get()) return null
                    val connection = openConnection(src, "GET")
                    try {
                        if (!track(connection)) return null
                        val code = connection.responseCode
                        if (code == 404 || code == 410) break
                        if (code !in 200..299) {
                            lastError = "HTTP $code"
                        } else {
                            val body = connection.inputStream.use { it.readBytes() }
                            val expected = connection.contentLengthLong
                            if (expected > 0 && expected != body.size.toLong()) {
                                lastError = "truncated body ${body.size}/$expected"
                            } else if (!isValidPayload(file.kind, body)) {
                                lastError = "invalid ${file.kind} payload (${body.size} B)"
                            } else {
                                if (!writeAtomically(File(root, file.rel), body)) return null
                                return LedgerEntry(src, connection.getHeaderField("ETag"), body.size.toLong())
                            }
                        }
                    } catch (e: IOException) {
                        if (cancelled.get()) return null
                        lastError = e.message ?: e.javaClass.simpleName
                    } finally {
                        connections -= connection
                        connection.disconnect()
                    }
                    if (attempt < DOWNLOAD_ATTEMPTS) backoff(attempt)
                }
            }
        }
        Log.w(TAG, "Download failed: ${file.rel}: ${lastError ?: "not found (404) on all sources"}")
        return null
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        return try {
            part.writeBytes(bytes)
            if (part.renameTo(target)) return true
            target.delete()
            if (part.renameTo(target)) return true
            part.delete()
            Log.w(TAG, "Rename failed: ${target.path}")
            false
        } catch (e: IOException) {
            part.delete()
            Log.w(TAG, "Write failed: ${target.path}", e)
            false
        }
    }

    /**
     * 上游 spine.mjs processModels 的 atlas 收尾：按页面 PNG 的实际尺寸补/改 `size:`，pma 模型补 `pma: true`，
     * 页面名改为 safeName。幂等，所以每次下载结束都对全部已下载的 atlas 执行。
     */
    private fun normalizeAtlases(root: File, index: AssetIndex) {
        for (file in index.files) {
            if (file.kind != AssetKind.ATLAS) continue
            val atlas = File(root, file.rel)
            if (!atlas.isFile) continue
            try {
                val dir = atlas.parentFile ?: continue
                val normalized = normalizeAtlas(atlas.readText(Charsets.UTF_8), file.pma) { page ->
                    readHead(File(dir, safeName(page)))?.let(::pngSize)
                }
                if (normalized == null) {
                    Log.w(TAG, "atlas pages missing: ${file.rel}")
                } else if (normalized.changed) {
                    val tmp = File(atlas.path + ".tmp")
                    tmp.writeText(normalized.text, Charsets.UTF_8)
                    if (!tmp.renameTo(atlas)) {
                        tmp.delete()
                        Log.w(TAG, "Rename failed: ${atlas.path}")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Atlas normalization failed: ${file.rel}", e)
            }
        }
    }

    private fun readHead(file: File): ByteArray? {
        if (!file.isFile) return null
        return FileInputStream(file).use { input ->
            val head = ByteArray(24)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read == head.size) head else null
        }
    }

    // ---- 检查更新 ----

    /** [etag] / [bytes] 为期望值：与原项目下载地址当前返回的不同即视为有更新。 */
    private class CheckItem(val rel: String, val sources: List<String>, val etag: String?, val bytes: Long?)

    private enum class Verdict { UNCHANGED, CHANGED, REMOVED, FAILED, UNKNOWN }

    private fun checkAll(origin: AssetOrigin, items: List<CheckItem>, store: AssetStore): TaskState.Finished {
        val progress = Progress(TaskKind.CHECK, items.size)
        val changed = ArrayList<String>()
        val removed = AtomicInteger()
        val unknown = AtomicInteger()
        progress.report(force = true)
        runParallel(items) { item ->
            when (checkItem(item)) {
                Verdict.UNCHANGED -> Unit
                Verdict.CHANGED -> synchronized(changed) { changed += item.rel }
                Verdict.REMOVED -> removed.incrementAndGet()
                Verdict.FAILED -> progress.failed.incrementAndGet()
                Verdict.UNKNOWN -> unknown.incrementAndGet()
            }
            progress.done.incrementAndGet()
            progress.report()
        }
        progress.report(force = true)
        if (cancelled.get()) return TaskState.Finished(TaskKind.CHECK, false, "已取消检查")
        val result = CheckResult(
            checkedAt = System.currentTimeMillis(),
            origin = origin,
            changed = changed.sorted(),
            removed = removed.get(),
            failed = progress.failed.get(),
            unknown = unknown.get(),
        )
        store.saveCheck(result)
        return TaskState.Finished(TaskKind.CHECK, result.failed == 0, result.summaryText())
    }

    private fun checkItem(item: CheckItem): Verdict {
        var errors = 0
        for (src in item.sources) {
            var sourceError = true
            for (attempt in 1..CHECK_ATTEMPTS) {
                if (cancelled.get()) return Verdict.FAILED
                val connection = openConnection(src, "HEAD", item.etag)
                try {
                    if (!track(connection)) return Verdict.FAILED
                    val code = connection.responseCode
                    when {
                        code == HttpURLConnection.HTTP_NOT_MODIFIED -> return Verdict.UNCHANGED
                        code == 404 || code == 410 -> {
                            sourceError = false
                            break
                        }
                        code in 200..299 -> return judge(item, connection)
                    }
                } catch (e: IOException) {
                    if (cancelled.get()) return Verdict.FAILED
                    Log.d(TAG, "Check failed: $src: ${e.message}")
                } finally {
                    connections -= connection
                    connection.disconnect()
                }
                if (attempt < CHECK_ATTEMPTS) backoff(attempt)
            }
            if (sourceError) errors++
        }
        return if (errors == 0) Verdict.REMOVED else Verdict.FAILED
    }

    private fun judge(item: CheckItem, connection: HttpURLConnection): Verdict {
        val length = connection.contentLengthLong
        if (item.etag != null) {
            val etag = connection.getHeaderField("ETag")
            if (!etag.isNullOrEmpty()) return if (etag == item.etag) Verdict.UNCHANGED else Verdict.CHANGED
        }
        if (item.bytes == null || length < 0) return Verdict.UNKNOWN
        return if (length == item.bytes) Verdict.UNCHANGED else Verdict.CHANGED
    }
}
