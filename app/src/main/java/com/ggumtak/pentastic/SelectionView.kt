package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 멈춘 화면(캡처 이미지)을 그대로 보여주고, 펜/손가락으로 드래그한 사각형을 고르게 한다.
 * 짧게 탭하거나 뒤로 가기를 누르면 취소.
 */
@SuppressLint("ViewConstructor")
class SelectionView(
    context: Context,
    private val shot: Bitmap,
    private val onSelected: (bitmapRect: Rect, screenRect: Rect) -> Unit,
    private val onCancel: (reason: String) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val loc = IntArray(2)

    /** 캡처 이미지 px / 화면 px */
    private val scale: Float = run {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (bounds.width() > 0) shot.width.toFloat() / bounds.width() else 1f
    }

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var finished = false
    private var softCopy: Bitmap? = null

    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val lightDimPaint = Paint().apply { color = Color.argb(40, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 20, 20) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        canvas.scale(1f / scale, 1f / scale)
        // 하드웨어 이미지는 하드웨어 가속 화면에서만 그릴 수 있다. 아니면 한 번만 복사해서 쓴다.
        val bmp = if (shot.config == Bitmap.Config.HARDWARE && !canvas.isHardwareAccelerated) {
            softCopy ?: shot.copy(Bitmap.Config.ARGB_8888, false).also { softCopy = it }
        } else shot
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.restore()

        val w = width.toFloat()
        val h = height.toFloat()
        if (dragging) {
            val r = selection()
            canvas.drawRect(0f, 0f, w, r.top, dimPaint)
            canvas.drawRect(0f, r.bottom, w, h, dimPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dimPaint)
            canvas.drawRect(r.right, r.top, w, r.bottom, dimPaint)
            canvas.drawRect(r, borderPaint)
        } else {
            canvas.drawRect(0f, 0f, w, h, lightDimPaint)
            val text = "질문할 부분을 드래그하세요  ·  짧게 탭하면 취소"
            val tw = hintPaint.measureText(text)
            val cx = w / 2
            val top = 48f * density
            val pad = 14f * density
            canvas.drawRoundRect(
                RectF(cx - tw / 2 - pad, top, cx + tw / 2 + pad, top + 40f * density),
                20f * density, 20f * density, hintBgPaint
            )
            canvas.drawText(text, cx, top + 26f * density, hintPaint)
        }
    }

    private fun selection() = RectF(
        min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY)
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finished) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y; curX = e.x; curY = e.y
                dragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                curX = e.x; curY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                curX = e.x; curY = e.y
                finish()
            }
            MotionEvent.ACTION_CANCEL -> {
                // S펜 버튼을 누른 채 끌면 시스템이 중간에 터치를 취소할 수 있다.
                // 이미 충분히 끌었으면 그 영역으로 확정하고, 아니면 다시 끌 수 있게 둔다.
                DebugLog.add("선택 중 터치 취소됨 (시스템)")
                val r = selection()
                if (dragging && r.width() >= 16f * density && r.height() >= 16f * density) {
                    finish()
                } else {
                    dragging = false
                    invalidate()
                }
            }
        }
        return true
    }

    private fun finish() {
        val r = selection()
        val minSize = 16f * density
        if (abs(r.width()) < minSize || abs(r.height()) < minSize) {
            finished = true
            onCancel("짧게 탭함")
            return
        }
        // 화면 좌표 -> 캡처 이미지 좌표
        getLocationOnScreen(loc)
        val left = ((r.left + loc[0]) * scale).roundToInt().coerceIn(0, shot.width - 1)
        val top = ((r.top + loc[1]) * scale).roundToInt().coerceIn(0, shot.height - 1)
        val right = ((r.right + loc[0]) * scale).roundToInt().coerceIn(left + 1, shot.width)
        val bottom = ((r.bottom + loc[1]) * scale).roundToInt().coerceIn(top + 1, shot.height)
        val screenRect = Rect(
            (r.left + loc[0]).roundToInt(), (r.top + loc[1]).roundToInt(),
            (r.right + loc[0]).roundToInt(), (r.bottom + loc[1]).roundToInt(),
        )
        finished = true
        onSelected(Rect(left, top, right, bottom), screenRect)
    }

    /** 선택 없이 치울 때 캡처 이미지와 복사본을 놓아 준다 (선택을 마친 경우엔 부르지 않는다) */
    fun dispose() {
        softCopy?.recycle()
        softCopy = null
        shot.recycle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !finished) {
                finished = true
                onCancel("뒤로 가기")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
