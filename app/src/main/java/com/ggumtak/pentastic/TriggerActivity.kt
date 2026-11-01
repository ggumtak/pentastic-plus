package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 런처 아이콘 / S펜 에어 액션(펜 버튼 길게 누르기) / 삼성 앱 페어로 실행되는 진입점.
 * 화면을 그리지 않는다.
 * - 분할 화면 한쪽으로 열렸고 AI 챗이 아직 안 보이면 (= 앱 페어 실행): AI 챗을 그 자리에 연다.
 *   삼성 앱 페어는 아이콘이 아니라 앱의 기본 시작 화면(이 화면)으로 여는 경우가 있어서다.
 * - 그 밖에는 접근성 서비스에 캡처를 요청한다 (권한이 없으면 설정 화면).
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = CaptureService.instance
        val split = isInMultiWindowMode
        DebugLog.add("펜스틱 실행 (접근성 ${if (service == null) "꺼짐" else "켜짐"}, 분할화면=$split, AI챗 보임=${ChatActivity.visible})")
        when {
            split && !ChatActivity.visible -> {
                // 분할 화면 안에서 연 새 화면은 같은 칸에 열린다
                startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            service == null -> startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            else -> service.requestCapture(CaptureService.TRIGGER_DELAY_MS)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
