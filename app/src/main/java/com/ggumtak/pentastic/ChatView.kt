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
        /** 한자 연습장 열기 */
        fun openPractice() {}
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
    /** 대화 목록: 질문 말풍선(오른쪽) + 답변(왼쪽 일반 글)을 차례로 쌓는다 */
    private val convo: LinearLayout

    /** 질문 하나에 해당하는 화면 조각. 답변이 흘러들어올 때 그 답변 글만 바꾼다. */
    private class EntryViews(val root: LinearLayout, val answer: TextView) {
        var shownLen = -1
        var shownError: String? = null
    }

    private val entryViews = mutableListOf<EntryViews>()
    private val statusRow: LinearLayout
    val input: EditText
    private val sendBtn: TextView
    private val inkPanel: LinearLayout
    private val ink: InkView
    private val candRow: LinearLayout
    private val inkStatus: TextView
    private val chips = mutableListOf<TextView>()
    private val chipRow: LinearLayout
    private val subjectRow: LinearLayout
    private val subjectButton: TextView
    private val modelButton: TextView
    private var shownSubject: String? = null
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
        if (mode == Mode.DOCKED) {
            header.addView(TextView(ctx).apply {
                text = "AI 챗"
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(c.text)
                setPadding(0, 0, dp(10), 0)
            })
        }
        // 과목 고르기 (누르면 아래에 과목 목록이 펼쳐진다)
        subjectButton = pillButton("", accent = false) { toggleSubjects() }
        header.addView(subjectButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        header.addView(View(ctx), LayoutParams(0, 1, 1f))
        // 모델 (누르면 이 과목의 모델이 Haiku ↔ Sonnet으로 바뀐다. 대화는 이어짐)
        modelButton = pillButton("", accent = false) { session.toggleModel() }.apply { textSize = 12f }
        header.addView(modelButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        header.addView(pillButton("＋ 캡처", accent = true) { actions.recapture() },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
        header.addView(pillButton("연습장", accent = false) { actions.openPractice() },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
        if (mode == Mode.CARD) {
            header.addView(iconButton("⧉") { actions.openDocked() })
        } else {
            header.addView(pillButton("새 대화", accent = false) { session.reset() },
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(6) })
        }
        header.addView(iconButton("⚙") { actions.openSettings() })
        if (mode == Mode.CARD) header.addView(iconButton("✕") { actions.close() })
        if (mode == Mode.DOCKED) {
            // 좁은 분할 화면에서는 머리줄을 옆으로 밀어서 모든 버튼을 누를 수 있게 한다
            addView(HorizontalScrollView(ctx).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength(dp(24))
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(header)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            addView(header)
        }
        dragHandles += header

        subjectRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            visibility = View.GONE
        }
        Subjects.all.forEach { subj ->
            subjectRow.addView(chip(subj.name, primary = false) {
                subjectRow.visibility = View.GONE
                session.switchSubject(subj.id)
            }.apply { tag = subj.id }, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(subjectRow)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })

        caption = TextView(ctx).apply {
            textSize = 13f
            setTextColor(c.muted)
            visibility = View.GONE
        }
        addView(caption, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })

        // 대화 (질문 말풍선 + 답변)
        convo = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(0, 0, dp(4), dp(4))
        }
        answerScroll = (if (mode == Mode.CARD) MaxHeightScrollView(ctx, maxAnswerHeight) else ScrollView(ctx)).apply {
            addView(convo)
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
            text = "보낼 이미지"
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

        // 빠른 질문 버튼 (과목마다 다르다. render()에서 채운다)
        chipRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chipRow)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        // 손글씨 입력 (✍): 한자를 쓰면 후보가 뜨고, 고르면 입력칸에 들어간다
        inkPanel = LinearLayout(ctx).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        ink = InkView(ctx, lineColor = c.stroke, inkColor = c.strong, guideColor = c.stroke)
        inkPanel.addView(ink, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (mode == Mode.DOCKED) 240 else 180)))
        inkStatus = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(c.muted)
            setPadding(dp(2), dp(6), 0, 0)
        }
        inkPanel.addView(inkStatus)
        candRow = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        inkPanel.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(candRow)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        val inkActions = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        inkActions.addView(chip("획 취소", primary = false) { ink.undo() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        inkActions.addView(chip("지우기", primary = false) { ink.clear() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        inkActions.addView(chip("부수·구성 묻기", primary = true) { askRadicals() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        inkActions.addView(chip("닫기", primary = false) { toggleInk(false) }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        inkPanel.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(inkActions)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(inkPanel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        ink.onStrokesChanged = { recognizeInk() }

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
        inputRow.addView(TextView(ctx).apply {
            text = "✍"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(c.accent)
            background = RippleDrawable(ColorStateList.valueOf(c.ripple), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            setOnClickListener { toggleInk(inkPanel.visibility != View.VISIBLE) }
        }, LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) })
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

    private fun toggleInk(show: Boolean) {
        inkPanel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        inkStatus.text = "한자를 쓰면 아래에 후보가 나옵니다. 고르면 입력칸에 들어갑니다."
        Handwriting.prepare { err ->
            if (err != null) inkStatus.text = err
        }
    }

    private fun recognizeInk() {
        if (ink.isEmpty) {
            candRow.removeAllViews()
            return
        }
        Handwriting.prepare { err ->
            if (err != null) {
                inkStatus.text = err
                return@prepare
            }
            Handwriting.recognize(ink.strokeData, ink.width.toFloat(), ink.height.toFloat()) { cands ->
                candRow.removeAllViews()
                if (cands.isEmpty()) inkStatus.text = "인식하지 못했습니다. 조금 더 크게 또는 또박또박 써 보세요."
                cands.forEach { cand ->
                    candRow.addView(chip(cand, primary = false) {
                        input.append(cand)
                        ink.clear()
                        inkStatus.text = "입력칸: ${input.text}  ·  더 쓰거나 [부수·구성 묻기]"
                    }.apply { textSize = 22f; setTypeface(Typeface.DEFAULT, Typeface.NORMAL); textLocale = java.util.Locale.JAPAN },
                        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                }
            }
        }
    }

    /** 입력칸의 한자(비어 있으면 가장 그럴듯한 후보)에 대해 부수·구성을 묻는다. */
    private fun askRadicals() {
        var text = input.text.toString().trim()
        if (text.isEmpty() && candRow.childCount > 0) text = (candRow.getChildAt(0) as TextView).text.toString()
        if (text.isEmpty()) {
            inkStatus.text = "먼저 한자를 쓰고 후보를 골라 주세요."
            return
        }
        input.setText("")
        ink.clear()
        actions.inputDone()
        session.send(ClaudeTutor.radicalQuestion(text), "부수·구성: $text")
    }

    private fun toggleSubjects() {
        subjectRow.visibility = if (subjectRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** 과목이 바뀌었으면 과목 이름과 빠른 질문 버튼을 다시 만든다. */
    private fun syncSubject() {
        val subj = session.subject
        if (shownSubject == subj.id) return
        shownSubject = subj.id
        subjectButton.text = "${subj.name} ▾"
        for (i in 0 until subjectRow.childCount) {
            val v = subjectRow.getChildAt(i) as TextView
            val selected = v.tag == subj.id
            v.setTextColor(if (selected) c.onAccentSoft else c.text)
            (v.background as? RippleDrawable)?.getDrawable(0)?.let { d ->
                (d as? GradientDrawable)?.setColor(if (selected) c.accentSoft else c.soft)
            }
        }
        chips.clear()
        chipRow.removeAllViews()
        subj.buttons.forEachIndexed { i, (label, q) ->
            val chip = chip(label, primary = i == 0) { session.send(q, label) }
            chips += chip
            chipRow.addView(chip, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
    }

    private fun render() {
        syncSubject()
        modelButton.text = if (session.model == ClaudeTutor.MODEL_SONNET) "Sonnet ⇄" else "Haiku ⇄"
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
            if (entryViews.isNotEmpty()) {
                convo.removeAllViews()
                entryViews.clear()
            }
            answerScroll.visibility = if (mode == Mode.DOCKED) View.VISIBLE else View.GONE
            renderedCount = 0
            return
        }
        answerScroll.visibility = View.VISIBLE

        // 대화가 새로 시작됐으면(줄었으면) 다시 만든다
        if (entryViews.size > entries.size) {
            convo.removeAllViews()
            entryViews.clear()
        }
        var newView: View? = null
        while (entryViews.size < entries.size) {
            val ev = buildEntryViews(entries[entryViews.size], first = entryViews.isEmpty())
            convo.addView(ev.root)
            entryViews += ev
            newView = ev.root
        }
        // 답변 글 갱신 (바뀐 것만)
        entries.forEachIndexed { i, e ->
            val ev = entryViews[i]
            if (ev.shownLen == e.raw.length && ev.shownError == e.error) return@forEachIndexed
            ev.shownLen = e.raw.length
            ev.shownError = e.error
            val sb = SpannableStringBuilder()
            renderer.append(sb, e.raw.toString())
            e.error?.let { err ->
                val st = sb.length
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(err)
                sb.setSpan(ForegroundColorSpan(c.error), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ev.answer.text = sb
            ev.answer.visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        }

        // 새 질문이 생겼을 때 한 번만 그 말풍선 위치로 스크롤 (읽는 중에 자동으로 따라 내려가지 않게)
        if (entries.size != renderedCount) {
            val scrollTo = if (entries.size > renderedCount && entries.size > 1) newView else null
            renderedCount = entries.size
            scrollTo?.let { v -> v.post { answerScroll.smoothScrollTo(0, v.top) } }
        }
    }

    /** 질문 말풍선(오른쪽, 캡처 이미지 포함) + 답변 글 */
    private fun buildEntryViews(e: ChatSession.Entry, first: Boolean): EntryViews {
        val root = LinearLayout(context).apply { orientation = VERTICAL }
        val maxBubble = ((if (convo.width > 0) convo.width else dp(if (mode == Mode.DOCKED) 420 else 400)) * 0.85f).roundToInt()

        val bubble = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                // 오른쪽 아래 모서리만 덜 둥글게 (말풍선 꼬리 느낌)
                cornerRadii = floatArrayOf(dpf(18f), dpf(18f), dpf(18f), dpf(18f), dpf(6f), dpf(6f), dpf(18f), dpf(18f))
                setColor(c.bubble)
            }
        }
        e.thumb?.let { bmp ->
            bubble.addView(ImageView(context).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                maxHeight = dp(110)
                maxWidth = maxBubble - dp(28)
                scaleType = ImageView.ScaleType.FIT_END
                background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(Color.WHITE) }
                clipToOutline = true
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                bottomMargin = dp(6)
            })
        }
        bubble.addView(TextView(context).apply {
            text = e.label
            textSize = 15f
            setTextColor(c.text)
            setLineSpacing(dpf(1f), 1.3f)
            maxWidth = maxBubble - dp(28)
            setTextIsSelectable(mode == Mode.DOCKED)
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        root.addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            topMargin = if (first) 0 else dp(20)
            marginStart = dp(40)
        })

        // 답변 (가독성: 16sp, 넉넉한 줄 간격, 진한 본문색, 굵은 핵심어)
        val answer = TextView(context).apply {
            textSize = if (mode == Mode.DOCKED) 16.5f else 16f
            setTextColor(c.text)
            setLineSpacing(dpf(2f), 1.42f)
            letterSpacing = 0.01f
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            setTextIsSelectable(mode == Mode.DOCKED)
            visibility = View.GONE
        }
        root.addView(answer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        return EntryViews(root, answer)
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
        /** 내가 보낸 질문 말풍선 배경 */
        val bubble: Int,
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
                    bubble = 0xFF2F3038.toInt(),
                ) else Palette(
                    card = 0xFFFFFFFF.toInt(), stroke = 0xFFE4E4EA.toInt(),
                    text = 0xFF26262C.toInt(), strong = 0xFF0B0B10.toInt(),
                    muted = 0xFF6E6E78.toInt(), soft = 0xFFF2F2F6.toInt(),
                    accent = 0xFF4552CC.toInt(), accentSoft = 0xFFE8EAFC.toInt(),
                    onAccentSoft = 0xFF2F3BAA.toInt(), ripple = 0x22000000, error = 0xFFD84315.toInt(),
                    bubble = 0xFFEFEFF3.toInt(),
                )
            }
        }
    }
}
