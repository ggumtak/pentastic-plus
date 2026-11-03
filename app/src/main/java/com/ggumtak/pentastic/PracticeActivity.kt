package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * 한자 연습장 화면: 쓰기 부분(PracticePad) + 옆에 AI 챗.
 * 쓰기 부분은 [⧉ 띄우기]로 화면 위에 떠 있는 창이 될 수 있다 (그때 이 화면은 닫힌다).
 */
class PracticeActivity : Activity() {

    private val prefs by lazy { Prefs(this) }
    private lateinit var pad: PracticePad
    private lateinit var chat: ChatView
    private lateinit var divider: View
    private lateinit var split: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val session = ChatSession.get(this)
        chat = ChatView(this, session, ChatView.Mode.DOCKED, actions = object : ChatView.Actions {
            override fun recapture() {
                CaptureService.instance?.requestCapture(CaptureService.RECAPTURE_DELAY_MS)
                    ?: Toast.makeText(this@PracticeActivity, "먼저 설정에서 화면 캡처 권한(접근성)을 켜 주세요.", Toast.LENGTH_LONG).show()
            }
            override fun openSettings() {
                startActivity(Intent(this@PracticeActivity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            override fun openPractice() {
                Toast.makeText(this@PracticeActivity, "지금 연습장입니다.", Toast.LENGTH_SHORT).show()
            }
        })
        val c = chat.palette

        pad = PracticeDock.takeForActivity(this)
        intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }?.let { pad.target.setText(it) }
        pad.host = object : PracticePad.Host {
            override fun swap() {
                prefs.practiceSwap = !prefs.practiceSwap
                arrange()
            }
            override fun flip() {
                prefs.practiceToolbarBottom = !prefs.practiceToolbarBottom
                arrange()
            }
            override fun close() = finish()
        }

        // 가로 화면: 연습 칸 | AI 챗,  세로 화면: 연습 칸 위, AI 챗 아래 (⇄로 자리 바꿈)
        val root = FloatingPanel.PanelFrame(this).apply { setBackgroundColor(c.card); fitsSystemWindows = true }
        divider = View(this).apply { setBackgroundColor(c.stroke) }
        split = LinearLayout(this)
        arrange()
        root.addView(split, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window.statusBarColor = c.card
        window.navigationBarColor = c.card
        pad.prepare()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TARGET)?.takeIf { it.isNotBlank() }?.let { pad.target.setText(it) }
    }

    override fun onStart() {
        super.onStart()
        // 연습장이 보이는 동안 캡처하면 옆의 AI 챗으로 들어오게
        ChatActivity.visibleExtra = true
    }

    override fun onStop() {
        ChatActivity.visibleExtra = false
        super.onStop()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        // 띄우기로 옮겨 간 게 아니면 쓰기 부분을 이 화면에서 떼어 둔다 (다음에 다시 쓰도록)
        // (화면 회전 등으로 새 화면이 이미 가져갔으면 건드리지 않는다)
        if (!PracticeDock.floating && ::pad.isInitialized && ::split.isInitialized && pad.parent === split) {
            pad.host = null
            PracticeDock.detach(pad)
        }
        super.onDestroy()
    }

    /** 설정에 맞춰 쓰기 칸·AI 챗·메뉴줄을 배치한다 (그린 획과 대화는 그대로) */
    private fun arrange() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        pad.layoutFor(floating = false, bottomBar = prefs.practiceToolbarBottom)
        PracticeDock.detach(pad)
        split.removeAllViews()
        split.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val padLp = if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f)
        val chatLp = if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val divLp = if (landscape) LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        if (prefs.practiceSwap) {
            split.addView(chat, chatLp); split.addView(divider, divLp); split.addView(pad, padLp)
        } else {
            split.addView(pad, padLp); split.addView(divider, divLp); split.addView(chat, chatLp)
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"

        private var current: WeakReference<PracticeActivity>? = null

        /** 연습장을 띄우면 이 화면은 닫는다 */
        fun finishCurrent() {
            current?.get()?.let { if (!it.isFinishing) it.finish() }
        }

        fun open(ctx: android.content.Context, target: String? = null) {
            ctx.startActivity(Intent(ctx, PracticeActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
