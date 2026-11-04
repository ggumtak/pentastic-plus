package com.ggumtak.pentastic

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Claude 답변(가벼운 마크다운)을 읽기 좋은 글자 모양으로 바꾼다.
 * - **굵게** → 굵은 글씨 (기호는 숨김). 라이트 모드에서는 연한 형광펜 배경을 깔아 핵심어가 바로 보이게 한다.
 *   답변이 흘러들어오는 중 닫히지 않은 ** 는 잠시 숨긴다.
 * - '・', '-', '*', '•' 로 시작하는 줄 → 들여쓰기된 글머리표
 * - '1.' 같은 번호 줄 → 둘째 줄부터 들여쓰기
 * - '#'~'######' 제목 → 강조색 굵은 글씨 + 위 여백 (묶음 이름표)
 * - 한자 바로 뒤 괄호 속 가나 읽기 '勉強(べんきょう)' → 작고 흐리게 (한자와 설명이 먼저 보이게)
 * - 줄(\n)과 줄 사이에는 작은 간격을 더한다. 한 항목이 화면에서 두 줄로 꺾인 부분은 촘촘하게 둔다.
 * - 빈 줄 → 묶음 사이 작은 간격
 */
class AnswerRenderer(
    private val density: Float,
    private val strongColor: Int,
    private val bulletColor: Int,
    /** 제목(###) 글자색 */
    private val headingColor: Int = strongColor,
    /** 후리가나(괄호 속 읽기) 글자색 */
    private val mutedColor: Int = strongColor,
    /** 굵은 글씨 뒤에 깔 형광펜 색. 0이면 깔지 않는다 (다크 모드). */
    private val boldHighlight: Int = 0,
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
            val textEnd = sb.length
            if (i < lines.lastIndex) sb.append("\n")
            val end = sb.length
            if (end <= start) return@forEachIndexed

            styleFurigana(sb, start, textEnd)
            when {
                content.isEmpty() ->
                    // 빈 줄은 높이를 줄여서 묶음 사이 간격으로만 쓴다
                    sb.setSpan(AbsoluteSizeSpan(dp(7f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                bullet ->
                    sb.setSpan(BulletSpan(dp(10f), bulletColor, dp(2.6f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                numbered ->
                    sb.setSpan(LeadingMarginSpan.Standard(0, dp(20f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                heading -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(headingColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.06f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    // 묶음 이름표 위에 여백 (답의 맨 첫 줄이면 필요 없음)
                    if (start > 0) sb.setSpan(TopGapSpan(dp(6f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            // 줄과 줄 사이 간격: 항목끼리는 떨어지고, 한 항목 안에서 꺾인 줄은 촘촘하게
            if (content.isNotEmpty() && i < lines.lastIndex) {
                sb.setSpan(ParagraphGapSpan(dp(5f)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            if (boldHighlight != 0) {
                sb.setSpan(BackgroundColorSpan(boldHighlight), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
    }

    /** 한자 바로 뒤 괄호 속 가나 읽기를 작고 흐리게. 굵은 글씨 안에 있어도 이 색이 이긴다(나중에 붙인 span). */
    private fun styleFurigana(sb: SpannableStringBuilder, from: Int, to: Int) {
        if (to <= from) return
        val text = sb.subSequence(from, to).toString()
        for (m in FURIGANA.findAll(text)) {
            val s = from + m.range.first
            val e = from + m.range.last + 1
            sb.setSpan(RelativeSizeSpan(0.85f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(mutedColor), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 줄(문단)의 마지막 화면 줄 아래에 간격을 더한다. */
    private class ParagraphGapSpan(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: return
            if (end >= spanEnd) {
                fm.descent += gap
                fm.bottom += gap
            }
        }
    }

    /** 줄(문단)의 첫 화면 줄 위에 간격을 더한다. */
    private class TopGapSpan(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            val spanStart = (text as? Spanned)?.getSpanStart(this) ?: return
            if (start == spanStart) {
                fm.ascent -= gap
                fm.top -= gap
            }
        }
    }

    companion object {
        private val HEADING = Regex("^#{1,6}\\s+")
        private val NUMBERED = Regex("^\\d{1,2}[.)]\\s")
        private val BOLD_MARK = Regex("\\*\\*")
        private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
        /** 한자(々 포함) 바로 뒤의 (かな) 또는 （かな） */
        private val FURIGANA = Regex("(?<=[\\u3400-\\u9FFF\\uF900-\\uFAFF々〆ヵヶ])[(（][ぁ-ゖァ-ヺー・\\s]+[)）]")
    }
}
