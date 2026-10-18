package com.stronghold.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 连接页左栏的"游戏资源"区块：下载 / 继续下载 / 重新下载、导入资源包、检查更新与局部更新、导出资源包。
 * 导入的资源包与已下载的文件同等视为本地已有内容，下载只补缺失的部分。
 * 会覆盖已有资源的操作（重新下载、导入后删除已下载的文件）都先弹窗确认。
 */
internal class AssetPanel(
    private val activity: Activity,
    private val store: AssetStore,
    private val onPickArchive: () -> Unit,
) {
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var importButton: Button
    private lateinit var checkButton: Button
    private lateinit var exportButton: Button
    private lateinit var artButton: Button
    private lateinit var artText: TextView

    private var disposed = false
    private var importing = false
    private var taskState: TaskState = TaskState.Idle
    private var artState: LocalArtState = LocalArtState.Idle
    private var summary: AssetSummary? = null
    private var total = 0
    private var totalBytes = 0L
    private var downloadAction: () -> Unit = {}
    private var checkAction: () -> Unit = {}
    private var artAction: () -> Unit = {}

    private val listener: (TaskState) -> Unit = { state ->
        val previous = taskState
        taskState = state
        render()
        // 任务刚结束：文件数量变了，重新统计。
        if (state is TaskState.Finished && previous is TaskState.Running) refreshSummary()
    }

    private val artListener: (LocalArtState) -> Unit = { state ->
        artState = state
        render()
    }

    fun createView(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.styledText("游戏资源", 13f, Palette.TEXT_MD).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(
                activity.microText("ASSETS", Palette.TEXT_DIM),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = activity.dp(8) },
            )
        }
        root.addView(header)

        statusText = activity.styledText("正在统计…", 12f, Palette.TEXT_LO).apply { maxLines = 2 }
        root.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(6) })

        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(Palette.MINT_500)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.LINE)
            indeterminateTintList = ColorStateList.valueOf(Palette.MINT_500)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(3)).apply { topMargin = activity.dp(6) })

        downloadButton = actionButton { downloadAction() }
        importButton = actionButton { onPickArchive() }.apply { text = "导入资源包" }
        checkButton = actionButton { checkAction() }
        exportButton = actionButton { exportAction() }.apply { text = "导出资源包" }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            listOf(listOf(downloadButton, importButton), listOf(checkButton, exportButton)).forEachIndexed { row, pair ->
                val line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pair[0], LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                    addView(pair[1], LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = activity.dp(8) })
                }
                addView(line, LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(36)).apply {
                    if (row > 0) topMargin = activity.dp(8)
                })
            }
        }
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(8) })

        artButton = actionButton { artAction() }.apply { text = "本地美术包" }
        root.addView(artButton, LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(36)).apply { topMargin = activity.dp(8) })

        artText = activity.styledText("", 12f, Palette.TEXT_LO).apply { maxLines = 3 }
        root.addView(artText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = activity.dp(6) })

        AssetTasks.addListener(listener)
        LocalArt.addListener(artListener)
        refreshSummary()
        return root
    }

    fun dispose() {
        disposed = true
        AssetTasks.removeListener(listener)
        LocalArt.removeListener(artListener)
    }

    // ---- 导出 ----

    private fun exportAction() {
        confirm(
            "导出资源包",
            "将把已下载的全部 $total 个文件（约 ${formatMb(summary?.bytes ?: 0L)}）打包为 zip，" +
                "保存到手机的「下载」目录。导出的文件可以通过「导入资源包」在本机或其他设备上使用。",
            "导出", ::startExport,
        )
    }

    /** API 26–28 写公共下载目录需要存储权限；API 29+ 通过 MediaStore 写入，无需权限。 */
    private fun startExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_EXPORT_PERMISSION)
            return
        }
        if (!AssetTasks.startExport(activity)) toast("已有任务在进行")
    }

    fun onExportPermissionResult(granted: Boolean) {
        if (disposed) return
        if (granted) startExport() else toast("没有存储权限，无法导出到下载目录")
    }

    // ---- 导入 ----

    private sealed interface ImportOutcome {
        class Ready(val info: ImportInfo, val summary: AssetSummary, val total: Int) : ImportOutcome
        class Failed(val message: String) : ImportOutcome
    }

    fun onArchivePicked(uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            toast("无法获得该文件的持续访问权限")
            return
        }
        importing = true
        render()
        thread(name = "Stronghold-AssetImport") {
            val outcome = try {
                val (name, size) = queryNameAndSize(uri)
                val index = AssetIndex.get(activity)
                val count = ZipAssetSource.open(activity, uri).use { src ->
                    index.files.count { src.size(it.rel) > 0 }
                }
                if (count == 0) {
                    ImportOutcome.Failed(NO_ASSETS_IN_PACK)
                } else {
                    ImportOutcome.Ready(
                        ImportInfo(uri, name, size, count),
                        store.summary(index, includeImportPack = false),
                        index.files.size,
                    )
                }
            } catch (e: AssetPackException) {
                ImportOutcome.Failed(e.message ?: NO_ASSETS_IN_PACK)
            } catch (e: Exception) {
                ImportOutcome.Failed("读取资源包失败：${e.message ?: e.javaClass.simpleName}")
            }
            activity.runOnUiThread {
                importing = false
                onImportInspected(uri, outcome)
            }
        }
    }

    private fun onImportInspected(uri: Uri, outcome: ImportOutcome) {
        val current = store.importInfo()
        val releaseIfUnused = {
            if (current?.uri != uri) store.releaseUri(uri)
            render()
        }
        if (disposed) {
            releaseIfUnused()
            return
        }
        when (outcome) {
            is ImportOutcome.Failed -> {
                releaseIfUnused()
                AlertDialog.Builder(activity, DIALOG_THEME)
                    .setTitle("无法导入")
                    .setMessage(outcome.message)
                    .setPositiveButton("确定", null)
                    .show()
            }
            is ImportOutcome.Ready -> {
                val info = outcome.info
                val files = "${info.count}/${outcome.total}"
                val apply = { applyImport(info) }
                when {
                    outcome.summary.present > 0 -> confirm(
                        "导入资源包",
                        "资源包「${info.name}」包含 $files 个文件。导入后将删除已下载的 " +
                            "${outcome.summary.present} 个文件（${formatMb(outcome.summary.bytes)}）。",
                        "导入", apply, releaseIfUnused,
                    )
                    current != null && current.uri != uri -> confirm(
                        "替换资源包",
                        "将改用资源包「${info.name}」（$files 个文件），不再使用「${current.name}」。",
                        "替换", apply, releaseIfUnused,
                    )
                    else -> apply()
                }
            }
        }
    }

    private fun applyImport(info: ImportInfo) {
        if (AssetTasks.state() is TaskState.Running) {
            if (store.importInfo()?.uri != info.uri) store.releaseUri(info.uri)
            toast("已有任务在进行")
            return
        }
        summary = null
        render()
        thread(name = "Stronghold-AssetImport") {
            store.useImport(info)
            refreshSummary()
        }
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        return (name ?: uri.lastPathSegment ?: "资源包") to size
    }

    // ---- 状态 ----

    private fun refreshSummary() {
        thread(name = "Stronghold-AssetSummary") {
            val index = AssetIndex.get(activity)
            val next = store.summary(index)
            activity.runOnUiThread {
                total = index.files.size
                totalBytes = index.totalBytes
                summary = next
                render()
            }
        }
    }

    private fun render() {
        if (disposed || !::statusText.isInitialized) return
        val state = taskState
        when {
            artState is LocalArtState.Running -> renderArtRunning(artState as LocalArtState.Running)
            state is TaskState.Running -> renderRunning(state)
            importing -> {
                setEnabled(downloadButton, false)
                setEnabled(importButton, false)
                setEnabled(checkButton, false)
                setEnabled(exportButton, false)
                setEnabled(artButton, false)
                showProgress(indeterminate = true)
                statusText.text = "正在读取资源包…"
            }
            else -> renderIdle(state as? TaskState.Finished)
        }
    }

    private fun renderRunning(state: TaskState.Running) {
        downloadButton.text = if (state.cancelling) "正在取消…" else "取消"
        downloadAction = { AssetTasks.cancel() }
        setEnabled(downloadButton, !state.cancelling)
        setEnabled(importButton, false)
        setEnabled(checkButton, false)
        setEnabled(exportButton, false)
        setEnabled(artButton, false)
        if (state.total > 0) {
            showProgress(indeterminate = false)
            progress.max = state.total
            progress.progress = state.done
        } else {
            showProgress(indeterminate = true)
        }
        statusText.text = if (state.cancelling) "正在取消，停止进行中的请求…" else when (state.kind) {
            TaskKind.CHECK -> "检查更新 ${state.done}/${state.total}"
            TaskKind.EXPORT -> "导出中 ${state.done}/${state.total} · ${formatMb(state.bytes)}"
            TaskKind.DOWNLOAD, TaskKind.UPDATE -> buildString {
                append("下载中 ${state.done}/${state.total} · ${formatMb(state.bytes)} · ${formatMb(state.bytesPerSec)}/s")
                if (state.failed > 0) append(" · 失败 ${state.failed}")
            }
        }
    }

    private fun renderIdle(finished: TaskState.Finished?) {
        renderArt()
        progress.visibility = View.GONE
        setEnabled(importButton, true)
        val summary = summary
        if (summary == null) {
            statusText.text = "正在统计…"
            setEnabled(downloadButton, false)
            setEnabled(checkButton, false)
            setEnabled(exportButton, false)
            return
        }

        val check = store.lastCheck()
        val complete = total > 0 && summary.present == total

        downloadButton.text = when {
            complete -> "重新下载"
            summary.present > 0 -> "继续下载"
            else -> "下载"
        }
        downloadAction = if (complete) {
            {
                confirm(
                    "重新下载全部资源",
                    "将从原项目地址重新下载全部 $total 个文件（约 ${formatMb(totalBytes)}），并覆盖已下载的文件。",
                    "重新下载", { startDownload(force = true) },
                )
            }
        } else {
            // 首次下载、继续下载都只补缺失的文件：资源包里已有的部分不会再下一遍。
            { startDownload(force = false) }
        }
        setEnabled(downloadButton, true)

        val changed = check?.changed.orEmpty()
        val added = check?.added.orEmpty()
        val pending = changed + added
        if (pending.isNotEmpty()) {
            checkButton.text = "更新 ${pending.size} 个"
            checkAction = {
                val parts = buildList {
                    if (changed.isNotEmpty()) add("${changed.size} 个有更新")
                    if (added.isNotEmpty()) add("${added.size} 个尚未安装")
                }.joinToString("，")
                confirm(
                    "更新资源",
                    "将下载 ${pending.size} 个文件（$parts），其余已是最新的不动。",
                    "更新", { if (!AssetTasks.startUpdate(activity, pending)) toast("已有任务在进行") },
                )
            }
        } else {
            checkButton.text = "检查更新"
            checkAction = { if (!AssetTasks.startCheck(activity)) toast("已有任务在进行") }
        }
        setEnabled(checkButton, summary.present > 0)
        // 只导出完整的资源：资源包之后可能被用户删掉，导出一份可留作备份。
        setEnabled(exportButton, complete)

        val firstLine = if (summary.present > 0) {
            "已下载 ${summary.present}/$total 个文件 · ${formatMb(summary.bytes)}"
        } else {
            "未安装 · 本地模式会显示占位画面"
        }
        val text = SpannableStringBuilder(firstLine)
        when {
            finished != null -> {
                text.append('\n')
                val start = text.length
                text.append(finished.message)
                if (!finished.success) {
                    text.setSpan(ForegroundColorSpan(Palette.DANGER), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            check != null -> {
                val now = System.currentTimeMillis()
                text.append('\n').append(check.summaryText()).append(" · 检查于 ")
                    .append(DateUtils.getRelativeTimeSpanString(check.checkedAt, now, DateUtils.MINUTE_IN_MILLIS))
            }
        }
        statusText.text = text
    }

    // ---- 本地美术包 ----

    /**
     * 本地美术包（官方 3D 棋盘与各战场场景）的按钮与状态。它不属于 [AssetTasks] 那批按索引逐文件下载的资源，
     * 但共用同一条进度条与取消语义，两者互斥。
     */
    private fun renderArt() {
        val status = LocalArt.status(activity)
        artButton.text = when (status.phase) {
            LocalArt.Phase.INSTALLED -> "重新下载本地美术包"
            LocalArt.Phase.READY -> "安装本地美术包"
            LocalArt.Phase.ABSENT -> "下载本地美术包"
        }
        artAction = { artConfirm(status) }
        setEnabled(artButton, true)

        val base = when (status.phase) {
            LocalArt.Phase.INSTALLED -> "本地美术包：已安装 · ${formatMb(status.bytes)}"
            LocalArt.Phase.READY -> "本地美术包：已下载，待安装 · ${formatMb(status.bytes)}"
            LocalArt.Phase.ABSENT -> "本地美术包：未下载，3D 棋盘与各战场官方场景不可用"
        }
        val text = SpannableStringBuilder(base)
        val finished = artState as? LocalArtState.Finished
        if (finished != null) {
            text.append('\n')
            val start = text.length
            text.append(finished.message)
            if (!finished.success) {
                text.setSpan(ForegroundColorSpan(Palette.DANGER), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        artText.text = text
    }

    private fun renderArtRunning(state: LocalArtState.Running) {
        artButton.text = if (state.cancelling) "正在取消…" else "取消"
        artAction = { LocalArt.cancel() }
        setEnabled(artButton, !state.cancelling)
        setEnabled(downloadButton, false)
        setEnabled(importButton, false)
        setEnabled(checkButton, false)
        setEnabled(exportButton, false)
        if (state.total > 0) {
            showProgress(indeterminate = false)
            progress.max = 1000
            progress.progress = ((state.done * 1000) / state.total).toInt()
        } else {
            showProgress(indeterminate = true)
        }
        artText.text = if (state.cancelling) {
            "本地美术包：正在取消…"
        } else when (state.stage) {
            LocalArtStage.DOWNLOAD -> buildString {
                append("本地美术包：下载中 ${formatMb(state.done)}")
                if (state.total > 0) append(" / ${formatMb(state.total)}")
            }
            LocalArtStage.INSTALL -> "本地美术包：安装中 ${state.done}/${state.total}"
        }
    }

    private fun artConfirm(status: LocalArt.Status) {
        val what = "官方 3D 棋盘（图集、tiles.json 与网格）和各战场的官方场景"
        val message: String
        val positive: String
        val force: Boolean
        when (status.phase) {
            LocalArt.Phase.INSTALLED -> {
                message = "将重新下载并覆盖已安装的本地美术包（$what，约 65 MB）。"
                positive = "重新下载"
                force = true
            }
            LocalArt.Phase.READY -> {
                message = "将把已下载的本地美术包（$what）安装到本机运行时目录。"
                positive = "安装"
                force = false
            }
            LocalArt.Phase.ABSENT -> {
                message = "将从本项目的 release 下载本地美术包（$what，约 157 MB），解到本机运行时目录。\n\n${LocalArt.url()}"
                positive = "下载"
                force = false
            }
        }
        confirm(
            "本地美术包", message, positive,
            { if (!LocalArt.start(activity, force)) toast("已有任务在进行") },
        )
    }

    // ---- 小工具 ----

    private fun startDownload(force: Boolean) {
        if (!AssetTasks.startDownload(activity, force)) toast("已有任务在进行")
    }

    private fun confirm(title: String, message: String, positive: String, onConfirm: () -> Unit, onCancel: () -> Unit = {}) {
        AlertDialog.Builder(activity, DIALOG_THEME)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onConfirm() }
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun showProgress(indeterminate: Boolean) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = indeterminate
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun actionButton(onClick: () -> Unit) = Button(activity).apply {
        background = activity.secondaryButtonBackground()
        flat()
        textSize = 13f
        setTextColor(Palette.TEXT_HI)
        typeface = Typeface.DEFAULT_BOLD
        setSingleLine(true)
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQUEST_EXPORT_PERMISSION = 1002
        private val DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert
    }
}
