package com.ggumtak.pentastic

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 화면 캡처 → 영역 선택 → 떠 있는 질문 카드까지 모두 이 서비스 안에서 처리한다.
 * - takeScreenshot()을 쓰므로 매번 "화면 녹화 허용" 창이 뜨지 않는다.
 * - 서비스는 계속 살아 있으므로 SDK 클래스와 API 연결을 미리 데워 두어 첫 답이 빨리 온다.
 */
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var selector: SelectionView? = null
    private var panel: FloatingPanel? = null
    private var pending = false
    private var retried = false
    private var pendingSince = 0L
    private var selectorSince = 0L


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        thread(name = "warm-up") { ClaudeTutor.warmUpClasses() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        instance = null
        removeSelector()
        panel?.close(resetSession = false)
    }

    // ------------------------------------------------------------------ 캡처

    fun requestCapture(delayMs: Long) {
        // 이전 캡처가 어떤 이유로든 끝나지 않고 멈춰 있으면(3초 이상) 정리하고 새로 시작한다.
        val now = System.currentTimeMillis()
        DebugLog.add("캡처 요청 (대기 ${delayMs}ms, 카드=${panel != null}, AI챗 보임=${ChatActivity.visible})")
        if (pending && now - pendingSince < 3000) {
            DebugLog.add("→ 무시: 이전 캡처 진행 중")
            return
        }
        if (selector != null) {
            if (now - selectorSince < 1500) {
                DebugLog.add("→ 무시: 선택 화면이 이미 떠 있음")
                return
            }
            removeSelector()
        }
        pendingSince = now
        // 떠 있는 카드는 닫지 않고 잠시 감춘다. 새 캡처는 같은 카드(같은 자리, 같은 대화)에 붙는다.
        panel?.hideForCapture()
        pending = true
        retried = false
        handler.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    pending = false
                    val hw = result.hardwareBuffer
                    val shot = try {
                        Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hw.close()
                    }
                    DebugLog.add("스크린샷 성공 (${shot?.width}x${shot?.height})")
                    if (shot == null) {
                        panel?.reveal()
                        toast("화면 캡처에 실패했습니다.")
                        return
                    }
                    showSelector(fixOrientation(shot))
                }

                override fun onFailure(errorCode: Int) {
                    DebugLog.add("스크린샷 실패 코드 $errorCode (재시도함=$retried)")
                    // 캡처는 1초에 한 번만 허용된다. 너무 빨리 다시 누른 경우 한 번 자동 재시도.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !retried) {
                        retried = true
                        handler.postDelayed({ capture() }, 1000)
                        return
                    }
                    pending = false
                    panel?.reveal()
                    toast("화면 캡처에 실패했습니다 (코드 $errorCode).")
                }
            })
        } catch (e: Exception) {
            DebugLog.add("스크린샷 예외: ${e.javaClass.simpleName} ${e.message}")
            pending = false
            panel?.reveal()
            toast("화면 캡처를 시작하지 못했습니다: ${e.message}")
        }
    }

    /** 캡처 이미지의 가로/세로가 현재 화면과 다르면 화면 회전만큼 돌려 맞춘다. */
    private fun fixOrientation(shot: Bitmap): Bitmap {
        val wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        val screenLandscape = b.width() > b.height()
        val shotLandscape = shot.width > shot.height
        if (screenLandscape == shotLandscape || shot.width == shot.height) return shot
        @Suppress("DEPRECATION")
        val degrees = when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> -90f
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f
            else -> 90f
        }
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(shot, 0, 0, shot.width, shot.height, m, true)
        shot.recycle()
        return rotated
    }

    // ------------------------------------------------------------------ 영역 선택

    private fun showSelector(shot: Bitmap) {
        removeSelector()
        val wm = getSystemService(WindowManager::class.java)
        val view = SelectionView(
            this,
            shot,
            onSelected = { bitmapRect, screenRect -> onSelected(shot, bitmapRect, screenRect) },
            onCancel = { reason ->
                DebugLog.add("선택 취소: $reason")
                removeSelector()
                shot.recycle()
                panel?.reveal()
            },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, lp)
        DebugLog.add("선택 화면 표시")
        selector = view
        selectorSince = System.currentTimeMillis()
        view.requestFocus()

        // 고르는 동안 API 서버 연결을 미리 열어 둔다 (토큰을 쓰지 않는 요청)
        thread(name = "prewarm") { ApiClient.get(this)?.let { ClaudeTutor.prewarmConnection(it) } }
    }

    private fun removeSelector() {
        val v = selector ?: return
        selector = null
        try {
            getSystemService(WindowManager::class.java).removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }

    private fun onSelected(shot: Bitmap, r: Rect, screenRect: Rect) {
        DebugLog.add("선택 완료 ${r.width()}x${r.height()}")
        removeSelector()
        val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
        shot.recycle()
        // Claude는 긴 변 1568px이면 충분히 읽는다. 더 크면 줄여서 전송량과 시간을 아낀다.
        val longest = max(crop.width, crop.height)
        val img = if (longest > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                crop, (crop.width * s).roundToInt(), (crop.height * s).roundToInt(), true
            ).also { crop.recycle() }
        } else crop

        // PNG 변환은 카드를 띄운 뒤 뒤에서 한다. 질문을 누르는 시점엔 보통 이미 끝나 있다.
        val png: Future<ByteArray> = worker.submit<ByteArray> {
            ByteArrayOutputStream().use { out ->
                img.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

        val session = ChatSession.get(this)
        val existing = panel
        when {
            // 분할 화면 AI 챗이 보이면 그쪽으로 바로 보낸다
            ChatActivity.visible -> {
                DebugLog.add("→ AI 챗에 붙임")
                existing?.close(resetSession = false)
                session.attach(img, { png.get() })
            }
            // 떠 있는 카드가 있으면 같은 카드(같은 자리, 같은 대화)에 붙인다
            existing != null -> {
                DebugLog.add("→ 떠 있는 카드에 붙임")
                session.attach(img, { png.get() })
                existing.reveal()
            }
            // 아무것도 없으면 새 대화로 새 카드를 띄운다
            else -> {
                DebugLog.add("→ 새 카드")
                session.reset()
                session.attach(img, { png.get() })
                val p = FloatingPanel(
                    ctx = this,
                    session = session,
                    selection = screenRect,
                    onRecapture = { requestCapture(RECAPTURE_DELAY_MS) },
                    onOpenSettings = {
                        startActivity(
                            Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onOpenDocked = { ChatActivity.open(this) },
                    onClosed = { panel = null },
                )
                panel = p
                p.show()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set

        /** 에어 액션으로 실행된 투명 화면이 닫힐 시간 */
        const val TRIGGER_DELAY_MS = 180L

        /** 카드의 [캡처] 버튼 / 펜 버튼: 카드가 화면에서 사라질 한 프레임만 기다린다 */
        const val RECAPTURE_DELAY_MS = 120L

        private const val MAX_SIDE = 1568
    }
}
