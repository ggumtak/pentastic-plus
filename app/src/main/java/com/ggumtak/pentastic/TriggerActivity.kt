package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 런처 아이콘 / S펜 에어 액션(펜 버튼 길게 누르기)으로 실행되는 진입점.
 * 화면을 그리지 않고, 접근성 서비스에 캡처를 요청한 뒤 바로 닫힌다.
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = CaptureService.instance
        if (service == null) {
            // 아직 접근성 권한이 없으면 설정 화면으로 안내
            startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            // 이전 질문 창이 떠 있으면 닫고, 닫히는 애니메이션이 끝난 뒤 캡처한다.
            val closedPanel = AskActivity.closeIfOpen()
            service.requestCapture(if (closedPanel) 650L else 350L)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
