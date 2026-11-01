package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 한자를 쓰는 칸. 가운데 십자 안내선, 따라 쓰기용 흐린 글자(ghost)를 그릴 수 있다.
 * 한 획을 쓸 때마다 잠시 뒤 onStrokesChanged가 불린다 (필기 인식용).
 */
@SuppressLint("ViewConstructor")
class InkView(
    ctx: Context,
    private val lineColor: Int,
    private val inkColor: Int,
    private val guideColor: Int,
    /** 정사각형으로 그릴지 (연습장) */
    private val square: Boolean = false,
) : View(ctx) {

    class Pt(val x: Float, val y: Float, val t: Long)

    private val density = resources.displayMetrics.density
    private val strokes = mutableListOf<MutableList<Pt>>()
    private val paths = mutableListOf<Path>()
    private var current: MutableList<Pt>? = null
    private var currentPath: Path? = null
    private val main = Handler(Looper.getMainLooper())
    private val notify = Runnable { onStrokesChanged?.invoke() }

    var onStrokesChanged: (() -> Unit)? = null

    /** 따라 쓰기용 흐린 글자 (null이면 안 그림) */
    var ghost: String? = null
        set(v) {
            field = v
            invalidate()
        }

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        style = Paint.Style.STROKE
        strokeWidth = (if (square) 7f else 5f) * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = guideColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 6 * density), 0f)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = guideColor
        textAlign = Paint.Align.CENTER
        textLocale = Locale.JAPAN
    }

    val strokeData: List<List<Pt>> get() = strokes
    val isEmpty get() = strokes.isEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!square) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = if (h == 0) w else min(w, h)
        setMeasuredDimension(s, s)
    }

    private fun box(): RectF {
        val pad = 4 * density
        return RectF(pad, pad, width - pad, height - pad)
    }

    override fun onDraw(canvas: Canvas) {
        val b = box()
        // 쓰는 칸: 정사각 안내 영역 (가로로 긴 칸이면 가운데 정사각 영역)
        val side = min(b.width(), b.height())
        val g = RectF(b.centerX() - side / 2, b.centerY() - side / 2, b.centerX() + side / 2, b.centerY() + side / 2)
        canvas.drawRoundRect(b, 12 * density, 12 * density, borderPaint)
        canvas.drawLine(g.centerX(), g.top, g.centerX(), g.bottom, guidePaint)
        canvas.drawLine(g.left, g.centerY(), g.right, g.centerY(), guidePaint)
        if (square) {
            canvas.drawLine(g.left, g.top, g.right, g.bottom, guidePaint)
            canvas.drawLine(g.right, g.top, g.left, g.bottom, guidePaint)
        }
        ghost?.takeIf { it.isNotBlank() }?.let { t ->
            ghostPaint.textSize = side * 0.82f / max(1, t.length)
            val fm = ghostPaint.fontMetrics
            canvas.drawText(t, g.centerX(), g.centerY() - (fm.ascent + fm.descent) / 2, ghostPaint)
        }
        paths.forEach { canvas.drawPath(it, inkPaint) }
        currentPath?.let { canvas.drawPath(it, inkPaint) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(notify)
                current = mutableListOf(Pt(e.x, e.y, SystemClock.uptimeMillis()))
                currentPath = Path().apply { moveTo(e.x, e.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = current ?: return true
                val path = currentPath ?: return true
                for (i in 0 until e.historySize) {
                    pts += Pt(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                    path.lineTo(e.getHistoricalX(i), e.getHistoricalY(i))
                }
                pts += Pt(e.x, e.y, e.eventTime)
                path.lineTo(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                val pts = current ?: return true
                val path = currentPath ?: return true
                pts += Pt(e.x, e.y, e.eventTime)
                path.lineTo(e.x + 0.1f, e.y + 0.1f) // 점 하나만 찍어도 보이게
                strokes += pts
                paths += path
                current = null
                currentPath = null
                main.postDelayed(notify, 250)
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                currentPath = null
            }
        }
        invalidate()
        return true
    }

    fun undo() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        paths.removeAt(paths.lastIndex)
        invalidate()
        onStrokesChanged?.invoke()
    }

    fun clear() {
        strokes.clear()
        paths.clear()
        invalidate()
        onStrokesChanged?.invoke()
    }

    /** 쓴 글씨만 흰 바탕 정사각 이미지로 (AI에게 보여줄 때). 비어 있으면 null. */
    fun toPng(maxSide: Int = 512): Pair<Bitmap, ByteArray>? {
        if (strokes.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var bt = -Float.MAX_VALUE
        strokes.flatten().forEach { p ->
            l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); bt = max(bt, p.y)
        }
        val pad = 24 * density
        val side = max(r - l, bt - t) + pad * 2
        val cx = (l + r) / 2
        val cy = (t + bt) / 2
        val scale = min(1f, maxSide / side)
        val size = (side * scale).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        c.translate(side / 2 - cx, side / 2 - cy)
        val p = Paint(inkPaint).apply { color = Color.BLACK }
        paths.forEach { c.drawPath(it, p) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return bmp to out.toByteArray()
    }
}
