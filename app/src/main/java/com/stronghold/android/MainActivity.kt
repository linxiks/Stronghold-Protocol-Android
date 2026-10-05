package com.stronghold.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    companion object {
        private const val TAG = "Stronghold"

        private const val LOCAL_SERVER_URL =
            "http://127.0.0.1:3000"

        private const val PREFS_NAME =
            "stronghold_connection"

        private const val PREF_EXTERNAL_URL =
            "external_url"
    }

    private lateinit var webView: WebView

    private var currentServerUrl =
        LOCAL_SERVER_URL

    private var localMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(true)

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.P
        ) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }

        configureFullscreen()

        showConnectionPage()
    }

    /*
     * ---------------------------------------------------------
     * Connection entry page
     * ---------------------------------------------------------
     */

    private fun showConnectionPage() {
        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                MODE_PRIVATE
            )

        val savedExternalUrl =
            prefs.getString(
                PREF_EXTERNAL_URL,
                ""
            ).orEmpty()

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(32),
                    dp(24),
                    dp(32),
                    dp(24)
                )

                setBackgroundColor(
                    Color.rgb(
                        13,
                        15,
                        18
                    )
                )
            }

        val container =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER_HORIZONTAL

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(420),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            }

        val title =
            TextView(this).apply {
                text =
                    "STRONGHOLD PROTOCOL"

                textSize = 24f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER
            }

        val subtitle =
            TextView(this).apply {
                text =
                    "选择接入方式"

                textSize = 16f

                setTextColor(
                    Color.rgb(
                        180,
                        190,
                        195
                    )
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(8),
                    0,
                    dp(28)
                )
            }

        val localButton =
            Button(this).apply {
                text =
                    "本地运行"

                isAllCaps =
                    false

                setOnClickListener {
                    startLocalMode()
                }
            }

        val localDescription =
            TextView(this).apply {
                text =
                    "使用 APK 内置 Stronghold 服务"

                textSize = 12f

                setTextColor(
                    Color.rgb(
                        130,
                        140,
                        145
                    )
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(22)
                )
            }

        val separator =
            TextView(this).apply {
                text =
                    "或连接外部服务器"

                textSize = 13f

                setTextColor(
                    Color.rgb(
                        160,
                        170,
                        175
                    )
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(12)
                )
            }

        val addressInput =
            EditText(this).apply {
                hint =
                    "192.168.1.100:3000"

                setText(
                    savedExternalUrl
                )

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    Color.rgb(
                        100,
                        110,
                        115
                    )
                )

                textSize = 15f

                setSingleLine(true)

                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI

                setPadding(
                    dp(14),
                    0,
                    dp(14),
                    0
                )

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(50)
                    )
            }

        val externalButton =
            Button(this).apply {
                text =
                    "连接外部服务器"

                isAllCaps =
                    false

                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(52)
                    ).apply {
                        topMargin =
                            dp(12)
                    }

                setOnClickListener {
                    val input =
                        addressInput.text
                            .toString()
                            .trim()

                    if (input.isBlank()) {
                        Toast.makeText(
                            this@MainActivity,
                            "请输入服务器地址",
                            Toast.LENGTH_SHORT
                        ).show()

                        return@setOnClickListener
                    }

                    val normalizedUrl =
                        normalizeServerUrl(
                            input
                        )

                    prefs.edit()
                        .putString(
                            PREF_EXTERNAL_URL,
                            input
                        )
                        .apply()

                    startExternalMode(
                        normalizedUrl
                    )
                }
            }

        container.addView(
            title
        )

        container.addView(
            subtitle
        )

        container.addView(
            localButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        container.addView(
            localDescription
        )

        container.addView(
            separator
        )

        container.addView(
            addressInput
        )

        container.addView(
            externalButton
        )

        root.addView(
            container
        )

        setContentView(
            root
        )
    }

    /*
     * ---------------------------------------------------------
     * Local / external modes
     * ---------------------------------------------------------
     */

    private fun startLocalMode() {
        localMode = true

        currentServerUrl =
            LOCAL_SERVER_URL

        createWebView()

        showLoadingPage(
            "Preparing Stronghold..."
        )

        Thread {
            try {
                val root =
                    StrongholdInstaller.install(
                        this
                    )

                Log.i(
                    TAG,
                    "Runtime ready: ${root.absolutePath}"
                )

                NodeManager.startStronghold(
                    root
                )

                waitForServer(
                    currentServerUrl,
                    60_000
                )

                runOnUiThread {
                    webView.loadUrl(
                        currentServerUrl
                    )
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Stronghold startup failed",
                    t
                )

                showError(
                    "Stronghold startup failed",
                    t.stackTraceToString()
                )
            }
        }.apply {
            name =
                "Stronghold-Bootstrap"

            start()
        }
    }

    private fun startExternalMode(
        serverUrl: String
    ) {
        localMode = false

        currentServerUrl =
            serverUrl

        createWebView()

        showLoadingPage(
            "Connecting to $serverUrl..."
        )

        Thread {
            try {
                /*
                 * External mode:
                 *
                 * Do NOT extract the embedded runtime.
                 * Do NOT start the embedded Node server.
                 */
                waitForServer(
                    serverUrl,
                    15_000
                )

                runOnUiThread {
                    webView.loadUrl(
                        serverUrl
                    )
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "External server connection failed",
                    t
                )

                showError(
                    "Unable to connect",
                    """
                    Server:
                    $serverUrl

                    ${t.message ?: "Unknown error"}
                    """.trimIndent()
                )
            }
        }.apply {
            name =
                "Stronghold-External"

            start()
        }
    }

    /*
     * ---------------------------------------------------------
     * WebView
     * ---------------------------------------------------------
     */

    private fun createWebView() {
        webView =
            WebView(this)

        configureWebView()

        setContentView(
            webView
        )

        webView.post {
            val metrics =
                resources.displayMetrics

            Log.i(
                TAG,
                "DISPLAY=${metrics.widthPixels}x${metrics.heightPixels}"
            )

            Log.i(
                TAG,
                "WEBVIEW=${webView.width}x${webView.height}"
            )

            val location =
                IntArray(2)

            webView.getLocationOnScreen(
                location
            )

            Log.i(
                TAG,
                "WEBVIEW location=${location.contentToString()}"
            )
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true

            domStorageEnabled = true

            cacheMode =
                WebSettings.LOAD_DEFAULT

            allowFileAccess = true

            allowContentAccess = true

            mediaPlaybackRequiresUserGesture =
                false

            builtInZoomControls =
                false

            displayZoomControls =
                false
        }

        webView.webViewClient =
            object : WebViewClient() {

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    super.onPageFinished(
                        view,
                        url
                    )

                    Log.i(
                        TAG,
                        "WebView loaded: $url"
                    )

                    /*
                     * Android-specific Stronghold layout fix.
                     *
                     * The upstream web UI reserves 44 CSS px
                     * on the left side of .screen.
                     *
                     * Do not modify the upstream project.
                     */
                    if (
                        url?.startsWith("http://") == true ||
                        url?.startsWith("https://") == true
                    ) {
                        installAndroidLayoutFix(
                            view
                        )
                    }
                }
            }
    }

    private fun installAndroidLayoutFix(
        view: WebView?
    ) {
        view?.evaluateJavascript(
            """
            (() => {
                const STYLE_ID =
                    'stronghold-android-layout-fix';

                // 1. Persistent CSS override
                let style =
                    document.getElementById(STYLE_ID);

                if (!style) {
                    style =
                        document.createElement('style');

                    style.id =
                        STYLE_ID;

                    style.textContent = `
                        .screen {
                            left: 0 !important;
                        }
                    `;

                    (
                        document.head ||
                        document.documentElement
                    ).appendChild(style);
                }

                // 2. Also force existing screens directly
                const fixScreens = () => {
                    document
                        .querySelectorAll('.screen')
                        .forEach(screen => {
                            screen.style.setProperty(
                                'left',
                                '0px',
                                'important'
                            );
                        });
                };

                fixScreens();

                // 3. Watch later SPA rendering / attribute changes
                if (
                    !window.__strongholdAndroidObserver
                ) {
                    const observer =
                        new MutationObserver(() => {
                            fixScreens();
                        });

                    observer.observe(
                        document.documentElement,
                        {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: [
                                'class',
                                'style'
                            ]
                        }
                    );

                    window.__strongholdAndroidObserver =
                        observer;
                }

                return {
                    installed: true,
                    url: location.href,
                    screens:
                        [...document.querySelectorAll('.screen')]
                            .map(x => ({
                                className: x.className,
                                left:
                                    x.getBoundingClientRect().left,
                                width:
                                    x.getBoundingClientRect().width
                            }))
                };
            })();
            """.trimIndent()
        ) { result ->
            Log.i(
                TAG,
                "Android layout fix: $result"
            )
        }
    }

    /*
     * ---------------------------------------------------------
     * Server
     * ---------------------------------------------------------
     */

    private fun waitForServer(
        serverUrl: String,
        timeoutMs: Long
    ) {
        val start =
            System.currentTimeMillis()

        while (
            System.currentTimeMillis() -
                start <
                timeoutMs
        ) {
            if (
                isServerReady(
                    serverUrl
                )
            ) {
                Log.i(
                    TAG,
                    "Stronghold server is ready: $serverUrl"
                )

                return
            }

            Thread.sleep(
                250
            )
        }

        throw IllegalStateException(
            "Server did not become ready: $serverUrl"
        )
    }

    private fun isServerReady(
        serverUrl: String
    ): Boolean {
        /*
         * Use "/" rather than relying on /healthz.
         *
         * This also makes external Stronghold servers
         * easier to connect to.
         */
        var connection:
            HttpURLConnection? =
            null

        return try {
            connection =
                URL(
                    "${
                        serverUrl.trimEnd('/')
                    }/"
                ).openConnection()
                    as HttpURLConnection

            connection.connectTimeout =
                750

            connection.readTimeout =
                750

            connection.requestMethod =
                "GET"

            connection.useCaches =
                false

            connection.instanceFollowRedirects =
                true

            connection.responseCode in
                200..399
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun normalizeServerUrl(
        input: String
    ): String {
        var value =
            input.trim()
                .trimEnd('/')

        if (
            !value.startsWith(
                "http://",
                ignoreCase = true
            ) &&
            !value.startsWith(
                "https://",
                ignoreCase = true
            )
        ) {
            value =
                "http://$value"
        }

        return value
    }

    /*
     * ---------------------------------------------------------
     * Loading / error pages
     * ---------------------------------------------------------
     */

    private fun showLoadingPage(
        message: String
    ) {
        runOnUiThread {
            webView.loadData(
                """
                <!doctype html>
                <html>
                <head>
                    <meta
                        name="viewport"
                        content="width=device-width,initial-scale=1"
                    >
                </head>

                <body style="
                    margin:0;
                    background:#0d0f12;
                    color:#e8e8e8;
                    font-family:sans-serif;
                    display:flex;
                    align-items:center;
                    justify-content:center;
                    height:100vh;
                ">
                    <div style="text-align:center">
                        <h2>
                            Stronghold Protocol
                        </h2>

                        <p>
                            ${escapeHtml(message)}
                        </p>
                    </div>
                </body>
                </html>
                """.trimIndent(),
                "text/html",
                "UTF-8"
            )
        }
    }

    private fun showError(
        title: String,
        message: String
    ) {
        runOnUiThread {
            webView.loadData(
                """
                <!doctype html>
                <html>
                <head>
                    <meta
                        name="viewport"
                        content="width=device-width,initial-scale=1"
                    >
                </head>

                <body style="
                    margin:0;
                    background:#111;
                    color:#eee;
                    font-family:monospace;
                    padding:20px;
                    box-sizing:border-box;
                ">
                    <h2>
                        ${escapeHtml(title)}
                    </h2>

                    <pre style="
                        white-space:pre-wrap;
                        word-break:break-word;
                    ">${
                        escapeHtml(
                            message
                        )
                    }</pre>
                </body>
                </html>
                """.trimIndent(),
                "text/html",
                "UTF-8"
            )
        }
    }

    private fun escapeHtml(
        value: String
    ): String {
        return value
            .replace(
                "&",
                "&amp;"
            )
            .replace(
                "<",
                "&lt;"
            )
            .replace(
                ">",
                "&gt;"
            )
    }

    /*
     * ---------------------------------------------------------
     * Fullscreen
     * ---------------------------------------------------------
     */

    private fun configureFullscreen() {
        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.P
        ) {
            window.attributes =
                window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {
        super.onWindowFocusChanged(
            hasFocus
        )

        if (hasFocus) {
            configureFullscreen()
        }
    }

    /*
     * ---------------------------------------------------------
     * Navigation
     * ---------------------------------------------------------
     */

    @Deprecated(
        "Deprecated in Android API"
    )
    override fun onBackPressed() {
        if (
            ::webView.isInitialized
        ) {
            if (
                webView.canGoBack()
            ) {
                webView.goBack()
            } else {
                webView.stopLoading()
                webView.destroy()

                showConnectionPage()
            }

            return
        }

        super.onBackPressed()
    }

    override fun onDestroy() {
        if (
            ::webView.isInitialized
        ) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }

    private fun dp(
        value: Int
    ): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}