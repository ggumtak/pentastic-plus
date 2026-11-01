package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 처음 한 번 하는 설정: 접근성 권한, API 키, 모델, 펜 버튼 연결 안내. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var a11yStatus: TextView
    private lateinit var keyStatus: TextView
    private lateinit var promptStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(48))
        }

        col.addView(TextView(this).apply {
            text = "펜스틱 설정"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(body("S펜 버튼을 길게 눌러 화면을 멈추고, 펜으로 드래그한 부분을 Claude에게 바로 질문합니다. 답은 보던 화면 위에 작은 카드로 뜹니다. 아래 4단계를 한 번만 하면 됩니다."))

        // 1. 접근성
        col.addView(section("1. 화면 캡처 권한 켜기"))
        a11yStatus = body("")
        col.addView(a11yStatus)
        col.addView(body("접근성 → 설치된 앱 → '펜스틱 화면 캡처' → 켜기.\n이 권한은 펜 버튼을 눌렀을 때 화면을 캡처하는 데만 씁니다."))
        col.addView(body("스위치가 회색으로 막혀 있거나 '제한된 설정' 창이 뜨면: 아래 [앱 정보 열기] → 오른쪽 위 ⋮ → '제한된 설정 허용' → 다시 접근성에서 켜기. (스토어 밖에서 설치한 앱에 안드로이드 13이 거는 보호 장치입니다.)"))
        col.addView(button("앱 정보 열기 (제한된 설정 허용)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // 2. API 키
        col.addView(section("2. Claude API 키 입력"))
        keyStatus = body("")
        col.addView(keyStatus)
        val keyInput = EditText(this).apply {
            hint = "sk-ant-로 시작하는 키 붙여넣기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(keyInput, matchWrap())
        col.addView(button("키 저장") {
            val k = keyInput.text.toString().trim()
            if (!k.startsWith("sk-ant-")) {
                toast("키는 보통 sk-ant- 로 시작합니다. 다시 확인해 주세요.")
            } else {
                prefs.apiKey = k
                keyInput.setText("")
                toast("저장했습니다.")
                refresh()
            }
        })
        col.addView(body("키는 console.anthropic.com → API Keys에서 만듭니다. 사용하려면 Billing에서 크레딧을 먼저 충전해야 합니다. 키는 이 태블릿의 앱 안에만 저장되고, 백업되지 않습니다."))
        col.addView(button("console.anthropic.com 열기") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        })

        // 3. 모델
        col.addView(section("3. 모델"))
        val group = RadioGroup(this)
        val haiku = RadioButton(this).apply {
            id = 1
            text = "Haiku 5.5 — 빠르고 저렴 (기본, 추천)"
        }
        val sonnet = RadioButton(this).apply {
            id = 2
            text = "Sonnet 5.5 — 더 정확, 비용 약 20배"
        }
        group.addView(haiku)
        group.addView(sonnet)
        group.check(if (prefs.model == ClaudeTutor.MODEL_SONNET) 2 else 1)
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.model = if (checkedId == 2) ClaudeTutor.MODEL_SONNET else ClaudeTutor.MODEL_HAIKU
        }
        col.addView(group)
        col.addView(body("바뀐 모델은 다음 캡처부터 적용됩니다."))

        // AI 챗 (분할 화면)
        col.addView(section("AI 챗 (분할 화면)"))
        col.addView(body("Anki 옆에 대화 화면을 띄워 두는 방식입니다. AI 챗이 화면에 보이는 동안 캡처하면 떠 있는 카드 대신 AI 챗으로 바로 들어갑니다. 카드의 ⧉ 버튼을 누르면 지금 대화를 그대로 AI 챗으로 옮깁니다.\n\n여는 방법:\n・ 앱 서랍이나 작업 표시줄의 '펜스틱 챗' 아이콘을 화면 옆쪽으로 끌어다 놓기\n・ 펜스틱 아이콘 길게 누르기 → 펜스틱 챗\n・ 한 번에 열기: 엣지 패널에서 '앱 페어'로 Anki + 펜스틱 챗을 묶어 두면 한 번 눌러 둘 다 분할 화면으로 열립니다."))
        col.addView(button("AI 챗 열기") {
            finish()
            ChatActivity.open(this)
        })

        // 답변 방식 (프롬프트)
        col.addView(section("답변 방식 (프롬프트)"))
        col.addView(body("Claude에게 매번 함께 보내는 지시문입니다. 원하는 대로 고쳐 쓰고 [저장]을 누르세요. 다음 캡처부터 적용됩니다.\n예: '예문은 항상 2개씩', 'JLPT 급수도 알려 줘', '더 짧게'"))
        promptStatus = body("")
        col.addView(promptStatus)
        val promptInput = EditText(this).apply {
            setText(prefs.systemPrompt)
            textSize = 14f
            minLines = 6
            maxLines = 16
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isVerticalScrollBarEnabled = true
        }
        col.addView(promptInput, matchWrap())
        val promptButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        promptButtons.addView(button("저장") {
            prefs.systemPrompt = promptInput.text.toString()
            promptInput.setText(prefs.systemPrompt)
            toast("저장했습니다. 다음 캡처부터 적용됩니다.")
            refresh()
        })
        promptButtons.addView(button("기본값으로 되돌리기") {
            prefs.systemPrompt = ""
            promptInput.setText(prefs.systemPrompt)
            toast("기본 프롬프트로 되돌렸습니다.")
            refresh()
        })
        col.addView(promptButtons)

        // 빠르게 보기
        col.addView(section("빠르게 보기 (선택)"))
        col.addView(Switch(this).apply {
            text = "드래그하자마자 '해석·문법'을 바로 물어보기"
            textSize = 15f
            isChecked = prefs.autoAsk
            setOnCheckedChangeListener { _, on -> prefs.autoAsk = on }
        }, matchWrap())
        col.addView(body("켜면 버튼을 누르지 않아도 답이 바로 나옵니다. 끄면(기본) 버튼을 누르거나 직접 질문을 보내기 전에는 아무것도 전송되지 않습니다."))

        // 4. 펜 버튼
        col.addView(section("4. 펜 버튼 길게 누르기에 연결"))
        col.addView(body("설정 → 유용한 기능 → S펜 → 에어 액션 → '펜 버튼 길게 누르기' → 앱 열기에서 '펜스틱'을 고릅니다.\n(메뉴 이름은 One UI 버전에 따라 조금 다를 수 있습니다. 에어 액션은 S펜이 충전되어 있어야 동작합니다.)"))
        col.addView(button("지금 캡처 테스트") {
            val svc = CaptureService.instance
            if (svc == null) {
                toast("먼저 1단계의 화면 캡처 권한을 켜 주세요.")
            } else {
                finish()
                svc.requestCapture(700L)
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!::a11yStatus.isInitialized) return
        a11yStatus.text = when {
            CaptureService.instance != null -> "상태: 켜짐 ✓"
            isA11yEnabledInSettings() -> "상태: 켜져 있지만 동작하지 않음 — 접근성에서 껐다가 다시 켜 주세요."
            else -> "상태: 꺼짐 — 아래 버튼으로 켜 주세요."
        }
        if (::promptStatus.isInitialized) {
            promptStatus.text = if (prefs.isCustomPrompt) "상태: 직접 고친 프롬프트 사용 중" else "상태: 기본 프롬프트"
        }
        val k = prefs.apiKey
        keyStatus.text = if (k.isBlank()) "상태: 키 없음"
        else "상태: 저장됨 (…${k.takeLast(4)})"
    }

    private fun isA11yEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(28), 0, dp(4))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
