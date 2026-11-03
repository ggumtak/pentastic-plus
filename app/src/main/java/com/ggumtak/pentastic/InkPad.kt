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

    /** 종이 느낌 색 (채팅의 흰/검정 카드와 구분) */
    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val paper = if (night) 0xFF29261F.toInt() else 0xFFFBF7EC.toInt()
    private val paperStroke = if (night) 0xFF4A4436.toInt() else 0xFFE3D9BF.toInt()
    private val inkColor = if (night) 0xFFF4EFE2.toInt() else 0xFF1E1B16.toInt()
    private val text = if (night) 0xFFEDE7D8.toInt() else 0xFF3A3427.toInt()
    private val muted = if (night) 0xFFA9A08B.toInt() else 0xFF8A806A.toInt()
    private val chipBg = if (night) 0xFF3A362C.toInt() else 0xFFF1EAD6.toInt()
    private val accent = if (night) 0xFFE0B860.toInt() else 0xFF9A6B12.toInt()

    val header: LinearLayout
    val ink: InkView
    private val status: TextView
    private val candRow: LinearLayout
    private val floatBtn: TextView

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setColor(paper)
            setStroke(dp(1), paperStroke)
        }

        // 제목줄 (떠 있을 때는 여기를 잡고 끌어서 옮긴다)
        header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(36)
        }
        header.addView(TextView(ctx).apply {
            text = "✍ 손글씨"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(this@InkPad.text)
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
    private var window: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lastX = -1
    private var lastY = -1

    var floating = false
        private set

    private fun pad(ctx: Context): InkPad = pad ?: InkPad(ctx.applicationContext).also { pad = it }

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.let { parent ->
            parent.removeView(v)
            if (parent.tag == SLOT_TAG) parent.visibility = View.GONE
        }
    }

    /** 채팅의 ✍ 버튼: 떠 있으면 닫고, 이 채팅에 붙어 있으면 닫고, 아니면 이 채팅에 붙인다. */
    fun toggleIn(slot: FrameLayout, inkHeight: Int) {
        val p = pad(slot.context)
        when {
            floating -> close()
            p.parent === slot -> close()
            else -> showIn(slot, inkHeight)
        }
    }

    fun showIn(slot: FrameLayout, inkHeight: Int) {
        val p = pad(slot.context)
        slot.tag = SLOT_TAG
        detach(p)
        p.setFloating(false)
        p.setInkHeight(inkHeight)
        slot.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val shadow = (12 * d).roundToInt()
        val width = minOf((440 * d).roundToInt(), screen.width() - (24 * d).roundToInt())
        p.setFloating(true)
        p.setInkHeight((260 * d).roundToInt())
        val frame = FrameLayout(svc).apply {
            setPadding(shadow, shadow, shadow, shadow)
            addView(p, FrameLayout.LayoutParams(width - shadow * 2, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (lastX >= 0) lastX else screen.right - width - (16 * d).roundToInt()
            y = if (lastY >= 0) lastY else (screen.height() * 0.3f).roundToInt()
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
