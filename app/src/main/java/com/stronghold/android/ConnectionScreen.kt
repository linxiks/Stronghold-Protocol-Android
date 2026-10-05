package com.stronghold.android

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Toast

/**
 * 原生连接页：左栏标题、本地运行与游戏资源，右栏外部服务器地址与连接历史。
 * 历史只由调用方在连接成功后写入；这里只负责展示、删除、清空。
 */
internal class ConnectionScreen(
    private val activity: Activity,
    private val history: ConnectionHistory,
    assets: AssetStore,
    private val onLocal: () -> Unit,
    private val onExternal: (serverUrl: String) -> Unit,
    onPickArchive: () -> Unit,
) {

    private val assetPanel = AssetPanel(activity, assets, onPickArchive)

    private var expanded = false
    private var clearArmed = false
    private lateinit var historyList: LinearLayout
    private lateinit var clearButton: Button
    private lateinit var addressInput: EditText

    private val disarm = Runnable {
        clearArmed = false
        renderClearButton()
    }

    fun createView(): View {
        val root = FrameLayout(activity).apply {
            background = TacticalBackgroundDrawable(activity.dpf(32f), 1f)
        }

        val corner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(activity.microText("RHODES ISLAND // SIMULATION SERVICE", Palette.MINT_700))
            addView(activity.microText("TACTICAL CO-OP NODE · 02", Palette.TEXT_DIM))
        }
        root.addView(
            corner,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(40)
                topMargin = dp(12)
            }
        )

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(28))
            addView(leftColumn(), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(
                remotePanel(),
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { leftMargin = dp(32) }
            )
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        renderHistory()
        return root
    }

    fun onArchivePicked(uri: Uri) = assetPanel.onArchivePicked(uri)

    fun onExportPermissionResult(granted: Boolean) = assetPanel.onExportPermissionResult(granted)

    fun dispose() = assetPanel.dispose()

    // ---- 左栏 ----

    private fun leftColumn() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL

        addView(activity.styledText("STRONGHOLD PROTOCOL", 13f, Palette.TITLE_EN).apply {
            typeface = StrongholdFonts.display(activity)
            letterSpacing = 0.3f
        })
        addView(activity.styledText("ALLIANCE", 15f, Palette.MINT_500).apply {
            typeface = StrongholdFonts.display(activity)
            letterSpacing = 0.34f
            setShadowLayer(activity.dpf(7f), 0f, 0f, 0x7317F9B7)
        }, topMargin(2))

        val title = SpannableString("卫戍协议：盟约").apply {
            val start = indexOf("盟约")
            setSpan(ForegroundColorSpan(Palette.MINT_500), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        addView(activity.styledText("", 40f, Palette.TITLE_CN).apply {
            text = title
            typeface = heavyTypeface()
            letterSpacing = 0.06f
            setShadowLayer(activity.dpf(8f), 0f, activity.dpf(2f), 0xB3000000.toInt())
        }, topMargin(6))

        addView(activity.styledText("选择接入方式", 13f, Palette.TEXT_LO).apply { letterSpacing = 0.1f }, topMargin(4))

        val localButton = Button(activity).apply {
            background = activity.primaryButtonBackground()
            flat()
            text = "本地运行"
            textSize = 16f
            setTextColor(Palette.TEXT_ON_MINT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.24f
            setOnClickListener { onLocal() }
        }
        addView(localButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })

        addView(activity.styledText("使用 APK 内置 Stronghold 服务", 12f, Palette.TEXT_DIM), topMargin(8))

        addView(
            assetPanel.createView(),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) },
        )
    }

    // ---- 右栏 ----

    private fun remotePanel() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = activity.panelBackground()
        setPadding(dp(18), dp(16), dp(18), dp(12))

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.styledText("外部服务器", 15f, Palette.TEXT_HI).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(activity.microText("REMOTE SERVER", Palette.TEXT_DIM), startMargin(8))
        }
        addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(24)))

        addView(inputRow(), LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })

        val divider = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x003E4B45, Palette.LINE_2, Palette.LINE_2, 0x003E4B45),
            )
        }
        addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { topMargin = dp(16) })

        clearButton = Button(activity).apply {
            background = ghostBackground()
            flat()
            setPadding(dp(10), 0, dp(10), 0)
            textSize = 12f
            setOnClickListener { onClearClicked() }
        }
        renderClearButton()

        val historyHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(activity.styledText("最近连接", 13f, Palette.TEXT_MD).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(activity.microText("HISTORY", Palette.TEXT_DIM), startMargin(8))
            addView(Space(activity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(clearButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(32)))
        }
        addView(historyHeader, LinearLayout.LayoutParams(MATCH_PARENT, dp(32)).apply { topMargin = dp(8) })

        historyList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(historyList, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun inputRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL

        val frame = GradientDrawable().apply {
            setColor(Palette.FIELD_BG)
            setStroke(dp(1), Palette.LINE)
        }
        val brackets = BracketsDrawable(Palette.LINE_3, activity.dpf(8f), activity.dpf(2f))

        addressInput = EditText(activity).apply {
            background = null
            setPadding(dp(12), 0, dp(12), 0)
            typeface = StrongholdFonts.number(activity)
            textSize = 15f
            setTextColor(Palette.TEXT_HI)
            hint = "192.168.1.100:3000"
            setHintTextColor(Palette.TEXT_DIM)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            setText(history.entries().firstOrNull()?.let { displayServerUrl(it.url) }.orEmpty())
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    connectFromInput()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                frame.setStroke(dp(1), if (hasFocus) Palette.MINT_700 else Palette.LINE)
                brackets.color = if (hasFocus) Palette.MINT_400 else Palette.LINE_3
            }
        }

        val field = FrameLayout(activity).apply {
            background = LayerDrawable(arrayOf(frame, brackets))
            addView(addressInput, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        addView(field, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))

        val connectButton = Button(activity).apply {
            background = activity.secondaryButtonBackground()
            flat()
            text = "连接"
            textSize = 14f
            setTextColor(Palette.TEXT_HI)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            setOnClickListener { connectFromInput() }
        }
        addView(connectButton, LinearLayout.LayoutParams(dp(96), MATCH_PARENT).apply { leftMargin = dp(10) })
    }

    // ---- 行为 ----

    /** 只发起连接；历史由调用方在连接成功后写入。 */
    private fun connectFromInput() {
        val input = addressInput.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(activity, "请输入服务器地址", Toast.LENGTH_SHORT).show()
            return
        }
        onExternal(normalizeServerUrl(input))
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val records = history.entries()

        if (records.isEmpty()) {
            historyList.addView(activity.styledText("暂无连接记录", 12f, Palette.TEXT_DIM).apply {
                setPadding(0, dp(12), 0, dp(12))
            })
            clearButton.removeCallbacks(disarm)
            clearArmed = false
            renderClearButton()
            clearButton.visibility = View.GONE
            return
        }

        clearButton.visibility = View.VISIBLE
        val visible = if (expanded || records.size <= HISTORY_COLLAPSED_COUNT) {
            records
        } else {
            records.take(HISTORY_COLLAPSED_COUNT)
        }
        visible.forEachIndexed { index, record ->
            historyList.addView(historyRow(record), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            if (index != visible.lastIndex) {
                historyList.addView(
                    View(activity).apply { setBackgroundColor(Palette.LINE) },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(1)),
                )
            }
        }

        if (records.size > HISTORY_COLLAPSED_COUNT) {
            val toggle = Button(activity).apply {
                background = ghostBackground()
                flat()
                text = if (expanded) "收起" else "显示更多（还有 ${records.size - HISTORY_COLLAPSED_COUNT} 条）"
                textSize = 12f
                setTextColor(Palette.MINT_500)
                setOnClickListener {
                    expanded = !expanded
                    renderHistory()
                }
            }
            historyList.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
        }
    }

    private fun historyRow(record: ServerRecord): View {
        val address = displayServerUrl(record.url)
        val relative = DateUtils.getRelativeTimeSpanString(
            record.lastConnectedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ghostBackground()
            isClickable = true
            isFocusable = true
            contentDescription = "连接 $address，$relative"
            setOnClickListener { onExternal(record.url) }

            val marker = View(activity).apply {
                setBackgroundColor(Palette.MINT_700)
                rotation = 45f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(marker, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(4)
                rightMargin = dp(12)
            })

            val labels = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 行本身已带完整的 contentDescription，子文本不再单独朗读。
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(activity.styledText(address, 14f, Palette.TEXT_HI).apply {
                    typeface = StrongholdFonts.number(activity)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(activity.styledText(relative.toString(), 11f, Palette.TEXT_LO))
            }
            addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            val delete = Button(activity).apply {
                background = ghostBackground()
                flat()
                text = "×"
                textSize = 18f
                setTextColor(Palette.TEXT_DIM)
                contentDescription = "删除 $address"
                setOnClickListener {
                    history.remove(record.url)
                    renderHistory()
                }
            }
            addView(delete, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    /** 清空需在 [CLEAR_CONFIRM_MS] 内二次确认，防止误触。 */
    private fun onClearClicked() {
        clearButton.removeCallbacks(disarm)
        if (clearArmed) {
            history.clear()
            clearArmed = false
            expanded = false
            renderHistory()
        } else {
            clearArmed = true
            renderClearButton()
            clearButton.postDelayed(disarm, CLEAR_CONFIRM_MS)
        }
    }

    private fun renderClearButton() {
        clearButton.text = if (clearArmed) "确认清空" else "清空"
        clearButton.setTextColor(if (clearArmed) Palette.DANGER else Palette.TEXT_LO)
    }

    // ---- 小工具 ----

    private fun dp(value: Int) = activity.dp(value)

    private fun topMargin(valueDp: Int) =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(valueDp) }

    private fun startMargin(valueDp: Int) =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(valueDp) }

    private companion object {
        const val HISTORY_COLLAPSED_COUNT = 3
        const val CLEAR_CONFIRM_MS = 3_000L
    }
}
