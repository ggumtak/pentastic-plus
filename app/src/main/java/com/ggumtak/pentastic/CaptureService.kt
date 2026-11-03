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
    private var shotBuffer: android.hardware.HardwareBuffer? = null

    private fun releaseShot(shot: Bitmap) {
        shot.recycle()
        shotBuffer?.close()
        shotBuffer = null
    }
    private var selectorSince = 0L


    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        thread(name = "warm-up") { ClaudeTutor.warmUpClasses() }
        // 필기 인식 모델을 이미 받아 두었다면 미리 열어 둔다 (✍를 누르면 바로 인식)
        Handwriting.prepareIfDownloaded()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        // 이미 넘긴 이미지 변환은 끝까지 하고 스레드를 닫는다
        worker.shutdown()
        super.onDestroy()
    }

    private fun cleanup() {
        // 떠 있는 창부터 거둔다 (서비스가 아직 살아 있을 때 지워야 창이 남지 않는다)
        InkDock.onServiceGone()
        PracticeDock.onServiceGone()
        // 예약된 캡처·재시도가 꺼진 서비스에서 창을 띄우려다 앱이 죽지 않게 취소한다
        handler.removeCallbacksAndMessages(null)
        pending = false
        discardSelector()
        panel?.close(resetSession = false)
        if (instance === this) instance = null
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
            discardSelector()
        }
        pendingSince = now
        // 떠 있는 카드는 닫지 않고 잠시 감춘다. 새 캡처는 같은 카드(같은 자리, 같은 대화)에 붙는다.
        panel?.hideForCapture()
        InkDock.setCaptureHidden(true)
        PracticeDock.setCaptureHidden(true)
        pending = true
        retried = false
        handler.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
        if (instance !== this) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    pending = false
                    val hw = result.hardwareBuffer
                    if (instance !== this@CaptureService) {
                        hw.close()
                        return
                    }
                    // 화면 전체를 복사하지 않고 그대로 보여준다 (멈춘 화면이 더 빨리 뜬다).
                    // 복사는 나중에 고른 부분만 한다.
                    val shot = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    if (shot == null) hw.close() else {
                        shotBuffer?.close()
                        shotBuffer = hw
                    }
                    DebugLog.add("스크린샷 성공 (${shot?.width}x${shot?.height}, 요청 후 ${System.currentTimeMillis() - pendingSince}ms)")
                    if (shot == null) {
                        panel?.reveal()
                InkDock.setCaptureHidden(false)
                PracticeDock.setCaptureHidden(false)
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
                InkDock.setCaptureHidden(false)
                PracticeDock.setCaptureHidden(false)
                    toast("화면 캡처에 실패했습니다 (코드 $errorCode).")
                }
            })
        } catch (e: Exception) {
            DebugLog.add("스크린샷 예외: ${e.javaClass.simpleName} ${e.message}")
            pending = false
            panel?.reveal()
                InkDock.setCaptureHidden(false)
                PracticeDock.setCaptureHidden(false)
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
                discardSelector()
                panel?.reveal()
                InkDock.setCaptureHidden(false)
                PracticeDock.setCaptureHidden(false)
            },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(view, lp)
        DebugLog.add("선택 화면 표시 (요청 후 ${System.currentTimeMillis() - pendingSince}ms)")
        // 캡처가 시작됐다는 걸 손으로 느낄 수 있게 짧은 진동
        view.post { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM) }
        selector = view
        selectorSince = System.currentTimeMillis()
        view.requestFocus()

        // 고르는 동안 API 서버 연결을 미리 열어 둔다 (토큰을 쓰지 않는 요청)
        thread(name = "prewarm") { ApiClient.get(this)?.let { ClaudeTutor.prewarmConnection(it) } }
    }

    /** 고르지 않고 선택 화면을 치울 때: 캡처 이미지(와 그 복사본)도 바로 놓아 준다 */
    private fun discardSelector() {
        val v = selector ?: return
        removeSelector()
        v.dispose()
        shotBuffer?.close()
        shotBuffer = null
    }

    private fun removeSelector() {
        InkDock.setCaptureHidden(false)
                PracticeDock.setCaptureHidden(false)
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
        val cut = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
        // 하드웨어 이미지는 저장·표시용으로 바로 쓸 수 없어서, 고른 부분만 일반 이미지로 복사
        val crop = if (cut.config == Bitmap.Config.HARDWARE) {
            cut.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== cut) cut.recycle() }
        } else cut
        if (crop !== shot) releaseShot(shot)
        // Claude는 긴 변 1568px이면 충분히 읽는다. 더 크면 줄여서 전송량과 시간을 아낀다.
        val longest = max(crop.width, crop.height)
        val img = if (longest > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                crop, (crop.width * s).roundToInt(), (crop.height * s).roundToInt(), true
            ).also { crop.recycle() }
        } else crop

        // 이미지 변환은 카드를 띄운 뒤 뒤에서 한다. 질문을 누르는 시점엔 보통 이미 끝나 있다.
        // JPEG(품질 90): PNG보다 몇 배 작아서 전송이 빠르고, 글자를 읽는 데는 차이가 없다.
        val png: Future<ByteArray> = worker.submit<ByteArray> {
            ByteArrayOutputStream().use { out ->
                img.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray().also { DebugLog.add("전송용 이미지 ${it.size / 1024}KB") }
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
                openCard(screenRect)
            }
        }
    }

    /** 대화 카드를 띄운다 (selection: 카드를 붙일 기준 영역) */
    private fun openCard(selection: Rect) {
        val p = FloatingPanel(
            ctx = this,
            session = ChatSession.get(this),
            selection = selection,
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

    /** 떠 있는 연습장 등에서 보낸 질문의 답을 보여줄 채팅이 없을 때, 그 옆에 대화 카드를 띄운다 */
    fun showChatCard(anchor: Rect) {
        if (panel != null || ChatActivity.visible) return
        openCard(anchor)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set

        /** 에어 액션으로 실행된 투명 화면이 닫힐 시간 */
        const val TRIGGER_DELAY_MS = 80L

        /** 카드의 [캡처] 버튼 / 펜 버튼: 카드가 화면에서 사라질 한 프레임만 기다린다 */
        const val RECAPTURE_DELAY_MS = 120L

        private const val MAX_SIDE = 1568
    }
}
