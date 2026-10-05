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
 * 连接页左栏的"游戏资源"区块：在线下载 / 继续下载 / 重新下载、导入资源包、检查更新与局部更新、导出资源包。
 * 会覆盖已有资源的操作（重新下载、更新、导入替换下载、下载替换导入）都先弹窗确认。
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

    private var disposed = false
    private var importing = false
    private var taskState: TaskState = TaskState.Idle
    private var summary: AssetSummary? = null
    private var total = 0
    private var totalBytes = 0L
    private var downloadAction: () -> Unit = {}
    private var checkAction: () -> Unit = {}

    private val listener: (TaskState) -> Unit = { state ->
        val previous = taskState
        taskState = state
        render()
        // 任务刚结束：文件数量变了，重新统计。
        if (state is TaskState.Finished && previous is TaskState.Running) refreshSummary()
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
            orientation = LinearLayout.HORIZONTAL
            addView(downloadButton, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            for (button in listOf(importButton, checkButton, exportButton)) {
                addView(button, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = activity.dp(8) })
            }
        }
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, activity.dp(36)).apply { topMargin = activity.dp(8) })

        AssetTasks.addListener(listener)
        refreshSummary()
        return root
    }

    fun dispose() {
        disposed = true
        AssetTasks.removeListener(listener)
    }

    // ---- 导出 ----

    private fun exportAction() {
        val what = when (store.origin()) {
            AssetOrigin.IMPORT -> "已导入资源包「${store.importInfo()?.name ?: "资源包"}」中的全部 $total 个文件"
            else -> "已下载的全部 $total 个文件（约 ${formatMb(summary?.bytes ?: 0L)}）"
        }
        confirm(
            "导出资源包",
            "将把$what 打包为 zip，保存到手机的「下载」目录。导出的文件可以通过「导入资源包」在本机或其他设备上使用。",
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
                    ImportOutcome.Ready(ImportInfo(uri, name, size, count), store.summary(index), index.files.size)
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
                    store.origin() == AssetOrigin.DOWNLOAD && outcome.summary.present > 0 -> confirm(
                        "导入资源包",
                        "资源包「${info.name}」包含 $files 个文件。导入后将删除已下载的资源（${formatMb(outcome.summary.bytes)}），改为直接读取该资源包。",
                        "导入", apply, releaseIfUnused,
                    )
                    store.origin() == AssetOrigin.IMPORT && current != null && current.uri != uri -> confirm(
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
            state is TaskState.Running -> renderRunning(state)
            importing -> {
                setEnabled(downloadButton, false)
                setEnabled(importButton, false)
                setEnabled(checkButton, false)
                setEnabled(exportButton, false)
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

        val origin = store.origin()
        val importInfo = store.importInfo()
        val check = store.lastCheck()
        val complete = origin == AssetOrigin.DOWNLOAD && summary.present == total

        downloadButton.text = when {
            complete -> "重新下载"
            origin == AssetOrigin.DOWNLOAD && summary.present > 0 -> "继续下载"
            else -> "下载"
        }
        downloadAction = when {
            origin == AssetOrigin.IMPORT -> ({
                confirm(
                    "改用在线下载",
                    "将停止使用已导入的资源包「${importInfo?.name ?: "资源包"}」，改为从原项目地址下载约 ${formatMb(totalBytes)}。资源包文件本身不会被删除。",
                    "下载", { startDownload(force = false) },
                )
            })
            complete -> ({
                confirm(
                    "重新下载全部资源",
                    "将从原项目地址重新下载全部 $total 个文件（约 ${formatMb(totalBytes)}），并覆盖已下载的文件。",
                    "重新下载", { startDownload(force = true) },
                )
            })
            // 首次下载和继续下载只补缺失的文件，不覆盖已有资源。
            else -> ({ startDownload(force = false) })
        }
        setEnabled(downloadButton, true)

        val changed = check?.changed.orEmpty()
        if (origin == AssetOrigin.DOWNLOAD && changed.isNotEmpty()) {
            checkButton.text = "更新 ${changed.size} 个"
            checkAction = {
                confirm(
                    "更新资源",
                    "将重新下载 ${changed.size} 个有更新的文件，并覆盖本地的旧版本。",
                    "更新", { if (!AssetTasks.startUpdate(activity, changed)) toast("已有任务在进行") },
                )
            }
        } else {
            checkButton.text = "检查更新"
            checkAction = { if (!AssetTasks.startCheck(activity)) toast("已有任务在进行") }
        }
        setEnabled(checkButton, origin == AssetOrigin.IMPORT || (origin == AssetOrigin.DOWNLOAD && summary.present > 0))
        // 只导出完整的资源：在线下载已全部完成，或导入的资源包包含全部文件（导入包之后可能被用户删掉，导出一份可留作备份）。
        setEnabled(exportButton, complete || (origin == AssetOrigin.IMPORT && importInfo?.count == total))

        val firstLine = when (origin) {
            null -> "未安装 · 本地模式会显示占位画面"
            AssetOrigin.DOWNLOAD -> "已下载 ${summary.present}/$total 个文件 · ${formatMb(summary.bytes)}"
            AssetOrigin.IMPORT -> "已导入 ${importInfo?.name ?: "资源包"} · ${importInfo?.count ?: 0}/$total 个文件"
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
                if (origin == AssetOrigin.IMPORT && check.changed.isNotEmpty()) text.append("；资源包无法局部更新，可改用下载")
            }
        }
        statusText.text = text
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
