package com.ggumtak.pentastic

import android.content.Context
import java.security.MessageDigest

/** 앱 설정. API 키는 이 기기의 앱 전용 저장소에만 저장된다 (백업 꺼짐). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pentastic", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** 과목별 모델 (역사는 기본 Sonnet, 나머지는 Haiku) */
    fun modelFor(s: Subject): String = sp.getString("model_${s.id}", null) ?: s.defaultModel

    fun setModelFor(s: Subject, model: String) = sp.edit().putString("model_${s.id}", model).apply()

    /** 드래그가 끝나자마자 '해석·문법'을 자동으로 물어볼지 (기본: 꺼짐 — 누르기 전엔 전송 안 함) */
    var autoAsk: Boolean
        get() = sp.getBoolean("auto_ask", false)
        set(v) = sp.edit().putBoolean("auto_ask", v).apply()

    /** 지금 고른 과목 */
    var subjectId: String
        get() = sp.getString("subject", Subjects.JAPANESE.id) ?: Subjects.JAPANESE.id
        set(v) = sp.edit().putString("subject", v).apply()

    val subject: Subject get() = Subjects.byId(subjectId)

    init {
        // 예전 버전의 (일본어) 프롬프트 설정을 과목별 저장 위치로 옮긴다
        val old = sp.getString("system_prompt", null)
        if (old != null) {
            if (old.isNotBlank() && !sp.contains("system_prompt_ja")) sp.edit().putString("system_prompt_ja", old).apply()
            sp.edit().remove("system_prompt").apply()
        }
        migratePromptDefaults()
    }

    /**
     * 기본 지시문이 바뀐 뒤 처음 열 때 한 번: 저장된 '직접 고친' 지시문이 예전 기본값과 글자까지 같으면
     * (사실상 안 고친 것) 지워서 새 기본값을 쓰게 한다. 정말 고친 지시문은 그대로 두고, 설정 화면에 안내만 띄운다.
     */
    private fun migratePromptDefaults() {
        if (sp.getInt(KEY_DEFAULTS_VERSION, 0) >= PROMPT_DEFAULTS_VERSION) return
        val e = sp.edit()
        Subjects.all.forEach { s ->
            val custom = sp.getString("system_prompt_${s.id}", null) ?: return@forEach
            if (sha256(custom.trim()) in OLD_DEFAULT_HASHES[s.id].orEmpty()) {
                e.remove("system_prompt_${s.id}").remove("system_prompt_ver_${s.id}")
            }
        }
        e.putInt(KEY_DEFAULTS_VERSION, PROMPT_DEFAULTS_VERSION).apply()
    }

    /** 과목별 지시문(시스템 프롬프트). 직접 고치지 않았으면 기본값. */
    fun promptFor(s: Subject): String =
        sp.getString("system_prompt_${s.id}", null)?.takeIf { it.isNotBlank() } ?: s.defaultPrompt

    fun setPromptFor(s: Subject, v: String) {
        val t = v.trim()
        if (t.isEmpty() || t == s.defaultPrompt.trim()) {
            sp.edit().remove("system_prompt_${s.id}").remove("system_prompt_ver_${s.id}").apply()
        } else {
            // 어느 기본값 버전을 보고 고쳤는지 함께 적어 둔다 (나중에 새 기본값이 나오면 안내하려고)
            sp.edit().putString("system_prompt_${s.id}", t)
                .putInt("system_prompt_ver_${s.id}", PROMPT_DEFAULTS_VERSION).apply()
        }
    }

    fun isCustomPrompt(s: Subject): Boolean = !sp.getString("system_prompt_${s.id}", null).isNullOrBlank()

    /** 직접 고친 지시문을 쓰는 중인데, 그 뒤에 기본 지시문이 새로 바뀌었는지 (설정 화면 안내용) */
    fun hasNewerDefault(s: Subject): Boolean =
        isCustomPrompt(s) && sp.getInt("system_prompt_ver_${s.id}", 0) < PROMPT_DEFAULTS_VERSION

    /** 연습장: AI 챗과 쓰기 칸 자리 바꿈 */
    var practiceSwap: Boolean
        get() = sp.getBoolean("practice_swap", false)
        set(v) = sp.edit().putBoolean("practice_swap", v).apply()

    /** 연습장: 메뉴줄을 아래에 둘지 */
    var practiceToolbarBottom: Boolean
        get() = sp.getBoolean("practice_toolbar_bottom", false)
        set(v) = sp.edit().putBoolean("practice_toolbar_bottom", v).apply()

    companion object {
        /** 기본 지시문(과목별 지시문·공통 규칙) 버전. 기본 지시문을 바꿀 때마다 1 올린다. */
        const val PROMPT_DEFAULTS_VERSION = 4
        private const val KEY_DEFAULTS_VERSION = "prompt_defaults_version"

        /**
         * 예전 버전 기본 지시문의 SHA-256 (과목별, 앞뒤 공백 제거한 UTF-8 기준, 주석은 그 버전의 커밋).
         * git 기록에서 그 시절 문자열을 그대로 다시 만들어 계산했다.
         * 기본 지시문을 바꿀 때는 지금 기본값의 해시를 여기에 더하고 PROMPT_DEFAULTS_VERSION을 올린다.
         */
        private val OLD_DEFAULT_HASHES: Map<String, Set<String>> = mapOf(
            "ja" to setOf(
                "93a80a11edead9ba890a6d7a8bdc7ac92a044ca300f3d97d8da235b8293fdf86", // 2890ffd
                "0c29e688cc261a777b358c4a172d3648dde2c158d771b0b98b798ae5b6df5238", // 48a979f
                "00aa7cd0b9b0affd87785eb46ca2931070c340ba03e6191b0d7dc455908471d1", // 785bf2d
                "a2f1a1b35703b479c61a8c2e59894f122dacc2c7149234156b8a0e3689142cf2", // a1af7f7
                "056c5ef03721e93d7ffd1f5ca7f93014a07e5fc9588d43b59dd9030240d5883b", // df69cee
            ),
            "en" to setOf(
                "796182271e1570b74f6144dece118aad7df8fd841ef2ac5ab5a01d3fd043160f", // 785bf2d
                "533f5ff9962b52d7b8a6fb0b161babfc43a5e02b317a585bdf304a8e298ad682", // a1af7f7
                "965ecf15e1e4db7c88a601f624abd1f81121e50d9e8b8c7e029c521126892b35", // df69cee
            ),
            "hist" to setOf(
                "e982671d58403a753c9be0ba06750c0d6c2b8f3d22b4b7c27a388f05037dc1c1", // 785bf2d
                "c7c99a6ac9ed38df6d8769e0af08d12476ee1a6a7c9ab71357d7a7f27d1d4d81", // a1af7f7
                "0d610151a85c7ca02abd1f172c6c2dd233597664fe09fbc34c7616a28f61133b", // df69cee
            ),
            "ko" to setOf(
                "b3398420cacc9e44fe957eef930b24b9dda8dd26c523b78737109b4a134b4bbd", // 785bf2d
                "3a3665fc5ff1f3e33c23ff7b9cade796889264ae3e2c4db721ece41c9a7a6ebf", // a1af7f7
                "629d27ab26b64d13571bba39ab2b696324d065be3bd8c2955fdb999f501c235d", // df69cee
            ),
        )

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
