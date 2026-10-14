package com.stronghold.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.concurrent.thread

/** 本地美术包正在做的两件事：下载 zip、解包进运行时目录。 */
internal enum class LocalArtStage { DOWNLOAD, INSTALL }

internal sealed interface LocalArtState {
    data object Idle : LocalArtState

    data class Running(
        val stage: LocalArtStage,
        val done: Long,
        val total: Long,
        /** 已点取消，正在断开进行中的请求。 */
        val cancelling: Boolean = false,
    ) : LocalArtState

    data class Finished(val success: Boolean, val message: String) : LocalArtState
}

/**
 * 本地客户端美术包：官方 3D 棋盘（图集、tiles.json、网格）与 11 个战场的官方场景。
 *
 * 上游把这些提取好的素材放进完整包（约 583 MB），公开镜像只有需要 UnityPy 解包的原始资源包，Android 端没法
 * 现提取；所以本项目 release 另出一份只含所需条目的 zip（`tools/fetch-local-art.mjs`），这里只管下载与安装。
 *
 * 页面由内置的 node 服务提供，它从运行时目录读 `data/local-assets.json`（清单）与 `public/assets/local/` 下的
 * 文件，两者缺一客户端就退回金属地块/2D 棋盘，所以两份都要落地。运行时目录在每次 APK 更新后重建
 * （`StrongholdInstaller` 的安装戳是包的 lastUpdateTime），因此 zip 留一份，重建后由 [ensureInstalled] 重装。
 */
internal object LocalArt {
    private const val TAG = "Stronghold"
    private const val DIR = "local-art"
    /** 清单里的相对路径；文件在这里出现就认为装好了。 */
    private const val MANIFEST = "data/local-assets.json"
    private const val ART_PREFIX = "public/assets/local/"
    private const val USER_AGENT = "Stronghold-Android"
    private const val PROGRESS_INTERVAL_MS = 250L

    /** 设备上的三种状态：没有包、有包但没装、已装。 */
    internal enum class Phase { ABSENT, READY, INSTALLED }

    internal data class Status(val phase: Phase, val bytes: Long)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(LocalArtState) -> Unit>()
    private val connections = CopyOnWriteArrayList<HttpURLConnection>()
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var state: LocalArtState = LocalArtState.Idle

    // ---- 对外 ----

    /** 下载地址：本项目 release 上的美术包，与固定的上游版本同名（见 `app/build.gradle.kts`）。 */
    fun url(): String = BuildConfig.LOCAL_ART_URL

    fun state(): LocalArtState = state

    fun addListener(listener: (LocalArtState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (LocalArtState) -> Unit) {
        listeners -= listener
    }

    /** 当前状态；[Status.bytes] 是已留存 zip 的大小。 */
    fun status(context: Context): Status {
        val pack = packFile(context)
        if (!pack.isFile) return Status(Phase.ABSENT, 0)
        val installed = File(StrongholdInstaller.root(context), MANIFEST).isFile
        return Status(if (installed) Phase.INSTALLED else Phase.READY, pack.length())
    }

    /** 下载（已有 zip 且 [force] 为假时直接用）并装进运行时目录；已有任务时返回 false。 */
    fun start(context: Context, force: Boolean = false): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        cancelled.set(false)
        publish(LocalArtState.Running(LocalArtStage.DOWNLOAD, 0, 0))
        val app = context.applicationContext
        thread(name = "Stronghold-LocalArt") {
            val finished = try {
                val pack = packFile(app)
                if (force || !pack.isFile) download(app, pack)
                if (cancelled.get()) {
                    LocalArtState.Finished(false, "已取消")
                } else {
                    install(app, pack)
                }
            } catch (t: Throwable) {
                if (cancelled.get()) {
                    LocalArtState.Finished(false, "已取消")
                } else {
                    Log.e(TAG, "Local art task failed", t)
                    LocalArtState.Finished(false, "出错：${t.message ?: t.javaClass.simpleName}")
                }
            }
            Log.i(TAG, "Local art task finished: $finished")
            busy.set(false)
            publish(finished)
        }
        return true
    }

    fun cancel() {
        if (!busy.get()) return
        cancelled.set(true)
        publishCancelling()
        for (connection in connections.toList()) {
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 运行时目录刚就绪时调用：留存过 zip 而目录里没有清单（全新安装，或 APK 更新后目录被重建）时把它装进去。
     * 同步执行，调用方在后台线程；失败只记日志，客户端照旧退回金属地块。
     */
    fun ensureInstalled(context: Context, runtimeRoot: File) {
        if (!runtimeRoot.isDirectory) return
        val pack = packFile(context)
        if (!pack.isFile) return
        if (File(runtimeRoot, MANIFEST).isFile) return
        Log.i(TAG, "Installing local art from ${pack.name}")
        try {
            extract(pack, runtimeRoot) { _, _ -> }
        } catch (t: Throwable) {
            // 美术包坏了或空间不够都不该拦住本地模式：删掉重下一份，这次照旧退回金属地块/2D 棋盘。
            Log.w(TAG, "Local art install failed", t)
            if (t is IOException) pack.delete()
        }
    }

    // ---- 下载 ----

    /** 下载到 [target] 的临时文件再改名，中断的半份不会当成本次结果。 */
    private fun download(context: Context, target: File) {
        target.parentFile?.mkdirs()
        // 只保留当前上游版本的包：APK 换版后旧包既不能用也不该占空间。
        target.parentFile?.listFiles()?.forEach { if (it.isFile && it != target) it.delete() }

        val connection = openConnection(url())
        try {
            if (!track(connection)) return
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = connection.contentLengthLong
            val temp = File(target.parentFile, "${target.name}.part")
            val started = SystemClock.elapsedRealtime()
            var done = 0L
            var lastPost = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled.get()) {
                            temp.delete()
                            return
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPost >= PROGRESS_INTERVAL_MS) {
                            lastPost = now
                            publish(LocalArtState.Running(LocalArtStage.DOWNLOAD, done, total))
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("下载不完整：$done/$total")
            if (!temp.renameTo(target)) throw IOException("无法保存 ${target.name}")
            Log.i(TAG, "Local art pack downloaded: ${formatMb(done)} in ${(SystemClock.elapsedRealtime() - started) / 1000}s")
        } finally {
            connections -= connection
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            // 不让系统自动 gzip，否则 Content-Length 与实际字节对不上。
            setRequestProperty("Accept-Encoding", "identity")
        }

    /** 登记连接后再检查取消标志：[cancel] 先置标志再断开已登记的连接，所以不会漏掉任何一条。 */
    private fun track(connection: HttpURLConnection): Boolean {
        connections += connection
        return !cancelled.get()
    }

    // ---- 安装 ----

    private fun install(context: Context, pack: File): LocalArtState.Finished {
        val root = StrongholdInstaller.root(context)
        if (!root.isDirectory) {
            // 运行时目录还没解出来（还没进过本地模式）：包先留着，启动时由 [ensureInstalled] 装。
            return LocalArtState.Finished(true, "美术包已就绪（${formatMb(pack.length())}），首次进入本地模式时安装")
        }
        extract(pack, root) { done, total -> publish(LocalArtState.Running(LocalArtStage.INSTALL, done, total)) }
        return LocalArtState.Finished(true, "本地美术包已安装（${formatMb(pack.length())}）")
    }

    /**
     * 把包里的 `public/assets/local/` 下的文件与 `data/local-assets.json` 写进 [runtimeRoot]。
     * 清单最后写：中途失败时它不在，下次启动会重装，而不是留下一个"装了但缺文件"的目录。
     */
    private fun extract(pack: File, runtimeRoot: File, report: (Long, Long) -> Unit) {
        val root = runtimeRoot.canonicalFile
        val prefix = root.path + File.separator
        ZipFile(pack).use { zip ->
            val entries = zip.entries().toList()
                .filter { !it.isDirectory && keep(it.name) }
                .sortedBy { if (it.name == MANIFEST) 1 else 0 }
            var done = 0L
            for (entry in entries) {
                if (cancelled.get()) throw IOException("已取消")
                val out = File(root, entry.name)
                if (!out.canonicalPath.startsWith(prefix)) throw IOException("包里的路径越界：${entry.name}")
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { input.copyTo(it) }
                }
                done++
                report(done, entries.size.toLong())
            }
            check(File(root, MANIFEST).isFile) { "美术包里没有 $MANIFEST" }
            Log.i(TAG, "Local art installed: $done files")
        }
    }

    private fun keep(name: String): Boolean = name == MANIFEST || name.startsWith(ART_PREFIX)

    // ---- 状态 ----

    private fun packFile(context: Context): File {
        val name = Uri.parse(url()).lastPathSegment ?: "stronghold-local-art.zip"
        return File(File(context.filesDir, DIR), name)
    }

    private fun publish(next: LocalArtState) {
        main.post {
            state = next
            for (listener in listeners.toList()) listener(next)
        }
    }

    private fun publishCancelling() {
        main.post {
            val current = state
            if (current is LocalArtState.Running && !current.cancelling) {
                state = current.copy(cancelling = true)
                for (listener in listeners.toList()) listener(state)
            }
        }
    }
}
