package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.anthropic.client.AnthropicClient
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Anki 위에 떠 있는 작은 질문 카드. 다른 화면으로 넘어가지 않고 그 자리에서 답을 본다.
 * - 고른 영역 바로 아래(공간이 없으면 위/옆)에 뜬다. 윗부분을 잡고 끌어서 옮길 수 있다.
 * - 버튼을 누르거나 직접 질문을 보내기 전에는 아무것도 전송하지 않는다 (설정에서 자동 요청 가능).
 * - 카드 바깥 터치는 그대로 아래 앱(Anki)으로 전달된다.
 */
@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class FloatingPanel(
    private val ctx: Context,
    private val preview: Bitmap,
    private val imageBytes: () -> ByteArray,
    private val selection: Rect,
    private val clientProvider: () -> AnthropicClient?,
    private val onOpenSettings: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val c = Palette.of(ctx)

    private val screen: Rect = wm.maximumWindowMetrics.bounds
    private val margin = dp(12)
    private val shadow = dp(14) // 그림자가 잘리지 않게 카드 바깥에 두는 여백

    private lateinit var window: KeyCatchingFrame
    private lateinit var card: LinearLayout
    private lateinit var caption: TextView
    private lateinit var answerScroll: ScrollView
    private lateinit var answer: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private val chips = mutableListOf<TextView>()
    private val log = SpannableStringBuilder()

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        windowAnimations = android.R.style.Animation_Toast
    }

    private enum class Anchor { BELOW, ABOVE, FREE }

    private var anchor = Anchor.FREE
    private var tutor: ClaudeTutor? = null
    private var busy = false
    private var closed = false
    private var imeMode = false
    private var yBeforeIme: Int? = null

    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    // ------------------------------------------------------------------ 표시 / 닫기

    fun show() {
        buildViews()
        placeInitially()
        wm.addView(window, lp)
        window.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) keepOnScreen()
        }
        if (prefs.autoAsk) send(ClaudeTutor.Q_GRAMMAR, "해석·문법")
    }

    fun close() {
        if (closed) return
        closed = true
        tutor?.cancel()
        hideIme()
        try {
            wm.removeViewImmediate(window)
        } catch (_: Exception) {
        }
        onClosed()
    }

    // ------------------------------------------------------------------ 화면 구성

    private fun buildViews() {
        val panelWidth = min(dp(460), screen.width() - margin * 2)

        window = KeyCatchingFrame(ctx).apply {
            setPadding(shadow, shadow, shadow, shadow)
            onBack = { if (imeMode) setImeMode(false) else close() }
        }

        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(c.card)
                setStroke(max(1, dp(1) / 2), c.stroke)
            }
            elevation = dpf(10f)
        }
        window.addView(card, FrameLayout.LayoutParams(panelWidth - shadow * 2, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 끌기 손잡이 + 머리줄
        val grip = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpf(2f)
                setColor(c.stroke)
            }
        }
        card.addView(grip, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val thumb = ImageView(ctx).apply {
            setImageBitmap(preview)
            adjustViewBounds = true
            maxHeight = dp(40)
            maxWidth = dp(150)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(c.soft)
            }
            clipToOutline = true
        }
        header.addView(thumb, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
        ))
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(pill(modelLabel()))
        header.addView(iconButton("⚙") {
            close()
            onOpenSettings()
        })
        header.addView(iconButton("✕") { close() })
        card.addView(header)

        // 머리줄과 손잡이를 잡고 끌면 카드가 움직인다
        val dragger = DragListener()
        grip.setOnTouchListener(dragger)
        header.setOnTouchListener(dragger)
        thumb.setOnTouchListener(dragger)

        // 빠른 질문 버튼
        val chipRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "해석·문법" to ClaudeTutor.Q_GRAMMAR,
            "한자 예시" to ClaudeTutor.Q_KANJI,
            "단어 뜻" to ClaudeTutor.Q_WORDS,
        ).forEachIndexed { i, (label, q) ->
            val chip = chip(label, primary = i == 0) { send(q, label) }
            chips += chip
            chipRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        card.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        caption = TextView(ctx).apply {
            text = "아직 전송 전이에요. 버튼을 누르거나 아래에 직접 물어보세요."
            textSize = 12.5f
            setTextColor(c.muted)
        }
        card.addView(caption, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // 답변
        answer = TextView(ctx).apply {
            textSize = 15.5f
            setTextColor(c.text)
            setLineSpacing(0f, 1.38f)
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        }
        answerScroll = MaxHeightScrollView(ctx, min(dp(440), (screen.height() * 0.5f).roundToInt())).apply {
            addView(answer)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
        }
        card.addView(answerScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        statusRow.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(c.accent)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        statusText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(c.muted)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(statusText)
        card.addView(statusRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        // 직접 질문 입력줄
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(c.soft)
            }
        }
        input = EditText(ctx).apply {
            hint = "직접 물어보기"
            textSize = 15f
            setTextColor(c.text)
            setHintTextColor(c.muted)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, dp(10), 0, dp(10))
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP && !imeMode) setImeMode(true)
                false
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTyped(); true
                } else false
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = TextView(ctx).apply {
            text = "↑"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = RippleDrawable(
                ColorStateList.valueOf(c.ripple),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c.accent)
                },
                null,
            )
            setOnClickListener { sendTyped() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        card.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })
    }

    private fun modelLabel() =
        if (prefs.model == ClaudeTutor.MODEL_SONNET) "Sonnet 5.5" else "Haiku 5.5"

    private fun pill(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(c.muted)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dpf(10f)
            setColor(c.soft)
        }
    }

    private fun iconButton(glyph: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = glyph
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(c.muted)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        background = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) c.onAccentSoft else c.text)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = RippleDrawable(
            ColorStateList.valueOf(c.ripple),
            GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(if (primary) c.accentSoft else c.soft)
            },
            null,
        )
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ 위치

    /** 고른 영역 아래 → 위 → 오른쪽 → 왼쪽 순으로 공간이 있는 곳에 둔다. */
    private fun placeInitially() {
        val w = min(dp(460), screen.width() - margin * 2)
        val need = dp(240)
        val gap = dp(6)
        val spaceBelow = screen.bottom - selection.bottom
        val spaceAbove = selection.top - screen.top
        val spaceRight = screen.right - selection.right
        val spaceLeft = selection.left - screen.left
        val alignedX = (selection.left - shadow).coerceIn(margin, screen.right - w - margin)
        when {
            spaceBelow >= need -> {
                anchor = Anchor.BELOW
                lp.x = alignedX
                lp.y = selection.bottom + gap - shadow
            }
            spaceAbove >= need -> {
                anchor = Anchor.ABOVE
                lp.x = alignedX
                lp.y = max(margin, selection.top - gap - dp(200))
            }
            spaceRight >= w -> {
                anchor = Anchor.FREE
                lp.x = selection.right + gap - shadow
                lp.y = max(margin, selection.top - shadow)
            }
            spaceLeft >= w -> {
                anchor = Anchor.FREE
                lp.x = selection.left - gap - w + shadow
                lp.y = max(margin, selection.top - shadow)
            }
            else -> {
                anchor = Anchor.FREE
                lp.x = screen.right - w - margin
                lp.y = screen.bottom - dp(320)
            }
        }
    }

    /** 답변이 길어져 카드가 커져도 화면 밖으로 나가지 않게, 위에 붙은 카드는 아래쪽 끝을 고정한다. */
    private fun keepOnScreen() {
        if (closed || !window.isAttachedToWindow) return
        val h = window.height
        var y = lp.y
        if (anchor == Anchor.ABOVE) y = selection.top - dp(6) - h + shadow
        y = y.coerceAtMost(screen.bottom - margin - h)
        y = y.coerceAtLeast(margin - shadow)
        if (y != lp.y) {
            lp.y = y
            wm.updateViewLayout(window, lp)
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (dx * dx + dy * dy) > dpf(6f) * dpf(6f)) dragging = true
                    if (dragging) {
                        anchor = Anchor.FREE
                        yBeforeIme = null
                        lp.x = (startX + dx).roundToInt()
                            .coerceIn(-shadow, screen.right - window.width + shadow)
                        lp.y = (startY + dy).roundToInt()
                            .coerceIn(-shadow, screen.bottom - window.height + shadow)
                        wm.updateViewLayout(window, lp)
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ 키보드

    private fun setImeMode(on: Boolean) {
        if (closed || imeMode == on) return
        imeMode = on
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (on) {
            // 키보드가 카드를 가리지 않게 화면 위쪽 절반 안으로 잠시 올린다
            val limit = (screen.height() * 0.45f).roundToInt() - window.height
            if (lp.y > limit) {
                yBeforeIme = lp.y
                lp.y = max(margin - shadow, limit)
            }
        } else {
            yBeforeIme?.let { lp.y = it }
            yBeforeIme = null
        }
        wm.updateViewLayout(window, lp)
        if (on) {
            input.requestFocus()
            main.postDelayed({
                ctx.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        } else {
            hideIme()
        }
    }

    private fun hideIme() {
        if (!::input.isInitialized) return
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    // ------------------------------------------------------------------ 질문 보내기

    private fun sendTyped() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        setImeMode(false)
        send(q, q)
    }

    private fun send(question: String, label: String) {
        if (busy || closed) return
        val client = clientProvider()
        if (client == null) {
            caption.text = "API 키가 없어요. ⚙ 설정에서 키를 넣어 주세요."
            caption.setTextColor(c.error)
            return
        }
        val t = tutor ?: ClaudeTutor(client, prefs.model, prefs.systemPrompt).also { tutor = it }

        caption.visibility = View.GONE
        answerScroll.visibility = View.VISIBLE
        if (log.isNotEmpty()) log.append("\n\n")
        val qStart = log.length
        log.append(if (label.length > 60) label.take(60) + "…" else label)
        log.append("\n")
        log.setSpan(ForegroundColorSpan(c.accent), qStart, log.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        log.setSpan(StyleSpan(Typeface.BOLD), qStart, log.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        log.setSpan(RelativeSizeSpan(0.85f), qStart, log.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        answer.text = log
        if (qStart > 0) {
            answer.post {
                val layout = answer.layout ?: return@post
                answerScroll.smoothScrollTo(0, layout.getLineTop(layout.getLineForOffset(qStart)))
            }
        }

        setBusy(true)
        statusText.text = "읽는 중…"
        statusRow.visibility = View.VISIBLE

        thread(name = "claude-ask") {
            var first = true
            try {
                t.ask(imageBytes(), "image/png", question) { delta ->
                    main.post {
                        if (closed) return@post
                        if (first) {
                            first = false
                            statusRow.visibility = View.GONE
                        }
                        log.append(delta)
                        answer.text = log
                    }
                }
                main.post {
                    if (closed) return@post
                    statusRow.visibility = View.GONE
                    setBusy(false)
                }
            } catch (e: ClaudeTutor.TutorException) {
                main.post { showError(e.message ?: "오류가 발생했어요.") }
            } catch (e: Exception) {
                main.post { showError("오류: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            }
        }
    }

    private fun showError(msg: String) {
        if (closed) return
        statusRow.visibility = View.GONE
        val start = log.length
        log.append("\n").append(msg)
        log.setSpan(ForegroundColorSpan(c.error), start, log.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        answer.text = log
        setBusy(false)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        chips.forEach {
            it.isEnabled = !b
            it.alpha = if (b) 0.45f else 1f
        }
        sendBtn.isEnabled = !b
        sendBtn.alpha = if (b) 0.45f else 1f
    }

    // ------------------------------------------------------------------ 보조 클래스

    /** 뒤로 가기 키를 받아 키보드 닫기 / 카드 닫기에 쓴다 (창이 포커스를 가질 때만 온다). */
    private class KeyCatchingFrame(ctx: Context) : FrameLayout(ctx) {
        var onBack: (() -> Unit)? = null
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** 내용만큼 늘어나다가 maxHeight에서 스크롤로 바뀌는 ScrollView. */
    private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            )
        }
    }

    /** 라이트/다크 모드 색. */
    private class Palette(
        val card: Int, val stroke: Int, val text: Int, val muted: Int, val soft: Int,
        val accent: Int, val accentSoft: Int, val onAccentSoft: Int, val ripple: Int, val error: Int,
    ) {
        companion object {
            fun of(ctx: Context): Palette {
                val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (night) Palette(
                    card = 0xFF1F2026.toInt(), stroke = 0xFF34353D.toInt(),
                    text = 0xFFEDEDF2.toInt(), muted = 0xFF9EA0AA.toInt(), soft = 0xFF2B2C34.toInt(),
                    accent = 0xFF8E98FF.toInt(), accentSoft = 0xFF2E3354.toInt(),
                    onAccentSoft = 0xFFC9CDFF.toInt(), ripple = 0x33FFFFFF, error = 0xFFFF8A65.toInt(),
                ) else Palette(
                    card = 0xFFFFFFFF.toInt(), stroke = 0xFFE4E4EA.toInt(),
                    text = 0xFF1D1D22.toInt(), muted = 0xFF74747D.toInt(), soft = 0xFFF2F2F6.toInt(),
                    accent = 0xFF4F5BD5.toInt(), accentSoft = 0xFFE8EAFC.toInt(),
                    onAccentSoft = 0xFF3340B8.toInt(), ripple = 0x22000000, error = 0xFFD84315.toInt(),
                )
            }
        }
    }
}
