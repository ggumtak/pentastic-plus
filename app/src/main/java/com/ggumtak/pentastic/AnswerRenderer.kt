package com.ggumtak.pentastic

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Claude 답변(가벼운 마크다운)을 읽기 좋은 글자 모양으로 바꾼다.
 * - **굵게** → 굵은 글씨 (기호는 숨김). 답변이 흘러들어오는 중 닫히지 않은 ** 는 잠시 숨긴다.
 * - '・', '-', '*', '•' 로 시작하는 줄 → 들여쓰기된 글머리표
 * - '1.' 같은 번호 줄 → 둘째 줄부터 들여쓰기
 * - '#' 제목 → 굵게
 * - 빈 줄 → 문단 사이 작은 간격
 */
class AnswerRenderer(
    private val density: Float,
    private val strongColor: Int,
    private val bulletColor: Int,
) {
    private fun dp(v: Float) = (v * density).toInt()

    fun append(sb: SpannableStringBuilder, raw: String) {
        val lines = raw.replace("\r", "").trimEnd().split("\n")
        lines.forEachIndexed { i, original ->
            val start = sb.length
            val t = original.trim()
            var bullet = false
            var numbered = false
            var heading = false
            val content = when {
                HEADING.containsMatchIn(t) -> {
                    heading = true
                    t.replaceFirst(HEADING, "")
                }
                t.startsWith("・") -> {
                    bullet = true
                    t.substring(1).trimStart()
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> {
                    bullet = true
                    t.substring(2).trimStart()
                }
                NUMBERED.containsMatchIn(t) -> {
                    numbered = true
                    t
                }
                else -> t
            }
            appendInline(sb, content)
            if (i < lines.lastIndex) sb.append("\n")
            val end = sb.length
            if (end <= start) return@forEachIndexed

            when {
                content.isEmpty() ->
                    // 빈 줄은 높이를 줄여서 문단 간격으로만 쓴다
                    sb.setSpan(AbsoluteSizeSpan(dp(7f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                bullet ->
                    sb.setSpan(BulletSpan(dp(10f), bulletColor, dp(2.6f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                numbered ->
                    sb.setSpan(LeadingMarginSpan.Standard(0, dp(20f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                heading -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(strongColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.04f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun appendInline(sb: SpannableStringBuilder, text: String) {
        var s = text.replace("`", "")
        // 스트리밍 중 아직 닫히지 않은 ** 는 보이지 않게 뺀다
        if (BOLD_MARK.findAll(s).count() % 2 == 1) {
            val idx = s.lastIndexOf("**")
            s = s.removeRange(idx, idx + 2)
        }
        var last = 0
        for (m in BOLD.findAll(s)) {
            sb.append(s, last, m.range.first)
            val st = sb.length
            sb.append(m.groupValues[1])
            sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(strongColor), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
    }

    companion object {
        private val HEADING = Regex("^#{1,6}\\s+")
        private val NUMBERED = Regex("^\\d{1,2}[.)]\\s")
        private val BOLD_MARK = Regex("\\*\\*")
        private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    }
}
