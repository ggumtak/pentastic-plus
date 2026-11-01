package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
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
 * - 처음엔 고른 영역 바로 아래(공간이 없으면 위/옆)에 뜬다. 윗부분을 잡고 끌어서 옮길 수 있다.
 * - [캡처] 버튼, 카드 위에서 S펜 버튼 누르기, 펜 버튼 길게 누르기 → 카드는 그 자리에 둔 채 새로 캡처해서
 *   같은 대화에 이미지를 붙인다.
 * - 버튼을 누르거나 직접 질문을 보내기 전에는 아무것도 전송하지 않는다 (설정에서 자동 요청 가능).
 * - 카드 바깥 터치는 그대로 아래 앱(Anki)으로 전달된다.
 */
@SuppressLint("SetTextI18n", "ClickableViewAccessibility")
class FloatingPanel(
    private val ctx: Context,
    firstPreview: Bitmap,
    firstImage: () -> ByteArray,
    private val selection: Rect,
    private val clientProvider: () -> AnthropicClient?,
    private val onOpenSettings: () -> Unit,
    private val onRecapture: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val c = Palette.of(ctx)
    private val renderer = AnswerRenderer(density, c.strong, c.accent)

    private val screen: Rect = wm.maximumWindowMetrics.bounds
    private val margin = dp(12)
    private val shadow = dp(14) // 그림자가 잘리지 않게 카드 바깥에 두는 여백

    private lateinit var window: PanelFrame
    private lateinit var card: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var caption: TextView
    private lateinit var answerScroll: ScrollView
    private lateinit var answer: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private val chips = mutableListOf<TextView>()

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

    /** 질문 하나와 그 답. 새 캡처와 함께 보낸 질문이면 thumb에 작은 이미지가 붙는다. */
    private class Entry(val label: String, val thumb: Bitmap?) {
        val raw = StringBuilder()
        var error: String? = null
    }

    private val entries = mutableListOf<Entry>()
    private var anchor = Anchor.FREE
    private var tutor: ClaudeTutor? = null
    private var busy = false
    private var closed = false
    private var hidden = false
    private var imeMode = false
    private var yBeforeIme: Int? = null

    /** 아직 보내지 않은 캡처. 다음 질문에 함께 보내고, 성공하면 비운다. */
    private var pendingImage: (() -> ByteArray)? = firstImage
    private var pendingPreview: Bitmap? = firstPreview
    private var captureCount = 1

    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    // ------------------------------------------------------------------ 표시 / 숨기기 / 닫기

    fun show() {
        buildViews()
        thumb.setImageBitmap(pendingPreview)
        placeInitially()
        wm.addView(window, lp)
        window.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) keepOnScreen()
        }
        if (prefs.autoAsk) send(ClaudeTutor.Q_GRAMMAR, "해석·문법")
    }

    /** 다시 캡처하는 동안 카드를 감춘다 (스크린샷에 찍히지 않게). 위치와 대화는 그대로. */
    fun hideForCapture() {
        if (closed || hidden) return
        hidden = true
        if (imeMode) setImeMode(false)
        window.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(window, lp)
    }

    fun reveal() {
        if (closed || !hidden) return
        hidden = false
        window.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        wm.updateViewLayout(window, lp)
    }

    /** 새 캡처를 이 카드(같은 자리, 같은 대화)에 붙인다. */
    fun attachCapture(preview: Bitmap, image: () -> ByteArray) {
        if (closed) return
        pendingImage = image
        pendingPreview = preview
        captureCount++
        thumb.setImageBitmap(preview)
        reveal()
        if (prefs.autoAsk) {
            send(ClaudeTutor.Q_GRAMMAR, "해석·문법")
        } else {
            caption.text = "새 캡처를 붙였어요. 버튼을 누르거나 아래에 물어보세요."
            caption.setTextColor(c.accent)
            caption.visibility = View.VISIBLE
        }
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

    private fun panelWidth() = min(dp(480), screen.width() - margin * 2)

    private fun buildViews() {
        window = PanelFrame(ctx).apply {
            setPadding(shadow, shadow, shadow, shadow)
            onBack = { if (imeMode) setImeMode(false) else close() }
            onStylusButton = { onRecapture() }
        }

        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(c.card)
                setStroke(max(1, dp(1) / 2), c.stroke)
            }
            elevation = dpf(10f)
        }
        window.addView(card, FrameLayout.LayoutParams(panelWidth() - shadow * 2, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 끌기 손잡이
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

        // 머리줄: 미리보기 / 모델 / [캡처] / 설정 / 닫기
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thumb = ImageView(ctx).apply {
            adjustViewBounds = true
            maxHeight = dp(40)
            maxWidth = dp(140)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(c.soft)
            }
            clipToOutline = true
        }
        header.addView(thumb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        header.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(pill(modelLabel()))
        header.addView(captureButton(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
        ).apply { marginStart = dp(8) })
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
            textSize = 13f
            setTextColor(c.muted)
        }
        card.addView(caption, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // 답변 (가독성: 16sp, 넉넉한 줄 간격, 진한 본문색, 굵은 핵심어)
        answer = TextView(ctx).apply {
            textSize = 16f
            setTextColor(c.text)
            setLineSpacing(dpf(2f), 1.42f)
            letterSpacing = 0.01f
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(0, 0, dp(4), dp(4))
        }
        answerScroll = MaxHeightScrollView(ctx, min(dp(480), (screen.height() * 0.55f).roundToInt())).apply {
            addView(answer)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            visibility = View.GONE
        }
        card.addView(answerScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

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
        if (prefs.model == ClaudeTutor.MODEL_SONNET) "Sonnet" else "Haiku"

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

    private fun captureButton() = TextView(ctx).apply {
        text = "＋ 캡처"
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(c.onAccentSoft)
        setPadding(dp(12), 0, dp(12), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(c.ripple),
            GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(c.accentSoft)
            },
            null,
        )
        setOnClickListener { onRecapture() }
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
        val w = panelWidth()
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
            caption.visibility = View.VISIBLE
            return
        }
        val t = tutor ?: ClaudeTutor(client, prefs.model, prefs.systemPrompt).also { tutor = it }

        val image = pendingImage
        // 두 번째 캡처부터는 어떤 이미지에 대한 질문인지 답변 안에 작은 그림으로 표시
        val entryThumb = if (image != null && captureCount > 1) pendingPreview else null
        val entry = Entry(if (label.length > 60) label.take(60) + "…" else label, entryThumb)
        entries += entry

        caption.visibility = View.GONE
        answerScroll.visibility = View.VISIBLE
        val entryStart = render()
        if (entries.size > 1) {
            answer.post {
                val layout = answer.layout ?: return@post
                answerScroll.smoothScrollTo(0, layout.getLineTop(layout.getLineForOffset(entryStart)))
            }
        }

        setBusy(true)
        statusText.text = "읽는 중…"
        statusRow.visibility = View.VISIBLE

        thread(name = "claude-ask") {
            var first = true
            try {
                t.ask(image?.invoke(), "image/png", question) { delta ->
                    main.post {
                        if (closed) return@post
                        if (first) {
                            first = false
                            statusRow.visibility = View.GONE
                        }
                        entry.raw.append(delta)
                        render()
                    }
                }
                main.post {
                    if (closed) return@post
                    // 이미지를 함께 보낸 질문이 성공했으면, 다음 질문부터는 대화 기록에 있는 이미지를 쓴다
                    if (pendingImage === image) pendingImage = null
                    statusRow.visibility = View.GONE
                    setBusy(false)
                }
            } catch (e: ClaudeTutor.TutorException) {
                main.post { showError(entry, e.message ?: "오류가 발생했어요.") }
            } catch (e: Exception) {
                main.post { showError(entry, "오류: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            }
        }
    }

    /** 대화 전체를 다시 그린다. 마지막 질문이 시작하는 글자 위치를 돌려준다. */
    private fun render(): Int {
        val sb = SpannableStringBuilder()
        var lastStart = 0
        entries.forEachIndexed { i, e ->
            if (i > 0) {
                // 질문 사이 간격
                val s = sb.length
                sb.append("\n\n")
                sb.setSpan(AbsoluteSizeSpan(dp(10)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            lastStart = sb.length
            if (e.thumb != null) {
                val h = dp(26)
                val w = (e.thumb.width * h.toFloat() / e.thumb.height).roundToInt().coerceIn(dp(16), dp(160))
                val d = BitmapDrawable(ctx.resources, e.thumb).apply { setBounds(0, 0, w, h) }
                val s = sb.length
                sb.append("￼")
                sb.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("  ")
            }
            val ls = sb.length
            sb.append(e.label).append("\n")
            sb.setSpan(ForegroundColorSpan(c.accent), ls, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), ls, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.84f), ls, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            renderer.append(sb, e.raw.toString())
            e.error?.let { err ->
                val s = sb.length
                if (e.raw.isNotEmpty()) sb.append("\n")
                sb.append(err)
                sb.setSpan(ForegroundColorSpan(c.error), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        answer.text = sb
        return lastStart
    }

    private fun showError(entry: Entry, msg: String) {
        if (closed) return
        statusRow.visibility = View.GONE
        entry.error = msg
        render()
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

    /**
     * 카드의 바깥 틀.
     * - 뒤로 가기: 키보드 닫기 / 카드 닫기 (창이 포커스를 가질 때만 온다)
     * - 카드 위에서 S펜 버튼을 누르면(펜을 띄운 채 또는 닿은 채) 다시 캡처
     */
    private class PanelFrame(ctx: Context) : FrameLayout(ctx) {
        var onBack: (() -> Unit)? = null
        var onStylusButton: (() -> Unit)? = null
        private var lastFire = 0L

        private fun isStylusButton(e: MotionEvent): Boolean {
            val stylus = (0 until e.pointerCount).any { e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS }
            val pressed = (e.buttonState and
                (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
            return stylus && pressed
        }

        private fun fire(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lastFire > 1200) {
                lastFire = now
                onStylusButton?.invoke()
            }
            return true
        }

        override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
            if (isStylusButton(e)) return fire()
            return super.dispatchGenericMotionEvent(e)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && isStylusButton(e)) return fire()
            return super.dispatchTouchEvent(e)
        }

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

    /** 라이트/다크 모드 색. 본문은 배경과 대비가 충분하도록 진하게. */
    private class Palette(
        val card: Int, val stroke: Int, val text: Int, val strong: Int, val muted: Int, val soft: Int,
        val accent: Int, val accentSoft: Int, val onAccentSoft: Int, val ripple: Int, val error: Int,
    ) {
        companion object {
            fun of(ctx: Context): Palette {
                val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                return if (night) Palette(
                    card = 0xFF1C1D22.toInt(), stroke = 0xFF34353D.toInt(),
                    text = 0xFFE9E9EE.toInt(), strong = 0xFFFFFFFF.toInt(),
                    muted = 0xFF9EA0AA.toInt(), soft = 0xFF2A2B33.toInt(),
                    accent = 0xFF9AA3FF.toInt(), accentSoft = 0xFF2E3354.toInt(),
                    onAccentSoft = 0xFFD0D4FF.toInt(), ripple = 0x33FFFFFF, error = 0xFFFF8A65.toInt(),
                ) else Palette(
                    card = 0xFFFFFFFF.toInt(), stroke = 0xFFE4E4EA.toInt(),
                    text = 0xFF26262C.toInt(), strong = 0xFF0B0B10.toInt(),
                    muted = 0xFF6E6E78.toInt(), soft = 0xFFF2F2F6.toInt(),
                    accent = 0xFF4552CC.toInt(), accentSoft = 0xFFE8EAFC.toInt(),
                    onAccentSoft = 0xFF2F3BAA.toInt(), ripple = 0x22000000, error = 0xFFD84315.toInt(),
                )
            }
        }
    }
}
