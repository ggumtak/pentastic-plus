package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ✍ 손글씨 칸. 채팅과 헷갈리지 않게 미색(종이) 카드 + "✍ 손글씨" 제목줄로 구분한다.
 * 채팅 안에 붙어 있거나(기본), [⧉ 띄우기]로 화면 위에 떠 있는 창이 될 수 있다 (InkDock).
 * 후보를 누르면 지금 보이는 채팅의 입력칸에 들어간다.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class InkPad(ctx: Context) : LinearLayout(ctx) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    /** 채팅과 같은 흰(다크 모드는 어두운) 바탕. 구분은 파란 테두리와 파란 제목 표시로 한다. */
    private val pal = ChatView.Palette.of(ctx)
    private val paper = pal.card
    private val paperStroke = pal.stroke
    private val inkColor = pal.strong
    private val text = pal.text
    private val muted = pal.muted
    private val chipBg = pal.soft
    private val accent = pal.accent

    val header: LinearLayout
    val ink: InkView
    private val status: TextView
    private val candRow: LinearLayout
    private val floatBtn: TextView

    init {
        orientation = VERTICAL
        // 가장자리 크기 조절 띠와 버튼이 겹치지 않게 안쪽 여백을 넉넉히
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setColor(paper)
            setStroke(dp(2), accent)
        }

        // 제목줄 (떠 있을 때는 여기를 잡고 끌어서 옮긴다)
        header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
        }
        header.addView(TextView(ctx).apply {
            text = "✍ 손글씨"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(pal.onAccentSoft)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                setColor(pal.accentSoft)
            }
        })
        header.addView(View(ctx), LayoutParams(0, 1, 1f))
        floatBtn = chip("⧉ 띄우기") { InkDock.toggleFloat(context) }
        header.addView(floatBtn)
        header.addView(chip("✕") { InkDock.close() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        addView(header)

        ink = InkView(ctx, lineColor = paperStroke, inkColor = inkColor, guideColor = paperStroke)
        addView(ink, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(6) })

        // 안내 문구·후보 줄은 높이 고정 (쓰는 칸이 움직이지 않게)
        status = TextView(ctx).apply {
            textSize = 12.5f
            setTextColor(muted)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "한자를 쓰면 후보가 나옵니다 · 누르면 채팅 입력칸에 들어갑니다 · 펜 버튼 누른 채 그으면 지우기"
        }
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        candRow = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
            addView(candRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        actions.addView(chip("획 취소") { ink.undo() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        actions.addView(chip("지우기") { ink.clear() }, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        actions.addView(chip("부수·구성 묻기", strong = true) { askRadicals() })
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        ink.onStrokesChanged = { recognize() }
    }

    fun setInkHeight(px: Int) {
        val lp = ink.layoutParams
        if (lp.height != px) {
            lp.height = px
            ink.layoutParams = lp
        }
    }

    fun setFloating(floating: Boolean) {
        floatBtn.text = if (floating) "⤓ 채팅에 붙이기" else "⧉ 띄우기"
        elevation = if (floating) dpf(10f) else 0f
    }

    fun prepare() {
        Handwriting.prepare { err -> if (err != null) status.text = err }
    }

    private fun recognize() {
        if (ink.isEmpty) {
            candRow.removeAllViews()
            return
        }
        Handwriting.prepare { err ->
            if (err != null) {
                status.text = err
                return@prepare
            }
            Handwriting.recognize(ink.strokeData, ink.width.toFloat(), ink.height.toFloat()) { cands ->
                candRow.removeAllViews()
                if (cands.isEmpty()) status.text = "인식하지 못했습니다. 조금 더 크게 또는 또박또박 써 보세요."
                cands.forEachIndexed { i, cand ->
                    candRow.addView(chip(cand, strong = i == 0) {
                        val chat = ChatView.active
                        if (chat == null) {
                            Toast.makeText(context, "열려 있는 채팅이 없어요. 카드나 AI 챗을 먼저 열어 주세요.", Toast.LENGTH_SHORT).show()
                        } else {
                            chat.appendInput(cand)
                            ink.clear()
                            status.text = "입력칸: ${chat.inputText()}  ·  더 쓰거나 [부수·구성 묻기]"
                        }
                    }.apply {
                        textSize = 22f
                        setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                        textLocale = Locale.JAPAN
                        setPadding(dp(14), dp(2), dp(14), dp(2))
                        gravity = Gravity.CENTER
                        minWidth = dp(48)
                    }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(6) })
                }
            }
        }
    }

    /** 채팅 입력칸의 한자(비어 있으면 가장 그럴듯한 후보)에 대해 부수·구성을 묻는다. */
    private fun askRadicals() {
        val chat = ChatView.active
        var t = chat?.inputText()?.trim().orEmpty()
        if (t.isEmpty() && candRow.childCount > 0) t = (candRow.getChildAt(0) as TextView).text.toString()
        if (t.isEmpty()) {
            status.text = "먼저 한자를 쓰고 후보를 골라 주세요."
            return
        }
        chat?.clearInput()
        ink.clear()
        ChatSession.get(context).send(ClaudeTutor.radicalQuestion(t), "부수·구성: $t")
    }

    private fun chip(label: String, strong: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (strong) accent else this@InkPad.text)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = RippleDrawable(
            ColorStateList.valueOf(0x22000000),
            GradientDrawable().apply {
                cornerRadius = dpf(16f)
                setColor(chipBg)
            },
            null,
        )
        setOnClickListener { onClick() }
    }
}

/**
 * 손글씨 칸 하나를 채팅 안(기본)과 화면 위 떠 있는 창 사이로 옮긴다.
 * 옮겨도 쓴 획은 그대로 남는다.
 */
@SuppressLint("ClickableViewAccessibility")
object InkDock {
    private var pad: InkPad? = null
    private var window: ResizableFrame? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lastX = -1
    private var lastY = -1

    var floating = false
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("pentastic", Context.MODE_PRIVATE)

    private fun dpx(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    /**
     * 채팅 안에 붙어 있을 때 손글씨 칸을 감싸는 틀: 위·아래 가장자리를 끌면 쓰는 칸 높이가 바뀐다.
     */
    private var dockHolder: ResizableFrame? = null
    private var dockStartInk = 0

    private fun dockHolder(ctx: Context): ResizableFrame = dockHolder ?: run {
        val app = ctx.applicationContext
        val accent = ChatView.Palette.of(app).accent
        ResizableFrame(
            app, ResizableFrame.TOP or ResizableFrame.BOTTOM,
            band = dpx(app, 12), corner = dpx(app, 12), outlineColor = accent,
            listener = object : ResizableFrame.Listener {
                override fun onResizeStart() {
                    dockStartInk = pad?.ink?.height ?: 0
                }

                override fun onResize(dLeft: Int, dTop: Int, dRight: Int, dBottom: Int) {
                    val p = pad ?: return
                    val maxH = (p.resources.displayMetrics.heightPixels * 0.7f).roundToInt()
                    p.setInkHeight((dockStartInk - dTop + dBottom).coerceIn(dpx(p.context, 120), maxH))
                }

                override fun onResizeEnd() {
                    val p = pad ?: return
                    sp(p.context).edit().putInt("ink_dock_h", p.ink.height).apply()
                }
            },
        ).also { dockHolder = it }
    }

    private fun pad(ctx: Context): InkPad = pad ?: InkPad(ctx.applicationContext).also { pad = it }

    private fun detach(v: View) {
        val parent = v.parent as? ViewGroup ?: return
        parent.removeView(v)
        // 채팅 안 틀에서 빼면 틀도 채팅에서 빼고 자리를 숨긴다
        if (parent === dockHolder) {
            (parent.parent as? ViewGroup)?.let { slot ->
                slot.removeView(parent)
                if (slot.tag == SLOT_TAG) slot.visibility = View.GONE
            }
        } else if (parent.tag == SLOT_TAG) {
            parent.visibility = View.GONE
        }
    }

    /** 채팅의 ✍ 버튼: 떠 있으면 닫고, 이 채팅에 붙어 있으면 닫고, 아니면 이 채팅에 붙인다. */
    /**
     * growsUp: 채팅 화면(분할 화면·연습장)에서는 손글씨 칸 아래가 입력칸에 붙어 있어 위쪽 변이 움직이고,
     * 떠 있는 카드에서는 카드가 아래로 늘어나 아래쪽 변이 움직인다. 실제로 손가락을 따라 움직이는 변만 잡을 수 있게 한다.
     */
    fun toggleIn(slot: FrameLayout, inkHeight: Int, growsUp: Boolean) {
        val p = pad(slot.context)
        when {
            floating -> close()
            p.parent === dockHolder && dockHolder?.parent === slot -> close()
            else -> showIn(slot, inkHeight, growsUp)
        }
    }

    fun showIn(slot: FrameLayout, inkHeight: Int, growsUp: Boolean) {
        val p = pad(slot.context)
        slot.tag = SLOT_TAG
        detach(p)
        p.setFloating(false)
        p.setInkHeight(sp(slot.context).getInt("ink_dock_h", inkHeight))
        val holder = dockHolder(slot.context)
        holder.edges = if (growsUp) ResizableFrame.TOP else ResizableFrame.BOTTOM
        (holder.parent as? ViewGroup)?.removeView(holder)
        holder.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        slot.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        slot.visibility = View.VISIBLE
        p.prepare()
    }

    fun close() {
        val p = pad ?: return
        if (floating) removeWindow()
        detach(p)
    }

    fun toggleFloat(ctx: Context) {
        if (floating) dock() else float(ctx)
    }

    /** 화면 위에 떠 있는 창으로 (펜스틱 화면 캡처 권한이 켜져 있어야 함) */
    private fun float(ctx: Context) {
        val svc = CaptureService.instance
        if (svc == null) {
            Toast.makeText(ctx, "띄우려면 설정에서 화면 캡처 권한(접근성)을 먼저 켜 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val p = pad(ctx)
        detach(p)
        val wm = svc.getSystemService(WindowManager::class.java)
        val d = svc.resources.displayMetrics.density
        val screen = wm.maximumWindowMetrics.bounds
        // 상태 표시줄·내비게이션 바를 뺀 실제로 쓸 수 있는 영역 안에 창 전체가 들어오게 한다
        val usable = ResizableFrame.usableBounds(wm)
        val shadow = (12 * d).roundToInt()
        val minW = (300 * d).roundToInt()
        val minInk = (120 * d).roundToInt()
        val padW = sp(ctx).getInt("ink_float_w", (440 * d).roundToInt() - shadow * 2)
            .coerceIn(minW, maxOf(minW, usable.width()))
        val frameW = padW + shadow * 2
        p.setFloating(true)
        // 쓰는 칸 말고 나머지(제목줄·후보·버튼)가 차지하는 높이를 재서, 저장된 높이가 화면을 넘으면 줄인다
        p.setInkHeight(sp(ctx).getInt("ink_float_h", (260 * d).roundToInt()))
        p.measure(
            View.MeasureSpec.makeMeasureSpec(padW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val chrome0 = p.measuredHeight - p.ink.layoutParams.height
        val inkH0 = p.ink.layoutParams.height.coerceIn(minInk, maxOf(minInk, usable.height() - chrome0))
        p.setInkHeight(inkH0)
        val frameH0 = inkH0 + chrome0 + shadow * 2
        lateinit var params: WindowManager.LayoutParams
        lateinit var frame: ResizableFrame
        var start = ResizableFrame.Box(0, 0, 0, 0)
        var bounds = usable
        val chrome = chrome0
        // 가장자리·모서리 어디든 잡고 끌면 크기 조절 (너비 = 창 너비, 높이 = 쓰는 칸 높이)
        frame = ResizableFrame(
            svc, ResizableFrame.ALL,
            band = shadow + (8 * d).roundToInt(), corner = shadow + (14 * d).roundToInt(),
            outlineColor = ChatView.Palette.of(svc).accent,
            listener = object : ResizableFrame.Listener {
                override fun onResizeStart() {
                    // 화면을 돌렸을 수도 있으니 잡을 때마다 다시 잰다
                    bounds = ResizableFrame.usableBounds(wm)
                    // 쓰기 칸 외 높이는 띄울 때 잘리지 않은 원래 크기로 잰 값을 쓴다
                    // (지금 창이 화면에 잘려 있으면 화면에서 잰 값이 틀리므로)
                    start = ResizableFrame.Box(params.x, params.y, p.width, p.ink.height)
                }

                override fun onResize(dLeft: Int, dTop: Int, dRight: Int, dBottom: Int) {
                    val b = ResizableFrame.resizedWithin(
                        start, dLeft, dTop, dRight, dBottom,
                        minW = minW, minH = minInk,
                        extraW = shadow * 2, extraH = chrome + shadow * 2,
                        bounds = bounds, slack = shadow,
                    )
                    val lpPad = p.layoutParams
                    if (lpPad != null && lpPad.width != b.w) {
                        lpPad.width = b.w
                        p.layoutParams = lpPad
                    }
                    p.setInkHeight(b.h)
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
                    sp(svc).edit().putInt("ink_float_w", p.width).putInt("ink_float_h", p.ink.height).apply()
                }
            },
        ).apply {
            setPadding(shadow, shadow, shadow, shadow)
            addView(p, FrameLayout.LayoutParams(frameW - shadow * 2, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            x = (if (lastX >= 0) lastX else usable.right - frameW - (16 * d).roundToInt())
                .coerceIn(usable.left - shadow, maxOf(usable.left - shadow, usable.right + shadow - frameW))
            y = (if (lastY >= 0) lastY else usable.top + (usable.height() * 0.25f).roundToInt())
                .coerceIn(usable.top - shadow, maxOf(usable.top - shadow, usable.bottom + shadow - frameH0))
            windowAnimations = android.R.style.Animation_Toast
        }
        // 제목줄을 잡고 끌면 창이 움직인다
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        p.header.setOnTouchListener { _, e ->
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
        DebugLog.add("손글씨 칸 띄움")
    }

    /** 떠 있는 창을 닫고 지금 보이는 채팅 안으로 돌려놓는다. */
    private fun dock() {
        removeWindow()
        val chat = ChatView.active
        if (chat != null) chat.showInkDocked() else pad?.let { detach(it) }
    }

    private fun removeWindow() {
        val w = window ?: return
        val p = pad
        p?.header?.setOnTouchListener(null)
        try {
            CaptureService.instance?.getSystemService(WindowManager::class.java)?.removeViewImmediate(w)
        } catch (_: Exception) {
        }
        w.removeAllViews()
        p?.setFloating(false)
        window = null
        lp = null
        floating = false
    }

    /** 캡처하는 동안 떠 있는 창을 감춘다 (스크린샷에 찍히지 않게). */
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

    /** 서비스가 꺼질 때 */
    fun onServiceGone() {
        if (floating) removeWindow()
    }

    private const val SLOT_TAG = "ink-slot"
}
