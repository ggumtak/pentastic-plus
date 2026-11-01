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
import com.anthropic.client.AnthropicClient
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

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

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
        panel?.close()
        synchronized(this) {
            client?.close()
            client = null
        }
    }

    /** API 키가 바뀌지 않는 한 같은 클라이언트(연결 풀)를 계속 쓴다. */
    @Synchronized
    fun client(): AnthropicClient? {
        val key = Prefs(this).apiKey
        if (key.isBlank()) return null
        if (client == null || clientKey != key) {
            client?.close()
            client = ClaudeTutor.newClient(key)
            clientKey = key
        }
        return client
    }

    // ------------------------------------------------------------------ 캡처

    fun requestCapture(delayMs: Long) {
        // 이전 캡처가 어떤 이유로든 끝나지 않고 멈춰 있으면(3초 이상) 정리하고 새로 시작한다.
        val now = System.currentTimeMillis()
        if (pending && now - pendingSince < 3000) return
        if (selector != null) {
            if (now - selectorSince < 1500) return
            removeSelector()
        }
        pendingSince = now
        // 떠 있는 카드가 있으면 바로 닫는다 (오버레이라 애니메이션 대기 없이 사라진다)
        panel?.close()
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
                    if (shot == null) {
                        toast("화면 캡처에 실패했습니다.")
                        return
                    }
                    showSelector(fixOrientation(shot))
                }

                override fun onFailure(errorCode: Int) {
                    // 캡처는 1초에 한 번만 허용된다. 너무 빨리 다시 누른 경우 한 번 자동 재시도.
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !retried) {
                        retried = true
                        handler.postDelayed({ capture() }, 1000)
                        return
                    }
                    pending = false
                    toast("화면 캡처에 실패했습니다 (코드 $errorCode).")
                }
            })
        } catch (e: Exception) {
            pending = false
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
            onCancel = {
                removeSelector()
                shot.recycle()
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
        selector = view
        selectorSince = System.currentTimeMillis()
        view.requestFocus()

        // 고르는 동안 API 서버 연결을 미리 열어 둔다 (토큰을 쓰지 않는 요청)
        thread(name = "prewarm") { client()?.let { ClaudeTutor.prewarmConnection(it) } }
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

        val p = FloatingPanel(
            ctx = this,
            preview = img,
            imageBytes = { png.get() },
            selection = screenRect,
            clientProvider = { client() },
            onOpenSettings = {
                startActivity(
                    Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onClosed = { panel = null },
        )
        panel = p
        p.show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set

        /** 에어 액션으로 실행된 투명 화면이 닫힐 시간 */
        const val TRIGGER_DELAY_MS = 180L

        private const val MAX_SIDE = 1568
    }
}
