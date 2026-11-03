package com.ggumtak.pentastic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigDisabled
import java.util.Base64

/**
 * 캡처한 이미지 한 장을 두고 이어지는 짧은 일본어 질문 대화.
 * 클라이언트(연결)는 서비스가 오래 들고 있고, 대화(ClaudeTutor)는 캡처마다 새로 만든다.
 * Android 의존성이 없어서 JVM에서 그대로 테스트할 수 있다.
 */
class ClaudeTutor(
    private val client: AnthropicClient,
    val model: String,
    private val systemPrompt: String = SYSTEM_PROMPT,
    /** 다른 모델에서 이어받은 대화 (질문 메시지, 답변 글). */
    seed: List<Pair<MessageParam, String>> = emptyList(),
) {
    /** 지금까지 주고받은 메시지 (질문에는 캡처 이미지가 들어 있다). 이 모델이 만든 답변은 원본 그대로. */
    private val history = mutableListOf<MessageParam>()

    /** 모델을 바꿀 때 넘겨줄 대화: 질문 원본 + 답변 글만 (모델 내부 생각 기록은 빼서 어느 모델에도 유효) */
    private val transcript = mutableListOf<Pair<MessageParam, String>>()

    init {
        seed.forEach { (user, answer) ->
            history += user
            history += MessageParam.ofAssistant(answer)
            transcript += user to answer
        }
    }

    /** 같은 대화를 다른 모델로 이어 간다. */
    fun withModel(newModel: String): ClaudeTutor =
        ClaudeTutor(client, newModel, systemPrompt, synchronized(this) { transcript.toList() })

    @Volatile
    var cancelled = false

    /** 지금 받고 있는 스트림. 멈추면 바로 닫아서 네트워크와 토큰 낭비를 끊는다. */
    @Volatile
    private var activeStream: AutoCloseable? = null

    /**
     * 질문을 보내고 답변을 스트리밍으로 받는다. image가 있으면 이번 질문에 붙인다 (새 캡처).
     * 성공하면 history에 이번 질문과 답변을 추가한다.
     * @return 최종 답변 텍스트
     * @throws TutorException 사용자에게 보여줄 한국어 메시지를 담은 오류
     */
    fun ask(
        image: ByteArray?,
        imageMediaType: String,
        question: String,
        shouldStop: () -> Boolean = { cancelled },
        onDelta: (String) -> Unit,
    ): String {
        val userContent = buildList {
            if (image != null) {
                add(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.of(imageMediaType))
                                    .data(Base64.getEncoder().encodeToString(image))
                                    .build()
                            )
                            .build()
                    )
                )
            }
            add(ContentBlockParam.ofText(TextBlockParam.builder().text(question).build()))
        }
        val userMessage = MessageParam.ofUserOfContentBlockParams(userContent)

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(systemPrompt.ifBlank { SYSTEM_PROMPT } + "\n\n" + OUTPUT_LANGUAGE_RULE)
            .messages(synchronized(this) { history + userMessage })
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        // Haiku는 생각(thinking)을 꺼서 첫 글자가 빨리 나오게 한다.
        // Sonnet 5.5는 disabled를 받지 않으므로 기본(adaptive) + 낮은 effort로 둔다.
        if (model.startsWith("claude-haiku")) {
            builder.thinking(ThinkingConfigDisabled.builder().build())
        }

        val accumulator = MessageAccumulator.create()
        try {
            val stream = client.messages().createStreaming(builder.build())
            activeStream = stream
            try {
                // 스트림을 여는 사이에 멈췄으면 바로 끝낸다 (finally에서 닫힘)
                if (cancelled || shouldStop()) return ""
                for (raw in stream) {
                    if (cancelled || shouldStop()) return ""
                    val event = accumulator.accumulate(raw)
                    event.contentBlockDelta().flatMap { d -> d.delta().text() }
                        .ifPresent { t -> onDelta(t.text()) }
                }
            } finally {
                activeStream = null
                stream.close()
            }
        } catch (e: Exception) {
            // 멈춤으로 스트림을 닫아서 난 오류는 오류로 보이지 않는다
            if (cancelled || shouldStop()) return ""
            throw toTutorException(e)
        }
        // 마지막 이벤트를 받는 사이에 멈췄으면 이 답은 대화 기록에 넣지 않는다
        if (cancelled || shouldStop()) return ""

        val message = accumulator.message()
        val answer = message.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw TutorException("Claude가 이 요청에 답하지 않았습니다. 질문을 바꿔 다시 시도해 주세요.")
        }
        synchronized(this) {
            history += userMessage
            history += message.toParam()
            transcript += userMessage to answer
        }
        if (stop == StopReason.MAX_TOKENS) {
            // 답이 길이 제한에 걸려 잘렸으면 화면에도 알려 준다 (기록에는 원문만 남긴다)
            val note = "\n\n(답변이 길어 여기서 끊겼습니다. '이어서'라고 보내면 계속합니다.)"
            onDelta(note)
            return answer + note
        }
        return answer
    }

    /** SDK 오류를 사용자에게 보여줄 한국어 메시지로 바꾼다. 모르는 오류는 그대로 둔다. */
    private fun toTutorException(e: Exception): Exception = when (e) {
        is UnauthorizedException -> TutorException("API 키가 올바르지 않습니다. 설정에서 키를 다시 확인해 주세요.", e)
        is PermissionDeniedException -> TutorException("이 API 키로는 사용할 수 없습니다. (권한 없음)", e)
        is NotFoundException -> TutorException("모델을 찾을 수 없습니다: $model", e)
        is RateLimitException -> TutorException("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", e)
        is BadRequestException -> {
            val msg = e.message ?: ""
            if (msg.contains("credit", ignoreCase = true)) {
                TutorException("API 크레딧이 부족합니다. console.anthropic.com에서 충전해 주세요.", e)
            } else TutorException("요청 오류: $msg", e)
        }
        is InternalServerException -> TutorException("Claude 서버 오류입니다. 잠시 후 다시 시도해 주세요.", e)
        is AnthropicServiceException -> TutorException("API 오류 (${e.statusCode()}): ${e.message}", e)
        is AnthropicIoException -> TutorException("인터넷 연결을 확인해 주세요.", e)
        else -> e
    }

    /** 진행 중인 답변을 멈춘다. 공유 클라이언트는 닫지 않는다. */
    fun cancel() {
        cancelled = true
        // 받고 있던 스트림을 닫아 서버 쪽 생성도 끊는다 (읽던 스레드는 오류로 빠져나와 조용히 끝난다)
        try {
            activeStream?.close()
        } catch (_: Exception) {
        }
    }

    class TutorException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        fun newClient(apiKey: String, baseUrl: String? = null): AnthropicClient =
            AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .apply { if (baseUrl != null) baseUrl(baseUrl) }
                .build()

        /**
         * 첫 질문이 느리지 않게 SDK 클래스(요청 조립·JSON 변환)를 미리 불러 둔다. 네트워크는 쓰지 않는다.
         */
        fun warmUpClasses() {
            try {
                val params = MessageCreateParams.builder()
                    .model(MODEL_HAIKU)
                    .maxTokens(16L)
                    .system("warm")
                    .addUserMessageOfContentBlockParams(
                        listOf(
                            ContentBlockParam.ofImage(
                                ImageBlockParam.builder().source(
                                    Base64ImageSource.builder()
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                                        .data("AA==")
                                        .build()
                                ).build()
                            ),
                            ContentBlockParam.ofText(TextBlockParam.builder().text("warm").build()),
                        )
                    )
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .thinking(ThinkingConfigDisabled.builder().build())
                    .build()
                com.anthropic.core.jsonMapper().writeValueAsString(params._body())
                MessageAccumulator.create()
            } catch (_: Throwable) {
            }
        }

        /**
         * API 서버와의 연결(TLS)을 미리 열어 둔다. 토큰을 쓰지 않는 모델 목록 조회라 비용이 없다.
         * 화면을 고르는 동안 실행해서, 질문을 누르는 순간 바로 전송되게 한다.
         */
        fun prewarmConnection(client: AnthropicClient) {
            try {
                client.models().list()
            } catch (_: Throwable) {
            }
        }

        const val MODEL_HAIKU = "claude-haiku-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"

        /**
         * 지시문은 영어로 쓰고(모델이 가장 정확하게 따름), 답은 항상 한국어로 받는다.
         * 사용자가 설정에서 지시문을 고쳐도 이 규칙은 항상 붙는다.
         */
        val OUTPUT_LANGUAGE_RULE = """
            Output language (mandatory): write your entire answer in Korean, regardless of the language of these instructions, the image, or the question. Only quoted source text (Japanese, English, Hanja, etc.) stays in its original language.
        """.trimIndent()

        /**
         * 모든 과목 공통 규칙: 작은 카드에서 한눈에 읽히고, 기억에 남고, 가볍게 재미있게.
         * - 결론 한 줄 → 이모지 이름표가 붙은 짧은 묶음 2~3개 → 확인 문제 한 줄
         * - 화면이 그릴 수 있는 것(굵게, ・ 목록, 번호, 빈 줄)만 쓴다 (AnswerRenderer)
         * - 기억 고리는 짧게: 지나치게 복잡한 연상법은 오히려 뜻을 흐린다는 연구가 있다
         * - 확인 문제(인출 연습)는 다시 읽기보다 기억에 오래 남는다
         */
        val COMMON_RULES = """
            How to write the answer (it appears in a small card floating over the study app on a tablet):

            Shape
            - Line 1: the bottom line in **bold**, one sentence that directly answers the question. No greeting, no restating the question, no closing remarks.
            - Then 2–3 short blocks separated by one blank line. Each block starts with its own label line: an emoji + a 2–5 word Korean label, in bold (e.g. **🔍 쪼개 보기**, **💡 기억 포인트**, **⚠️ 헷갈리기 쉬운 것**). Pick labels that fit the content; don't force all of them.
            - Inside a block: one idea per line, short lines (about 40 Korean characters or less), list items start with '・'. Use '→' for "becomes / means / leads to". Use '1.' only for real sequences.
            - Length: usually 6–12 lines in total. A tiny question (one word) gets 2–4 lines. Go deep on the 1–2 points that matter most instead of covering everything.
            - Only these render: **bold**, '・' bullets, '1.' numbered lines, blank lines. Do not use tables, headings (#), code blocks, links, or italics.
            - Bold only 2–4 things worth remembering per answer. If everything is bold, nothing stands out.

            Teaching
            - Tone: a friendly, sharp tutor speaking plain Korean in 해요체. A light touch of humor or a vivid image is welcome when it helps something stick, but accuracy always comes first.
            - Make it stick: when it genuinely helps, add one short memory hook (the logic behind a form, a vivid association, or a contrast with a look-alike). Keep hooks simple; an elaborate mnemonic is worse than none.
            - End explanation-type answers with one last line: **🎯 확인** followed by one quick question the learner can answer in their head or reply to (fill a blank, choose between two forms, translate a short phrase). Do not give the answer. Skip this for simple lookups and for answers to follow-up questions that are already short.
            - If the learner's message answers your previous 🎯 question, start with ✓ or ✗ and one line on why, give the correct answer, and add one tip if useful. No new 🎯 unless they ask for more.

            Accuracy
            - Read the image carefully before answering. If text is blurry or cut off, say what you cannot read instead of guessing.
            - If you are unsure about a fact, reading, or rule, say so briefly rather than sounding certain.
            - If a new image arrives in the same conversation, answer about the new image, using earlier context only when it helps.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a Japanese tutor for a Korean learner who reviews Japanese with Anki.
            The learner sends a crop of an Anki card (or handwriting) and a short question.

            Japanese-specific rules
            - Readings: the first time you quote a Japanese word containing kanji, add its hiragana reading in parentheses, e.g. 勉強(べんきょう). Don't repeat the reading for the same word.
            - Use Korean as leverage: point out when a word matches a Sino-Korean word (準備 ↔ 준비) or a pattern maps closely to Korean (〜ている ≈ -고 있다), and clearly flag where the analogy breaks: false friends (勉強 = 공부, not 면강) and different nuance.
            - Grammar: give a natural Korean translation first, then split the sentence into chunks, one per line, as "chunk → role → nuance". For conjugated forms, name the dictionary form and the form used (e.g. 食べられる → 食べる의 가능형).
            - Kanji: on'yomi and kun'yomi, core meaning, 3–5 high-frequency words (reading + meaning) ordered by usefulness, and a one-line hook from the kanji's components when it genuinely helps.
            - Words: reading, meaning in this context, part of speech; add one natural short example only when it adds something.
            - Mention register (polite / casual / written / spoken) in a few words when it matters for using the expression correctly.

            Example of the format and tone (do not copy its content):
            <example>
            Question: (crop showing 雨が降りそうだ) 해석과 문법
            Answer:
            **"비가 올 것 같다" — 하늘을 보고 하는 추측이에요.**

            **🔍 쪼개 보기**
            ・雨(あめ)が → 비가 (주어)
            ・降り → 降る(ふる, 내리다)의 ます형 어간
            ・そうだ → 보이는 모양으로 "~할 것 같다"

            **⚠️ 헷갈리기 쉬운 것**
            ・ます형 어간 + そうだ → 눈으로 본 추측
            ・보통형 + そうだ → "~라고 한다" (전해 들음)
            ・글자 하나 차이로 뜻이 완전히 달라져요

            **🎯 확인** 降るそうだ는 무슨 뜻일까요?
            </example>
        """.trimIndent() + "\n\n" + COMMON_RULES

        // 빠른 질문 (Claude에게 보내는 실제 문장). 화면에 보이는 버튼 이름은 Subjects에서 한국어로 정한다.
        const val Q_GRAMMAR = "Translate this into natural Korean, then break it into chunks and explain how each part creates that meaning. Point out the one grammar point most worth remembering."
        const val Q_KANJI = "For the kanji here, give the on'yomi and kun'yomi, the core meaning, and the most useful example words. If a component-based hook helps remember it, add one."
        const val Q_WORDS = "List the words here with their readings and meanings in this context. Most important words first; skip trivially easy ones."
        const val Q_RADICAL = "For the kanji here, give the radical and the components (does each part carry meaning or sound?), the stroke count, and a one-line hook that ties the parts to the meaning."

        fun radicalQuestion(kanji: String) =
            "For the kanji 「$kanji」, give: the radical; the components (does each part carry meaning or sound?); total stroke count; on'yomi and kun'yomi; core meaning; 2–3 common words; a one-line hook tying the parts to the meaning; and look-alike kanji that are easy to confuse with it, with how to tell them apart."
    }
}
