package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 대화 화면 (머리줄, 빠른 질문 버튼, 답변, 입력줄).
 * 떠 있는 카드(CARD)와 분할 화면 AI 챗(DOCKED)이 같은 화면을 쓴다. 내용은 ChatSession에서 가져온다.
 */
@SuppressLint("SetTextI18n", "ClickableViewAccessibility", "ViewConstructor")
class ChatView(
    ctx: Context,
    private val session: ChatSession,
    private val mode: Mode,
    /** CARD 모드에서 답변 영역 최대 높이(px). 넘으면 스크롤. */
    private val maxAnswerHeight: Int = 0,
    private val actions: Actions,
) : LinearLayout(ctx) {

    enum class Mode { CARD, DOCKED }

    interface Actions {
        fun recapture()
        fun openSettings()
        /** CARD: 카드 닫기 / DOCKED: 쓰지 않음 */
        fun close() {}
        /** CARD: 분할 화면으로 옮기기 */
        fun openDocked() {}
        /** CARD: 입력칸을 눌렀을 때 키보드를 쓸 수 있게 창 포커스 켜기 */
        fun inputTouched() {}
        /** CARD: 질문을 보낸 뒤 키보드/포커스 정리 */
        fun inputDone() {}
    }

    private val density = resources.displayMetrics.density
    val palette = Palette.of(ctx)
    private val c = palette
    private val renderer = AnswerRenderer(density, c.strong, c.accent)
    private val prefs = Prefs(ctx)

    /** 끌어서 카드를 옮길 때 잡는 부분 (CARD 모드) */
    val dragHandles = mutableListOf<View>()

    private val attachRow: LinearLayout
    private val attachImage: ImageView
    private val caption: TextView
    private val answerScroll: ScrollView
    private val answer: TextView
    private val statusRow: LinearLayout
    val input: EditText
    private val sendBtn: TextView
    private val chips = mutableListOf<TextView>()
    private var renderedCount = 0

    private val listener: () -> Unit = { render() }

    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    init {
        orientation = VERTICAL
        if (mode == Mode.CARD) {
            setPadding(dp(18), dp(8), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(c.card)
                setStroke(maxOf(1, dp(1) / 2), c.stroke)
            }
            elevation = dpf(10f)
        } else {
            setPadding(dp(20), dp(14), dp(20), dp(16))
            setBackgroundColor(c.card)
        }

        if (mode == Mode.CARD) {
            val grip = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(2f)
                    setColor(c.stroke)
                }
            }
            addView(grip, LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            dragHandles += grip
        }

        // 머리줄: 제목 / 모델 / [＋ 캡처] / [분할 또는 새 대화] / 설정 / (닫기)
        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
        }
        header.addView(TextView(ctx).apply {
            text = if (mode == Mode.DOCKED) "AI 챗" else "질문"
            textSize = if (mode == Mode.DOCKED) 17f else 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.text)
        })
        header.addView(View(ctx), LayoutParams(0, 1, 1f))
        header.addView(pill(if (prefs.model == ClaudeTutor.MODEL_SONNET) "Sonnet" else "Haiku"))
        header.addView(pillButton("＋ 캡처", accent = true) { actions.recapture() },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
        if (mode == Mode.CARD) {
            header.addView(iconButton("⧉") { actions.openDocked() })
        } else {
            header.addView(pillButton("새 대화", accent = false) { session.reset() },
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
        }
        header.addView(iconButton("⚙") { actions.openSettings() })
        if (mode == Mode.CARD) header.addView(iconButton("✕") { actions.close() })
        addView(header)
        dragHandles += header

        caption = TextView(ctx).apply {
            textSize = 13f
            setTextColor(c.muted)
            visibility = View.GONE
        }
        addView(caption, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })

        // 답변 (가독성: 16sp, 넉넉한 줄 간격, 진한 본문색, 굵은 핵심어)
        answer = TextView(ctx).apply {
            textSize = if (mode == Mode.DOCKED) 16.5f else 16f
            setTextColor(c.text)
            setLineSpacing(dpf(2f), 1.42f)
            letterSpacing = 0.01f
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(0, 0, dp(4), dp(4))
            setTextIsSelectable(mode == Mode.DOCKED)
        }
        answerScroll = (if (mode == Mode.CARD) MaxHeightScrollView(ctx, maxAnswerHeight) else ScrollView(ctx)).apply {
            addView(answer)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        addView(answerScroll, if (mode == Mode.CARD)
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        else
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        statusRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        statusRow.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(c.accent)
        }, LayoutParams(dp(16), dp(16)))
        statusRow.addView(TextView(ctx).apply {
            text = "읽는 중…"
            textSize = 13f
            setTextColor(c.muted)
            setPadding(dp(8), 0, 0, 0)
        })
        addView(statusRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        // 보낼 이미지 (캡처하면 여기에 크게 붙는다. 질문을 보내면 대화 안으로 들어간다)
        attachImage = ImageView(ctx).apply {
            adjustViewBounds = true
            maxHeight = dp(if (mode == Mode.DOCKED) 160 else 110)
            maxWidth = dp(if (mode == Mode.DOCKED) 520 else 360)
            scaleType = ImageView.ScaleType.FIT_START
            background = GradientDrawable().apply {
                cornerRadius = dpf(10f)
                setColor(c.soft)
                setStroke(dp(1), c.stroke)
            }
            clipToOutline = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        attachRow = LinearLayout(ctx).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        val attachTop = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        attachTop.addView(TextView(ctx).apply {
            text = "보낼 이미지 · 아래 버튼을 누르거나 직접 물어보세요"
            textSize = 12.5f
            setTextColor(c.accent)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        attachTop.addView(iconButton("✕") { session.removeAttachment() }.apply {
            textSize = 14f
            layoutParams = LayoutParams(dp(32), dp(32))
        })
        attachRow.addView(attachTop)
        attachRow.addView(attachImage, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })
        addView(attachRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })

        // 빠른 질문 버튼
        val chipRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        listOf(
            "해석·문법" to ClaudeTutor.Q_GRAMMAR,
            "한자 예시" to ClaudeTutor.Q_KANJI,
            "단어 뜻" to ClaudeTutor.Q_WORDS,
        ).forEachIndexed { i, (label, q) ->
            val chip = chip(label, primary = i == 0) { session.send(q, label) }
            chips += chip
            chipRow.addView(chip, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        // 직접 질문 입력줄
        val inputRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
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
                if (e.actionMasked == MotionEvent.ACTION_UP) actions.inputTouched()
                false
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTyped(); true
                } else false
            }
        }
        inputRow.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        inputRow.addView(sendBtn, LayoutParams(dp(36), dp(36)))
        addView(inputRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })

        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session.addListener(listener)
        render()
    }

    override fun onDetachedFromWindow() {
        session.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun sendTyped() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        actions.inputDone()
        session.send(q, q)
    }

    // ------------------------------------------------------------------ 그리기

    private fun render() {
        val attached = session.attachedPreview
        if (attached != null) {
            if (attachImage.tag !== attached) {
                attachImage.setImageBitmap(attached)
                attachImage.tag = attached
            }
            attachRow.visibility = View.VISIBLE
        } else {
            attachImage.setImageDrawable(null)
            attachImage.tag = null
            attachRow.visibility = View.GONE
        }

        val notice = session.notice ?: if (session.isEmpty) emptyHint() else null
        caption.text = notice ?: ""
        caption.setTextColor(if (session.noticeIsError) c.error else c.muted)
        caption.visibility = if (notice == null) View.GONE else View.VISIBLE

        statusRow.visibility = if (session.waiting) View.VISIBLE else View.GONE
        chips.forEach {
            it.isEnabled = !session.busy
            it.alpha = if (session.busy) 0.45f else 1f
        }
        sendBtn.isEnabled = !session.busy
        sendBtn.alpha = if (session.busy) 0.45f else 1f

        val entries = session.entries
        if (entries.isEmpty()) {
            answer.text = ""
            answerScroll.visibility = if (mode == Mode.DOCKED) View.VISIBLE else View.GONE
            renderedCount = 0
            return
        }
        answerScroll.visibility = View.VISIBLE

        val sb = SpannableStringBuilder()
        var lastStart = 0
        entries.forEachIndexed { i, e ->
            if (i > 0) {
                val s = sb.length
                sb.append("\n\n")
                sb.setSpan(AbsoluteSizeSpan(dp(10)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            lastStart = sb.length
            e.thumb?.let { bmp ->
                // 대화 안에 캡처 이미지를 보여준다 (높이 최대 72dp, 폭은 답변 칸을 넘지 않게)
                val maxW = (if (answer.width > 0) answer.width else dp(360)) - dp(8)
                var h = dp(72)
                var w = (bmp.width * h.toFloat() / bmp.height).roundToInt()
                if (w > maxW) {
                    h = (h * maxW.toFloat() / w).roundToInt()
                    w = maxW
                }
                val d = BitmapDrawable(resources, bmp).apply { setBounds(0, 0, w, h) }
                val s = sb.length
                sb.append("￼")
                sb.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append("\n")
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

        // 새 질문이 생겼을 때 한 번만 그 질문 위치로 스크롤 (읽는 중에 자동으로 따라 내려가지 않게)
        if (entries.size != renderedCount) {
            val newEntry = entries.size > renderedCount && entries.size > 1
            renderedCount = entries.size
            if (newEntry) {
                val target = lastStart
                answer.post {
                    val layout = answer.layout ?: return@post
                    answerScroll.smoothScrollTo(0, layout.getLineTop(layout.getLineForOffset(target)))
                }
            }
        }
    }

    private fun emptyHint() = if (mode == Mode.DOCKED)
        "펜 버튼을 길게 누르거나 [＋ 캡처]를 누르고, 질문할 부분을 드래그하세요. 캡처가 여기로 바로 들어옵니다."
    else null

    // ------------------------------------------------------------------ 부품

    private fun pill(text: String) = TextView(context).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(c.muted)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dpf(10f)
            setColor(c.soft)
        }
    }

    private fun pillButton(label: String, accent: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(if (accent) c.onAccentSoft else c.text)
        setPadding(dp(12), 0, dp(12), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(c.ripple),
            GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(if (accent) c.accentSoft else c.soft)
            },
            null,
        )
        setOnClickListener { onClick() }
    }

    private fun iconButton(glyph: String, onClick: () -> Unit) = TextView(context).apply {
        text = glyph
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(c.muted)
        layoutParams = LayoutParams(dp(40), dp(40)).apply { marginStart = dp(2) }
        background = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
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
    class Palette(
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
