package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 한자 연습장의 쓰기 부분 (큰 정사각 쓰기 칸, 따라 쓰기, 인식 결과, 후보, 버튼).
 * 기본은 연습장 화면(PracticeActivity) 안에 AI 챗과 나란히 있고,
 * [⧉ 띄우기]로 화면 위에 떠 있는 창이 되어 끌어서 옮기고 ⇲로 크기를 바꿀 수 있다 (PracticeDock).
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
class PracticePad(ctx: Context) : LinearLayout(ctx) {

    interface Host {
        fun swap()
        fun flip()
        fun close()
    }

    var host: Host? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density
    private val c = ChatView.Palette.of(ctx)

    val toolbar: LinearLayout
    private val toolbarScroll: HorizontalScrollView
    val target: EditText
    val ink: InkView
    private val result: TextView
    private val candRow: LinearLayout
    private val candScroll: HorizontalScrollView
    private val actionsRow: LinearLayout
    private val swapBtn: TextView
    private val flipBtn: TextView
    private val floatBtn: TextView
    private var ghostOn = true
    private var topCandidate: String? = null

    init {
        orientation = VERTICAL

        toolbar = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
        }
        toolbar.addView(TextView(ctx).apply {
            text = "한자 연습장"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.text)
            setPadding(0, 0, dp(10), 0)
        })
        target = EditText(ctx).apply {
            hint = "연습할 한자"
            textSize = 18f
            textLocale = Locale.JAPAN
            setTextColor(c.text)
            setHintTextColor(c.muted)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            background = GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(c.soft)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        toolbar.addView(target, LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
        toolbar.addView(View(ctx), LayoutParams(0, 1, 1f))
        swapBtn = toolButton("⇄") { host?.swap() }
        flipBtn = toolButton("⇅") { host?.flip() }
        floatBtn = toolButton("⧉ 띄우기") { PracticeDock.toggleFloat(context) }
        toolButton("✕ 닫기") { if (PracticeDock.floating) PracticeDock.close() else host?.close() }
        toolbarScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            isFillViewport = true
            addView(toolbar)
        }

        ink = InkView(ctx, lineColor = c.stroke, inkColor = c.strong, guideColor = c.stroke, square = true)

        // 결과 문구는 항상 2줄 높이 (문구 길이가 바뀌어도 쓰는 칸이 움직이지 않게)
        result = TextView(ctx).apply {
            textSize = 15f
            setTextColor(c.text)
            setPadding(0, dp(8), 0, dp(2))
            setLines(2)
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "칸에 한자를 써 보세요. 쓰는 대로 무슨 글자로 읽히는지 알려 드립니다.\n펜 버튼을 누른 채 그으면 지워집니다."
        }

        candRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            addView(candRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        actionsRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        fun action(label: String, primary: Boolean = false, onClick: (TextView) -> Unit) {
            val b = chipView(label, primary)
            b.setOnClickListener { onClick(b) }
            actions.addView(b, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        action("획 취소") { ink.undo() }
        action("지우기") { ink.clear() }
        action("따라쓰기 끄기") { b ->
            ghostOn = !ghostOn
            b.text = if (ghostOn) "따라쓰기 끄기" else "따라쓰기 켜기"
            updateGhost()
        }
        action("내 글씨 봐 줘", primary = true) { askReview() }
        action("부수·구성") {
            val k = target.text.toString().trim().ifEmpty { topCandidate ?: "" }
            if (k.isEmpty()) result.text = "연습할 한자를 적거나 먼저 써 주세요."
            else {
                ChatSession.get(context).send(ClaudeTutor.radicalQuestion(k), "부수·구성: $k")
                showAnswerSomewhere()
            }
        }
        // 쓴 글씨를 그림 그대로 채팅에 '보낼 이미지'로 붙인다 (전송은 하지 않음)
        action("그림 붙이기") {
            val png = ink.toPng()
            if (png == null) result.text = "먼저 칸에 한자를 써 주세요."
            else {
                ChatSession.get(context).attach(png.first, { png.second }, allowAutoAsk = false, mime = "image/png")
                result.text = "쓴 글씨를 채팅에 붙였습니다. 채팅에서 버튼을 누르거나 직접 물어보세요."
                showAnswerSomewhere()
            }
        }
        actionsRow.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            addView(actions)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateGhost()
                checkMatch()
            }
        })
        // 떠 있을 때 '연습할 한자' 칸을 누르면 키보드를 쓸 수 있게 창 포커스를 켠다
        target.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP && PracticeDock.floating) PracticeDock.setFocusable(true, target)
            false
        }
        target.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && PracticeDock.floating) {
                PracticeDock.setFocusable(false, target)
                true
            } else false
        }
        ink.onStrokesChanged = { recognize() }
        updateGhost()
    }

    /** 배치: 연습장 화면 안(docked)인지, 떠 있는지(floating), 메뉴줄을 아래에 둘지 */
    fun layoutFor(floating: Boolean, bottomBar: Boolean, inkSide: Int = 0) {
        removeAllViews()
        if (floating) {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(c.card)
                setStroke(dp(2), c.accent)
            }
            elevation = dpf(10f)
        } else {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = null
            setBackgroundColor(c.card)
            elevation = 0f
        }
        swapBtn.visibility = if (floating) View.GONE else View.VISIBLE
        flipBtn.visibility = if (floating) View.GONE else View.VISIBLE
        floatBtn.text = if (floating) "⤓ 화면에 붙이기" else "⧉ 띄우기"
        // 떠 있을 때는 가장자리로 너비·높이를 따로 조절하므로 정사각 고정을 푼다
        ink.keepSquare = !floating

        val bar = !floating && bottomBar
        if (!bar) addView(toolbarScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ink, if (floating) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, inkSide).apply { topMargin = dp(8) }
        else LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(if (bar) 0 else 12)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(result)
        addView(candScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        addView(actionsRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (bar) addView(toolbarScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })
    }

    fun setInkSide(px: Int) {
        val lp = ink.layoutParams ?: return
        if (lp.height != px) {
            lp.height = px
            ink.layoutParams = lp
        }
    }

    fun prepare() {
        Handwriting.prepare { err -> if (err != null) result.text = err }
    }

    private fun updateGhost() {
        ink.ghost = if (ghostOn) target.text.toString().trim().take(2) else null
    }

    private fun recognize() {
        if (ink.isEmpty) {
            topCandidate = null
            candRow.removeAllViews()
            if (ChatView.active?.inputText().isNullOrEmpty()) result.text = "칸에 한자를 써 보세요."
            return
        }
        Handwriting.prepare { err ->
            if (err != null) {
                result.text = err
                return@prepare
            }
            Handwriting.recognize(ink.strokeData, ink.width.toFloat(), ink.height.toFloat()) { cands ->
                topCandidate = cands.firstOrNull()
                checkMatch(cands)
                showCandidates(cands)
            }
        }
    }

    private fun showCandidates(cands: List<String>) {
        candRow.removeAllViews()
        if (cands.isNotEmpty()) {
            candRow.addView(TextView(context).apply {
                text = "채팅에 넣기 →"
                textSize = 12.5f
                setTextColor(c.muted)
                setPadding(0, 0, dp(8), 0)
            })
        }
        cands.take(8).forEachIndexed { i, cand ->
            candRow.addView(chipView(cand, primary = i == 0).apply {
                textSize = 22f
                setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                textLocale = Locale.JAPAN
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(14), dp(2), dp(14), dp(2))
                setOnClickListener {
                    val chat = ChatView.active
                    if (chat == null) {
                        Toast.makeText(context, "열려 있는 채팅이 없어요. AI 챗이나 카드를 먼저 열어 주세요.", Toast.LENGTH_SHORT).show()
                    } else {
                        chat.appendInput(cand)
                        ink.clear()
                        result.text = "채팅 입력칸: ${chat.inputText()}  ·  더 쓰거나 채팅에서 보내기"
                    }
                }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) })
        }
    }

    private fun checkMatch(cands: List<String>? = null) {
        val top = topCandidate ?: return
        val want = target.text.toString().trim()
        val others = cands?.drop(1)?.take(4)?.joinToString(" ") ?: ""
        result.text = when {
            want.isEmpty() -> "인식: $top" + if (others.isNotEmpty()) "   (다른 후보: $others)" else ""
            top == want -> "✓ 「$want」로 잘 읽힙니다."
            cands?.contains(want) == true -> "△ 「$want」 후보에는 있지만 「$top」로 더 읽힙니다. 모양을 조금 다듬어 보세요."
            else -> "✗ 「$top」로 읽힙니다. 「$want」와 비교해 보세요."
        }
    }

    private fun askReview() {
        val png = ink.toPng()
        if (png == null) {
            result.text = "먼저 칸에 한자를 써 주세요."
            return
        }
        val want = target.text.toString().trim().ifEmpty { topCandidate ?: "" }
        val (bmp, bytes) = png
        val session = ChatSession.get(context)
        session.attach(bmp, { bytes }, allowAutoAsk = false, mime = "image/png")
        val q = if (want.isNotEmpty())
            "이 이미지는 내가 손으로 쓴 「$want」야. 모양·균형·비율이 자연스러운지, 빠지거나 잘못 붙은 획이 있는지 봐 주고, 부수와 구성도 짧게 알려 줘. 획순은 그림으로 알 수 없으니 이 한자에서 자주 틀리는 획순만 짚어 줘."
        else
            "이 이미지는 내가 손으로 쓴 한자야. 무슨 글자인지 읽고, 모양·균형·빠진 획을 봐 준 뒤 부수와 구성을 짧게 알려 줘."
        session.send(q, "내 글씨: ${want.ifEmpty { "?" }}")
        showAnswerSomewhere()
    }

    /** 떠 있는 연습장만 있고 채팅이 안 보이면, 답을 볼 수 있게 연습장 옆에 대화 카드를 띄운다 */
    private fun showAnswerSomewhere() {
        if (ChatView.active != null) return
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        CaptureService.instance?.showChatCard(Rect(loc[0], loc[1], loc[0] + width, loc[1] + height))
    }

    private fun toolButton(label: String, onClick: () -> Unit): TextView {
        val b = TextView(context).apply {
            text = label
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(c.text)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dpf(18f)
                setColor(c.soft)
            }
            setOnClickListener { onClick() }
        }
        toolbar.addView(b, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6) })
        return b
    }

    private fun chipView(label: String, primary: Boolean) = TextView(context).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) c.onAccentSoft else c.text)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dpf(18f)
            setColor(if (primary) c.accentSoft else c.soft)
        }
    }
}

/**
 * 연습장 쓰기 부분을 연습장 화면(기본)과 화면 위 떠 있는 창 사이로 옮긴다.
 * 옮겨도 쓴 획과 '연습할 한자'는 그대로 남는다.
 */
@SuppressLint("ClickableViewAccessibility")
object PracticeDock {
    private var pad: PracticePad? = null
    private var window: ResizableFrame? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lastX = -1
    private var lastY = -1

    var floating = false
        private set

    fun pad(ctx: Context): PracticePad = pad ?: PracticePad(ctx.applicationContext).also { pad = it }

    fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("pentastic", Context.MODE_PRIVATE)

    fun toggleFloat(ctx: Context) {
        if (floating) dock(ctx) else float(ctx)
    }

    private fun float(ctx: Context) {
        val svc = CaptureService.instance
        if (svc == null) {
            Toast.makeText(ctx, "띄우려면 설정에서 화면 캡처 권한(접근성)을 먼저 켜 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val p = pad(ctx)
        detach(p)
        p.host = null
        val wm = svc.getSystemService(WindowManager::class.java)
        val d = svc.resources.displayMetrics.density
        val screen = wm.maximumWindowMetrics.bounds
        val shadow = (12 * d).roundToInt()
        val padW = sp(ctx).getInt("practice_float_w", (460 * d).roundToInt())
            .coerceIn((320 * d).roundToInt(), screen.width() - (24 * d).roundToInt() - shadow * 2)
        val inkH = sp(ctx).getInt("practice_float_ink_h", padW - (36 * d).roundToInt())
            .coerceIn((140 * d).roundToInt(), (screen.height() * 0.75f).roundToInt())
        p.layoutFor(floating = true, bottomBar = false, inkSide = inkH)
        lateinit var params: WindowManager.LayoutParams
        lateinit var frame: ResizableFrame
        var start = ResizableFrame.Box(0, 0, 0, 0)
        // 가장자리·모서리 어디든 잡고 끌면 크기 조절 (너비 = 연습장 너비, 높이 = 쓰기 칸 높이)
        frame = ResizableFrame(
            svc, ResizableFrame.ALL,
            band = shadow + (8 * d).roundToInt(), corner = shadow + (14 * d).roundToInt(),
            outlineColor = ChatView.Palette.of(svc).accent,
            listener = object : ResizableFrame.Listener {
                override fun onResizeStart() {
                    start = ResizableFrame.Box(params.x, params.y, p.width, p.ink.height)
                }

                override fun onResize(dLeft: Int, dTop: Int, dRight: Int, dBottom: Int) {
                    val b = ResizableFrame.resized(
                        start, dLeft, dTop, dRight, dBottom,
                        minW = (320 * d).roundToInt(), maxW = screen.width() - shadow * 2,
                        minH = (140 * d).roundToInt(), maxH = (screen.height() * 0.75f).roundToInt(),
                    )
                    val lpPad = p.layoutParams
                    if (lpPad != null && lpPad.width != b.w) {
                        lpPad.width = b.w
                        p.layoutParams = lpPad
                    }
                    p.setInkSide(b.h)
                    params.x = b.x
                    params.y = b.y
                    lastX = b.x
                    lastY = b.y
                    try {
                        wm.updateViewLayout(frame, params)
                    } catch (_: Exception) {
                    }
                }

                override fun onResizeEnd() {
                    sp(svc).edit().putInt("practice_float_w", p.width).putInt("practice_float_ink_h", p.ink.height).apply()
                }
            },
        ).apply {
            setPadding(shadow, shadow, shadow, shadow)
            addView(p, FrameLayout.LayoutParams(padW, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX >= 0) lastX else (screen.width() - padW) / 2
            y = if (lastY >= 0) lastY else (40 * d).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = android.R.style.Animation_Toast
        }
        // 메뉴줄을 잡고 끌면 창이 움직인다
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        p.toolbar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).roundToInt()
                        .coerceIn(-shadow, screen.right - frame.width + shadow)
                    params.y = (startY + (e.rawY - downY)).roundToInt()
                        .coerceIn(-shadow, screen.bottom - frame.height + shadow)
                    lastX = params.x
                    lastY = params.y
                    try {
                        wm.updateViewLayout(frame, params)
                    } catch (_: Exception) {
                    }
                }
            }
            true
        }
        wm.addView(frame, params)
        window = frame
        lp = params
        floating = true
        p.prepare()
        PracticeActivity.finishCurrent()
        DebugLog.add("연습장 띄움")
    }

    /** 떠 있는 연습장을 닫고 연습장 화면으로 돌아간다 */
    private fun dock(ctx: Context) {
        removeWindow()
        PracticeActivity.open(ctx)
    }

    /** 연습장 화면이 열릴 때: 떠 있던 창은 거둔다 */
    fun takeForActivity(ctx: Context): PracticePad {
        if (floating) removeWindow()
        val p = pad(ctx)
        detach(p)
        return p
    }

    fun close() {
        if (floating) removeWindow()
        pad?.let { detach(it) }
    }

    /** 떠 있을 때 '연습할 한자' 칸에 키보드로 입력할 수 있게 창 포커스를 켜고 끈다 */
    fun setFocusable(on: Boolean, field: EditText) {
        val w = window ?: return
        val params = lp ?: return
        params.flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            CaptureService.instance?.getSystemService(WindowManager::class.java)?.updateViewLayout(w, params)
        } catch (_: Exception) {
        }
        val imm = field.context.getSystemService(InputMethodManager::class.java)
        if (on) {
            field.requestFocus()
            field.postDelayed({ imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }, 120)
        } else {
            imm.hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
        }
    }

    private fun removeWindow() {
        val w = window ?: return
        pad?.toolbar?.setOnTouchListener(null)
        try {
            CaptureService.instance?.getSystemService(WindowManager::class.java)?.removeViewImmediate(w)
        } catch (_: Exception) {
        }
        w.removeAllViews()
        window = null
        lp = null
        floating = false
    }

    /** 캡처하는 동안 떠 있는 연습장을 감춘다 */
    fun setCaptureHidden(hidden: Boolean) {
        val w = window ?: return
        val params = lp ?: return
        w.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        params.flags = if (hidden) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            CaptureService.instance?.getSystemService(WindowManager::class.java)?.updateViewLayout(w, params)
        } catch (_: Exception) {
        }
    }

    fun onServiceGone() {
        if (floating) removeWindow()
    }
}
