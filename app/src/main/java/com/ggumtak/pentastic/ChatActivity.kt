package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast

/**
 * AI 챗 화면. 분할 화면(멀티윈도우)으로 Anki 옆에 띄워 두는 용도.
 * 캡처하면 떠 있는 카드 대신 이 화면으로 바로 들어온다. 카드와 같은 대화(ChatSession)를 쓴다.
 */
class ChatActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val session = ChatSession.get(this)
        val chat = ChatView(this, session, ChatView.Mode.DOCKED, actions = object : ChatView.Actions {
            override fun recapture() {
                val svc = CaptureService.instance
                if (svc == null) {
                    Toast.makeText(this@ChatActivity, "먼저 설정에서 화면 캡처 권한(접근성)을 켜 주세요.", Toast.LENGTH_LONG).show()
                    openSettings()
                } else {
                    svc.requestCapture(CaptureService.RECAPTURE_DELAY_MS)
                }
            }

            override fun openSettings() {
                startActivity(Intent(this@ChatActivity, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun openPractice() = PracticeActivity.open(this@ChatActivity)
        })
        // 이 화면 위에서 S펜 버튼을 눌러도 다시 캡처
        val frame = FloatingPanel.PanelFrame(this).apply {
            onStylusButton = { CaptureService.instance?.requestCapture(CaptureService.RECAPTURE_DELAY_MS) }
            setBackgroundColor(chat.palette.card)
            fitsSystemWindows = true
            addView(chat, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)
        window.statusBarColor = chat.palette.card
        window.navigationBarColor = chat.palette.card
    }

    override fun onStart() {
        super.onStart()
        dockedVisible = true
    }

    override fun onStop() {
        dockedVisible = false
        super.onStop()
    }

    companion object {
        @Volatile
        private var dockedVisible = false

        /** 한자 연습장처럼 AI 챗을 품은 다른 화면이 보이는 동안 true */
        @Volatile
        var visibleExtra = false

        /** AI 챗이 화면에 보이는 동안 true. 이때 캡처하면 카드 대신 이 화면으로 보낸다. */
        val visible: Boolean get() = dockedVisible || visibleExtra

        /** 분할 화면(옆자리)으로 연다. 지원하지 않는 상태면 일반 창으로 열린다. */
        fun open(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, ChatActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                )
            )
        }
    }
}
