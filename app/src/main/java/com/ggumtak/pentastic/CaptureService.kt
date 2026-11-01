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
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 화면 캡처 + 영역 선택 오버레이.
 * 접근성 서비스의 takeScreenshot()을 쓰면 매번 "화면 녹화 허용" 창이 뜨지 않는다.
 */
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: SelectionView? = null
    private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    fun requestCapture(delayMs: Long) {
        if (overlay != null || pending) return
        pending = true
        handler.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
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
                showOverlay(fixOrientation(shot))
            }

            override fun onFailure(errorCode: Int) {
                pending = false
                toast("화면 캡처에 실패했습니다 (코드 $errorCode). 1초 뒤 다시 시도해 주세요.")
            }
        })
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

    private fun showOverlay(shot: Bitmap) {
        removeOverlay()
        val wm = getSystemService(WindowManager::class.java)
        val view = SelectionView(
            this,
            shot,
            onSelected = { rect -> onSelected(shot, rect) },
            onCancel = {
                removeOverlay()
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
        overlay = view
        view.requestFocus()
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            getSystemService(WindowManager::class.java).removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }

    private fun onSelected(shot: Bitmap, r: Rect) {
        removeOverlay()
        val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
        shot.recycle()
        // Claude는 긴 변 1568px 정도면 충분히 읽는다. 더 크면 줄여서 전송량을 아낀다.
        val longest = max(crop.width, crop.height)
        val scaled = if (longest > MAX_SIDE) {
            val s = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                crop, (crop.width * s).roundToInt(), (crop.height * s).roundToInt(), true
            ).also { crop.recycle() }
        } else crop
        val file = File(cacheDir, "capture.png")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        scaled.recycle()

        startActivity(
            Intent(this, AskActivity::class.java)
                .putExtra(AskActivity.EXTRA_IMAGE, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        @Volatile
        var instance: CaptureService? = null
            private set

        private const val MAX_SIDE = 1568
    }
}
