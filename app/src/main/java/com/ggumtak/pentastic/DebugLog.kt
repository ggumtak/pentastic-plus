package com.ggumtak.pentastic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 진단 기록. 캡처 → 선택 → 대화에 붙이기 → 질문 전송의 각 단계를 최근 60개까지 기억한다.
 * 설정 화면의 '진단 기록'에서 보고 복사할 수 있다. 화면 내용이나 API 키는 기록하지 않는다.
 */
object DebugLog {
    private const val MAX = 60
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(기록 없음)" else lines.joinToString("\n")
}
