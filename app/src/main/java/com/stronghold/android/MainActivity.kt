package com.stronghold.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val TAG = "Stronghold"
        private const val LOCAL_SERVER_URL = "http://127.0.0.1:3000"
        private const val EXIT_CONFIRM_WINDOW_MS = 2_000L
        private const val REQUEST_IMPORT_ASSETS = 1001
    }

    /** 当前显示的 WebView；显示连接页时为 null。 */
    private var webView: WebView? = null

    /** API 33+ 的 OnBackInvokedCallback；声明为 Any，避免低版本系统解析该类。 */
    private var backCallback: Any? = null

    /** 上一次在游戏页按返回的时间（uptimeMillis）；0 表示尚未按过。 */
    private var lastBackPressAt = 0L

    private val history by lazy { ConnectionHistory(this) }

    private val assets by lazy { AssetStore(this) }

    /** 当前显示的连接页；显示 WebView 时为 null。 */
    private var connectionScreen: ConnectionScreen? = null

    /** 本地模式下 `/assets/` 的来源；外部模式和连接页为 null（不拦截请求）。WebView 在后台线程读取。 */
    @Volatile
    private var assetSource: AssetSource? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        configureFullscreen()
        showConnectionPage()
    }

    // ---- 连接页 ----

    private fun showConnectionPage() {
        val screen = ConnectionScreen(this, history, assets, ::startLocalMode, ::startExternalMode, ::pickAssetArchive)
        connectionScreen = screen
        setContentView(screen.createView())
        // 先把 WebView 移出视图树再销毁。
        closeWebView()
    }

    @Suppress("DEPRECATION")
    private fun pickAssetArchive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_ASSETS)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_ASSETS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        connectionScreen?.onArchivePicked(uri)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != AssetPanel.REQUEST_EXPORT_PERMISSION) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        connectionScreen?.onExportPermissionResult(granted)
    }

    // ---- 本地 / 外部模式 ----

    private fun startLocalMode() {
        val view = createWebView()
        showLoadingPage(view, "Preparing Stronghold...")
        thread(name = "Stronghold-Bootstrap") {
            try {
                val root = StrongholdInstaller.install(applicationContext)
                Log.i(TAG, "Runtime ready: ${root.absolutePath}")
                NodeManager.startStronghold(root)
                // 先就位资源来源再加载页面：runOnUiThread 按顺序执行，一定早于下面的 loadUrl。
                val source = assets.openSource()
                runOnUiThread { if (webView === view) assetSource = source else source?.close() }
                waitForServer(LOCAL_SERVER_URL, 60_000, NodeManager::failureReason)
                onCurrentWebView(view) { it.loadUrl(LOCAL_SERVER_URL) }
            } catch (t: Throwable) {
                Log.e(TAG, "Stronghold startup failed", t)
                onCurrentWebView(view) { showError(it, "Stronghold startup failed", t.stackTraceToString()) }
            }
        }
    }

    private fun startExternalMode(serverUrl: String) {
        val view = createWebView()
        showLoadingPage(view, "Connecting to $serverUrl...")
        // 外部模式不解压、也不启动内置运行时。
        thread(name = "Stronghold-External") {
            try {
                waitForServer(serverUrl, 15_000)
                // 只记录确实连上的服务器。
                history.record(serverUrl)
                onCurrentWebView(view) { it.loadUrl(serverUrl) }
            } catch (t: Throwable) {
                Log.e(TAG, "External server connection failed", t)
                onCurrentWebView(view) {
                    showError(it, "Unable to connect", "Server:\n$serverUrl\n\n${t.message ?: "Unknown error"}")
                }
            }
        }
    }

    /** 仅当 [view] 仍是当前显示的 WebView 时，才在 UI 线程执行 [action]（期间它可能已被关闭或替换）。 */
    private fun onCurrentWebView(view: WebView, action: (WebView) -> Unit) {
        runOnUiThread { if (webView === view) action(view) }
    }

    // ---- WebView ----

    private fun createWebView(): WebView {
        connectionScreen?.dispose()
        connectionScreen = null
        closeWebView()
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "WebView loaded: $url")
                if (view != null && isServerUrl(url)) installAndroidLayoutFix(view)
            }

            /** 本地模式的 `/assets/<rel>` 直接从下载目录或导入的资源包读取；读不到时交给 Node（返回 404，客户端显示占位）。 */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val source = assetSource ?: return null
                val url = request.url
                if (request.method != "GET" || url.host != "127.0.0.1" || url.port != 3000) return null
                val path = url.path ?: return null
                val rel = path.removePrefix("/assets/").takeIf { it != path && isSafeAssetRel(it) } ?: return null
                val stream = try {
                    source.open(rel)
                } catch (e: IOException) {
                    Log.w(TAG, "Asset read failed: $rel", e)
                    null
                } ?: return null
                return WebResourceResponse(
                    assetMimeType(rel), if (rel.endsWith(".atlas")) "utf-8" else null, 200, "OK",
                    mapOf("Cache-Control" to "no-cache"), stream,
                )
            }
        }
        setContentView(view)
        webView = view
        setBackInterception(true)
        return view
    }

    private fun closeWebView() {
        val view = webView ?: return
        webView = null
        setBackInterception(false)
        view.stopLoading()
        view.destroy()
        assetSource?.close()
        assetSource = null
    }

    /**
     * 上游 public/css/devices.css 中 `.screen:not(.gm) { left: var(--sa-l) }` 在 SHORT_EDGES 刘海模式下
     * 会在左侧留出安全区。这里不改上游代码，只注入一条覆盖规则；SPA 内跳转不会移除该样式。
     */
    private fun installAndroidLayoutFix(view: WebView) {
        view.evaluateJavascript(
            """
            (() => {
                const id = 'stronghold-android-layout-fix';
                if (document.getElementById(id)) return;
                const style = document.createElement('style');
                style.id = id;
                style.textContent = '.screen { left: 0 !important; }';
                (document.head || document.documentElement).appendChild(style);
            })();
            """.trimIndent(),
            null
        )
    }

    private fun isServerUrl(url: String?) =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    // ---- 服务器 ----

    private fun waitForServer(serverUrl: String, timeoutMs: Long, failureReason: () -> String? = { null }) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            failureReason()?.let { throw IllegalStateException(it) }
            if (isServerReady(serverUrl)) {
                Log.i(TAG, "Stronghold server is ready: $serverUrl")
                return
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("Server did not become ready: $serverUrl")
    }

    /** 探测 "/" 而不是 /healthz，方便连接外部 Stronghold 服务器。 */
    private fun isServerReady(serverUrl: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("${serverUrl.trimEnd('/')}/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 750
                readTimeout = 750
                requestMethod = "GET"
                useCaches = false
                instanceFollowRedirects = true
            }
            connection.responseCode in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    // ---- 加载页 / 错误页 ----

    private fun showLoadingPage(view: WebView, message: String) {
        loadHtml(
            view,
            """
            <body style="margin:0;background:#0d0f12;color:#e8e8e8;font-family:sans-serif;
                         display:flex;align-items:center;justify-content:center;height:100vh">
                <div style="text-align:center">
                    <h2>Stronghold Protocol</h2>
                    <p>${escapeHtml(message)}</p>
                </div>
            </body>
            """
        )
    }

    private fun showError(view: WebView, title: String, message: String) {
        loadHtml(
            view,
            """
            <body style="margin:0;background:#111;color:#eee;font-family:monospace;padding:20px;box-sizing:border-box">
                <h2>${escapeHtml(title)}</h2>
                <pre style="white-space:pre-wrap;word-break:break-word">${escapeHtml(message)}</pre>
            </body>
            """
        )
    }

    /** loadDataWithBaseURL 不按 URL 解析内容，`#`、`%` 不会截断页面（loadData 会）。 */
    private fun loadHtml(view: WebView, body: String) {
        val html = """
            <!doctype html>
            <html>
            <head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            ${body.trimIndent()}
            </html>
        """.trimIndent()
        view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun escapeHtml(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- 全屏 ----

    private fun configureFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureFullscreen()
    }

    // ---- 返回键 ----

    /**
     * API 33+：只在显示 WebView 时注册回调；连接页不注册，交给系统默认行为（退出，并保留预测性返回动画）。
     * targetSdk 36 下 onBackPressed 不再被调用，所以必须走这里。
     */
    private fun setBackInterception(enabled: Boolean) {
        lastBackPressAt = 0L
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        (backCallback as OnBackInvokedCallback?)?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        backCallback = null
        if (enabled) {
            val callback = OnBackInvokedCallback { handleBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    /** 游戏页按返回不离开页面：第一次提示，[EXIT_CONFIRM_WINDOW_MS] 内再按一次才退出。 */
    private fun handleBack() {
        val now = SystemClock.uptimeMillis()
        if (lastBackPressAt != 0L && now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            finish()
            return
        }
        lastBackPressAt = now
        Toast.makeText(this, "再按一次返回退出", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 仅 API < 33 会走到这里。
        if (webView != null) handleBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        connectionScreen?.dispose()
        closeWebView()
        super.onDestroy()
    }
}
