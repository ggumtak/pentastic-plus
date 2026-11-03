package com.ggumtak.pentastic

import android.content.Context

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

    /** 모르는 정보가 있으면 Claude가 스스로 판단해 인터넷 검색 (기본: 켜짐) */
    var webSearch: Boolean
        get() = sp.getBoolean("web_search", true)
        set(v) = sp.edit().putBoolean("web_search", v).apply()

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
    }

    /** 과목별 지시문(시스템 프롬프트). 직접 고치지 않았으면 기본값. */
    fun promptFor(s: Subject): String =
        sp.getString("system_prompt_${s.id}", null)?.takeIf { it.isNotBlank() } ?: s.defaultPrompt

    fun setPromptFor(s: Subject, v: String) {
        val t = v.trim()
        if (t.isEmpty() || t == s.defaultPrompt.trim()) sp.edit().remove("system_prompt_${s.id}").apply()
        else sp.edit().putString("system_prompt_${s.id}", t).apply()
    }

    fun isCustomPrompt(s: Subject): Boolean = !sp.getString("system_prompt_${s.id}", null).isNullOrBlank()

    /** 연습장: AI 챗과 쓰기 칸 자리 바꿈 */
    var practiceSwap: Boolean
        get() = sp.getBoolean("practice_swap", false)
        set(v) = sp.edit().putBoolean("practice_swap", v).apply()

    /** 연습장: 메뉴줄을 아래에 둘지 */
    var practiceToolbarBottom: Boolean
        get() = sp.getBoolean("practice_toolbar_bottom", false)
        set(v) = sp.edit().putBoolean("practice_toolbar_bottom", v).apply()
}
