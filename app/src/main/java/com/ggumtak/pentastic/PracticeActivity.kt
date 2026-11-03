package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 한자 연습장: 큰 쓰기 칸(가운데 십자·대각 안내선, 따라 쓰기용 흐린 글자) + 옆에 AI 챗.
 * - 쓰는 즉시 기기 안에서 무슨 글자로 인식되는지 보여준다 (비용 없음)
 * - [내 글씨 봐 줘]: 쓴 글씨 이미지를 Claude에게 보내 모양·균형·부수를 평가받는다
 */
class PracticeActivity : Activity() {

    private lateinit var ink: InkView
    private lateinit var target: EditText
    private lateinit var result: TextView
    private var ghostOn = true
    private var topCandidate: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = ChatSession.get(this)
        val chat = ChatView(this, session, ChatView.Mode.DOCKED, actions = object : ChatView.Actions {
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

        // 왼쪽(세로 화면이면 위쪽): 연습 칸
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(c.card)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "한자 연습장"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(c.text)
            setPadding(0, 0, dp(12), 0)
        })
        target = EditText(this).apply {
            hint = "연습할 한자"
            textSize = 18f
            textLocale = Locale.JAPAN
            setTextColor(c.text)
            setHintTextColor(c.muted)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setText(intent.getStringExtra(EXTRA_TARGET) ?: "")
            background = GradientDrawable().apply {
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(c.soft)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        top.addView(target, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        pad.addView(top)

        ink = InkView(this, lineColor = c.stroke, inkColor = c.strong, guideColor = c.stroke, square = true)
        pad.addView(ink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // 결과 문구는 항상 2줄 높이로 고정 (문구 길이가 바뀌어도 쓰는 칸이 움직이지 않게)
        result = TextView(this).apply {
            textSize = 15f
            setTextColor(c.text)
            setPadding(0, dp(10), 0, dp(4))
            setLines(2)
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "칸에 한자를 써 보세요. 쓰는 대로 무슨 글자로 읽히는지 알려 드립니다."
        }
        pad.addView(result)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, primary: Boolean = false, onClick: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (primary) c.onAccentSoft else c.text)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = GradientDrawable().apply {
                    cornerRadius = 18f * resources.displayMetrics.density
                    setColor(if (primary) c.accentSoft else c.soft)
                }
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        action("획 취소") { ink.undo() }
        action("지우기") { ink.clear() }
        action("따라쓰기 끄기") { /* 아래에서 글자 바꿈 */ }
        val ghostBtn = actions.getChildAt(actions.childCount - 1) as TextView
        ghostBtn.setOnClickListener {
            ghostOn = !ghostOn
            ghostBtn.text = if (ghostOn) "따라쓰기 끄기" else "따라쓰기 켜기"
            updateGhost()
        }
        action("내 글씨 봐 줘", primary = true) { askReview(session) }
        action("부수·구성") {
            val k = target.text.toString().trim().ifEmpty { topCandidate ?: "" }
            if (k.isEmpty()) result.text = "연습할 한자를 적거나 먼저 써 주세요."
            else session.send(ClaudeTutor.radicalQuestion(k), "부수·구성: $k")
        }
        pad.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            addView(actions)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateGhost()
                checkMatch()
            }
        })
        ink.onStrokesChanged = { recognize() }
        updateGhost()

        // 가로 화면: 연습 칸 | AI 챗,  세로 화면: 연습 칸 위, AI 챗 아래
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = FloatingPanel.PanelFrame(this).apply { setBackgroundColor(c.card); fitsSystemWindows = true }
        val split = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        split.addView(pad, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.2f))
        split.addView(View(this).apply { setBackgroundColor(c.stroke) }, if (landscape)
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        split.addView(chat, if (landscape) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(split, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        window.statusBarColor = c.card
        window.navigationBarColor = c.card

        Handwriting.prepare { err -> if (err != null) result.text = err }
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

    private fun updateGhost() {
        ink.ghost = if (ghostOn) target.text.toString().trim().take(2) else null
    }

    private fun recognize() {
        if (ink.isEmpty) {
            topCandidate = null
            result.text = "칸에 한자를 써 보세요."
            return
        }
        Handwriting.prepare { err ->
            if (err != null) {
                result.text = err
                return@prepare
            }
            Handwriting.recognize(ink.strokeData, ink.width.toFloat(), ink.height.toFloat()) { cands ->
                topCandidate = cands.firstOrNull()
                checkMatch(cands)
            }
        }
    }

    private fun checkMatch(cands: List<String>? = null) {
        val top = topCandidate ?: return
        val want = target.text.toString().trim()
        val others = cands?.drop(1)?.take(4)?.joinToString(" ") ?: ""
        result.text = when {
            want.isEmpty() -> "인식: $top" + if (others.isNotEmpty()) "   (다른 후보: $others)" else ""
            top == want -> "✓ 「$want」로 잘 읽힙니다."
            cands?.contains(want) == true -> "△ 「$want」 후보에는 있지만 「$top」로 더 읽힙니다. 모양을 조금 다듬어 보세요."
            else -> "✗ 「$top」로 읽힙니다. 「$want」와 비교해 보세요."
        }
    }

    private fun askReview(session: ChatSession) {
        val png = ink.toPng()
        if (png == null) {
            result.text = "먼저 칸에 한자를 써 주세요."
            return
        }
        val want = target.text.toString().trim().ifEmpty { topCandidate ?: "" }
        val (bmp, bytes) = png
        session.attach(bmp, { bytes }, allowAutoAsk = false, mime = "image/png")
        val q = if (want.isNotEmpty())
            "이 이미지는 내가 손으로 쓴 「$want」야. 모양·균형·비율이 자연스러운지, 빠지거나 잘못 붙은 획이 있는지 봐 주고, 부수와 구성도 짧게 알려 줘. 획순은 그림으로 알 수 없으니 이 한자에서 자주 틀리는 획순만 짚어 줘."
        else
            "이 이미지는 내가 손으로 쓴 한자야. 무슨 글자인지 읽고, 모양·균형·빠진 획을 봐 준 뒤 부수와 구성을 짧게 알려 줘."
        session.send(q, "내 글씨: ${want.ifEmpty { "?" }}")
    }

    companion object {
        const val EXTRA_TARGET = "target"

        fun open(ctx: android.content.Context, target: String? = null) {
            ctx.startActivity(Intent(ctx, PracticeActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
