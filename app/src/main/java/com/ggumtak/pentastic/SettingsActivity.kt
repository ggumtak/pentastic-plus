package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 처음 한 번 하는 설정: 접근성 권한, API 키, 모델, 펜 버튼 연결 안내. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var a11yStatus: TextView
    private lateinit var keyStatus: TextView

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
        col.addView(body("S펜 버튼을 길게 눌러 화면을 멈추고, 펜으로 드래그한 부분을 Claude에게 바로 질문합니다. 아래 4단계를 한 번만 하면 됩니다."))

        // 1. 접근성
        col.addView(section("1. 화면 캡처 권한 켜기"))
        a11yStatus = body("")
        col.addView(a11yStatus)
        col.addView(body("접근성 → 설치된 앱 → '펜스틱 화면 캡처' → 켜기.\n이 권한은 펜 버튼을 눌렀을 때 화면을 캡처하는 데만 씁니다."))
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
        a11yStatus.text = if (CaptureService.instance != null) "상태: 켜짐 ✓" else "상태: 꺼짐 — 아래 버튼으로 켜 주세요."
        val k = prefs.apiKey
        keyStatus.text = if (k.isBlank()) "상태: 키 없음"
        else "상태: 저장됨 (…${k.takeLast(4)})"
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
