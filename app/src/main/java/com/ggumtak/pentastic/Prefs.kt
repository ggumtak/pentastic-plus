package com.ggumtak.pentastic

import android.content.Context

/** 앱 설정. API 키는 이 기기의 앱 전용 저장소에만 저장된다 (백업 꺼짐). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pentastic", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", ClaudeTutor.MODEL_HAIKU) ?: ClaudeTutor.MODEL_HAIKU
        set(v) = sp.edit().putString("model", v).apply()

    /** 드래그가 끝나자마자 '해석·문법'을 자동으로 물어볼지 (기본: 꺼짐 — 누르기 전엔 전송 안 함) */
    var autoAsk: Boolean
        get() = sp.getBoolean("auto_ask", false)
        set(v) = sp.edit().putBoolean("auto_ask", v).apply()
}
