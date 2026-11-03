package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 보통 앱 창처럼 가장자리·모서리를 잡고 끌어서 크기를 바꾸는 틀.
 * 눈에 보이는 손잡이는 없다. 틀의 바깥 [band] 안쪽을 누르면 그 방향(변/모서리)으로 크기 조절이 시작되고,
 * 그 밖을 누르면 안의 내용(버튼, 쓰기 칸 등)이 평소처럼 받는다.
 * 실제 크기·위치 변경은 [listener]가 한다 (창이 떠 있는지, 채팅 안에 붙어 있는지에 따라 다르므로).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ResizableFrame(
    ctx: Context,
    /** 조절할 수 있는 변 (LEFT|TOP|RIGHT|BOTTOM 조합) */
    private val edges: Int,
    /** 가장자리에서 이 거리 안을 누르면 크기 조절 */
    private val band: Int,
    /** 모서리는 이 거리 안이면 두 방향 동시 조절 (변보다 넓게 잡아 쉽게) */
    private val corner: Int,
    outlineColor: Int,
    private val listener: Listener,
) : FrameLayout(ctx) {

    interface Listener {
        fun onResizeStart()

        /** 누른 뒤 각 변이 움직인 양(px). 잡지 않은 변은 0. */
        fun onResize(dLeft: Int, dTop: Int, dRight: Int, dBottom: Int)
        fun onResizeEnd()
    }

    private var active = 0
    private var downX = 0f
    private var downY = 0f
    private val density = resources.displayMetrics.density

    // 크기를 바꾸는 동안만 내용 둘레에 파란 테두리 (지금 조절 중이라는 표시)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = outlineColor
    }

    init {
        setWillNotDraw(false)
    }

    private fun hit(x: Float, y: Float): Int {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return 0
        val inCornerX = x < corner || x > w - corner
        val inCornerY = y < corner || y > h - corner
        var e = 0
        if (x < band || (x < corner && inCornerY)) e = e or LEFT
        if (x > w - band || (x > w - corner && inCornerY)) e = e or RIGHT
        if (y < band || (y < corner && inCornerX)) e = e or TOP
        if (y > h - band || (y > h - corner && inCornerX)) e = e or BOTTOM
        return e and edges
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val e = hit(ev.x, ev.y)
            if (e != 0) {
                active = e
                downX = ev.rawX
                downY = ev.rawY
                listener.onResizeStart()
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (active == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // 화면 기준 좌표(raw)로 계산해야 창이 움직여도 흔들리지 않는다
                val dx = (ev.rawX - downX).roundToInt()
                val dy = (ev.rawY - downY).roundToInt()
                listener.onResize(
                    if (active and LEFT != 0) dx else 0,
                    if (active and TOP != 0) dy else 0,
                    if (active and RIGHT != 0) dx else 0,
                    if (active and BOTTOM != 0) dy else 0,
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = 0
                listener.onResizeEnd()
                invalidate()
            }
        }
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (active == 0 || childCount == 0) return
        val c = getChildAt(0)
        val r = RectF(c.left.toFloat(), c.top.toFloat(), c.right.toFloat(), c.bottom.toFloat())
        val half = outline.strokeWidth / 2
        r.inset(half, half)
        canvas.drawRoundRect(r, 18f * density, 18f * density, outline)
    }

    /** 크기 조절 계산: 시작 상태 + 변 이동량 → 새 위치와 크기 (최소·최대 안에서, 반대쪽 변은 고정) */
    class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    companion object {
        const val LEFT = 1
        const val TOP = 2
        const val RIGHT = 4
        const val BOTTOM = 8
        const val ALL = LEFT or TOP or RIGHT or BOTTOM

        fun resized(
            start: Box, dl: Int, dt: Int, dr: Int, db: Int,
            minW: Int, maxW: Int, minH: Int, maxH: Int,
        ): Box {
            val w = (start.w - dl + dr).coerceIn(minW, maxOf(minW, maxW))
            val h = (start.h - dt + db).coerceIn(minH, maxOf(minH, maxH))
            // 왼쪽/위 변을 잡았으면 오른쪽/아래 변이 제자리에 있도록 위치를 옮긴다
            val x = if (dl != 0) start.x + (start.w - w) else start.x
            val y = if (dt != 0) start.y + (start.h - h) else start.y
            return Box(x, y, w, h)
        }
    }
}
