package com.ggumtak.pentastic

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * 캡처한 이미지를 붙인 상태로 열리는 작은 질문 창.
 * 질문은 사용자가 버튼을 누르거나 입력 후 보내기를 눌러야만 전송된다.
 */
class AskActivity : Activity() {

    private lateinit var prefs: Prefs
    private var imageBytes: ByteArray? = null
    private var tutor: ClaudeTutor? = null
    private var busy = false

    private lateinit var panel: LinearLayout
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private val quickButtons = mutableListOf<Button>()
    private val logText = SpannableStringBuilder()

    private var accent = 0
    private var textMuted = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        prefs = Prefs(this)

        val path = intent.getStringExtra(EXTRA_IMAGE)
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            finish()
            return
        }
        imageBytes = file.readBytes()

        resolveColors()
        setContentView(buildUi(file))
        applyPanelLayout()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPanelLayout()
    }

    override fun onDestroy() {
        tutor?.close()
        tutor = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun resolveColors() {
        val a = obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent, android.R.attr.textColorSecondary)
        )
        accent = a.getColor(0, 0xFF3D6AE0.toInt())
        textMuted = a.getColor(1, 0xFF888888.toInt())
        a.recycle()
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, fallback)
        a.recycle()
        return c
    }

    private fun buildUi(imageFile: File): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x22000000)
            setOnClickListener { finish() } // 바깥을 누르면 닫기
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true // 패널 안 클릭이 바깥(닫기)으로 새지 않게
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(themeColor(android.R.attr.colorBackground, 0xFFFFFFFF.toInt()))
            }
        }

        // 머리줄: 제목 / 설정 / 닫기
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "일본어 질문"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton("설정") {
            startActivity(
                Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        header.addView(smallButton("닫기") { finish() })
        panel.addView(header)

        // 캡처 이미지 미리보기
        val preview = ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(imageFile.absolutePath))
            adjustViewBounds = true
            maxHeight = dp(150)
            scaleType = ImageView.ScaleType.FIT_START
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), textMuted)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        panel.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // 빠른 질문 버튼
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "해석·문법" to ClaudeTutor.Q_GRAMMAR,
            "한자 예시" to ClaudeTutor.Q_KANJI,
            "단어 뜻" to ClaudeTutor.Q_WORDS,
        ).forEach { (label, q) ->
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { send(q, label) }
            }
            quickButtons += b
            quickRow.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(quickRow)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // 답변 영역
        log = TextView(this).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            text = "위 버튼을 누르거나, 아래에 질문을 쓰고 보내기를 누르세요.\n아직 아무것도 전송되지 않았습니다."
            setTextColor(textMuted)
        }
        logScroll = ScrollView(this).apply { addView(log) }
        panel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(textMuted)
        }
        panel.addView(status)

        // 입력줄
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "질문 입력 (비워두면 해석·문법)"
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)
            setOnEditorActionListener { _, actionId, event ->
                val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (isSend) sendTyped()
                isSend
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendButton = Button(this).apply {
            text = "보내기"
            setOnClickListener { sendTyped() }
        }
        inputRow.addView(sendButton)
        panel.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        root.addView(panel)
        return root
    }

    private fun smallButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    /** 가로 화면이면 오른쪽 45%, 세로 화면이면 아래쪽 60%에 패널을 둔다. Anki 카드가 가려지지 않게. */
    private fun applyPanelLayout() {
        if (!::panel.isInitialized) return
        val dm = resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        val m = dp(12)
        panel.layoutParams = if (landscape) {
            FrameLayout.LayoutParams(
                max(dp(360), (dm.widthPixels * 0.45f).toInt()),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END,
            ).apply { setMargins(m, m * 3, m, m) }
        } else {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.6f).toInt(),
                Gravity.BOTTOM,
            ).apply { setMargins(m, m, m, m) }
        }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized || busy) return
        val modelName = if (prefs.model == ClaudeTutor.MODEL_SONNET) "Sonnet 5.5" else "Haiku 5.5"
        status.text = if (prefs.apiKey.isBlank()) "API 키가 없습니다. [설정]에서 입력해 주세요."
        else "모델: $modelName"
    }

    // ---------------------------------------------------------------- 질문 보내기

    private fun sendTyped() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) send(ClaudeTutor.Q_GRAMMAR, "해석·문법") else send(q, q)
        input.setText("")
    }

    private fun send(question: String, shownAs: String) {
        if (busy) return
        val key = prefs.apiKey
        if (key.isBlank()) {
            status.text = "API 키가 없습니다. [설정]에서 입력한 뒤 다시 눌러 주세요."
            return
        }
        val t = tutor ?: ClaudeTutor(key, prefs.model).also { tutor = it }

        // 첫 질문이면 안내 문구를 지운다
        if (logText.isEmpty()) log.setTextColor(themeColor(android.R.attr.textColorPrimary, 0xFF000000.toInt()))
        if (logText.isNotEmpty()) logText.append("\n\n")
        val qStart = logText.length
        logText.append("▶ ").append(shownAs).append("\n")
        logText.setSpan(ForegroundColorSpan(accent), qStart, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logText.setSpan(StyleSpan(Typeface.BOLD), qStart, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        log.text = logText
        // 새 답변 시작 위치로 한 번만 스크롤 (읽는 중에 자동으로 따라 내려가지 않게)
        log.post {
            val layout = log.layout ?: return@post
            logScroll.smoothScrollTo(0, layout.getLineTop(layout.getLineForOffset(qStart)))
        }

        setBusy(true)
        status.text = "답변 받는 중…"
        val image = imageBytes
        thread(name = "claude-ask") {
            try {
                t.ask(image, "image/png", question) { delta ->
                    runOnUiThread {
                        logText.append(delta)
                        log.text = logText
                    }
                }
                runOnUiThread {
                    setBusy(false)
                    refreshStatus()
                }
            } catch (e: ClaudeTutor.TutorException) {
                runOnUiThread { showError(e.message ?: "오류가 발생했습니다.") }
            } catch (e: Exception) {
                runOnUiThread { showError("오류: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            }
        }
    }

    private fun showError(msg: String) {
        if (isDestroyed) return
        val start = logText.length
        logText.append("\n⚠ ").append(msg)
        logText.setSpan(ForegroundColorSpan(0xFFD84315.toInt()), start, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        log.text = logText
        setBusy(false)
        refreshStatus()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        sendButton.isEnabled = !b
        quickButtons.forEach { it.isEnabled = !b }
    }

    companion object {
        const val EXTRA_IMAGE = "image_path"

        private var current: WeakReference<AskActivity>? = null

        /** 열려 있는 질문 창이 있으면 닫는다. 닫았으면 true. */
        fun closeIfOpen(): Boolean {
            val a = current?.get() ?: return false
            if (a.isFinishing || a.isDestroyed) return false
            a.finish()
            return true
        }
    }
}
