package com.ggumtak.pentastic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Anki 위에 떠 있는 작은 질문 카드 (창 관리만 한다. 화면 내용은 ChatView, 대화는 ChatSession).
 * - 처음엔 고른 영역 바로 아래(공간이 없으면 위/옆)에 뜬다. 윗부분을 잡고 끌어서 옮길 수 있다.
 * - 다시 캡처하는 동안엔 잠깐 숨었다가 같은 자리에 다시 나타난다.
 * - 카드 바깥 터치는 그대로 아래 앱(Anki)으로 전달된다.
 */
@SuppressLint("ClickableViewAccessibility")
class FloatingPanel(
    private val ctx: Context,
    private val session: ChatSession,
    private val selection: Rect,
    private val onRecapture: () -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenDocked: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val screen: Rect = wm.maximumWindowMetrics.bounds
    private val margin = dp(12)
    private val shadow = dp(14) // 그림자가 잘리지 않게 카드 바깥에 두는 여백

    private lateinit var window: PanelFrame
    private lateinit var chat: ChatView

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
    private var closed = false
    private var hidden = false
    private var imeMode = false
    private var yBeforeIme: Int? = null

    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dpf(v: Float) = v * density

    // ------------------------------------------------------------------ 표시 / 숨기기 / 닫기

    fun show() {
        window = PanelFrame(ctx).apply {
            setPadding(shadow, shadow, shadow, shadow)
            onBack = { if (imeMode) setImeMode(false) else close(resetSession = true) }
            onStylusButton = { onRecapture() }
        }
        chat = ChatView(
            ctx, session, ChatView.Mode.CARD,
            maxAnswerHeight = min(dp(480), (screen.height() * 0.55f).roundToInt()),
            actions = object : ChatView.Actions {
                override fun recapture() = onRecapture()
                override fun openSettings() {
                    close(resetSession = false)
                    onOpenSettings()
                }
                override fun close() = this@FloatingPanel.close(resetSession = true)
                override fun openDocked() {
                    close(resetSession = false)
                    onOpenDocked()
                }
                override fun openPractice() {
                    close(resetSession = false)
                    PracticeActivity.open(ctx)
                }
                override fun inputTouched() {
                    if (!imeMode) setImeMode(true)
                }
                override fun inputDone() = setImeMode(false)
            },
        )
        window.addView(chat, FrameLayout.LayoutParams(panelWidth() - shadow * 2, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dragger = DragListener()
        chat.dragHandles.forEach { it.setOnTouchListener(dragger) }

        placeInitially()
        wm.addView(window, lp)
        window.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) keepOnScreen()
        }
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

    /** resetSession=true면 대화도 끝낸다 (✕). 분할 화면으로 옮길 땐 대화를 남긴다. */
    fun close(resetSession: Boolean) {
        if (closed) return
        closed = true
        hideIme()
        try {
            wm.removeViewImmediate(window)
        } catch (_: Exception) {
        }
        if (resetSession) session.reset()
        onClosed()
    }

    // ------------------------------------------------------------------ 위치

    private fun panelWidth() = min(dp(480), screen.width() - margin * 2)

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
            chat.input.requestFocus()
            main.postDelayed({
                ctx.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(chat.input, InputMethodManager.SHOW_IMPLICIT)
            }, 120)
        } else {
            hideIme()
        }
    }

    private fun hideIme() {
        if (!::chat.isInitialized) return
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(chat.input.windowToken, 0)
        chat.input.clearFocus()
    }

    // ------------------------------------------------------------------ 바깥 틀

    /**
     * 카드의 바깥 틀.
     * - 뒤로 가기: 키보드 닫기 / 카드 닫기 (창이 포커스를 가질 때만 온다)
     * - 카드 위에서 S펜 버튼을 누르면(펜을 띄운 채 또는 닿은 채) 다시 캡처
     */
    class PanelFrame(ctx: Context) : FrameLayout(ctx) {
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
            if (onStylusButton != null && isStylusButton(e)) return fire()
            return super.dispatchGenericMotionEvent(e)
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (onStylusButton != null && e.actionMasked == MotionEvent.ACTION_DOWN && isStylusButton(e)) return fire()
            return super.dispatchTouchEvent(e)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val back = onBack
            if (back != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }
}
