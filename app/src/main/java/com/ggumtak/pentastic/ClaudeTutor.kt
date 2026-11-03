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
    fun withModel(newModel: String): ClaudeTutor = ClaudeTutor(client, newModel, systemPrompt, transcript.toList())

    @Volatile
    var cancelled = false

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
            .messages(history + userMessage)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        // Haiku는 생각(thinking)을 꺼서 첫 글자가 빨리 나오게 한다.
        // Sonnet 5.5는 disabled를 받지 않으므로 기본(adaptive) + 낮은 effort로 둔다.
        if (model.startsWith("claude-haiku")) {
            builder.thinking(ThinkingConfigDisabled.builder().build())
        }

        val accumulator = MessageAccumulator.create()
        try {
            val stream = client.messages().createStreaming(builder.build())
            try {
                for (raw in stream) {
                    if (cancelled || shouldStop()) return ""
                    val event = accumulator.accumulate(raw)
                    event.contentBlockDelta().flatMap { d -> d.delta().text() }
                        .ifPresent { t -> onDelta(t.text()) }
                }
            } finally {
                stream.close()
            }
        } catch (e: UnauthorizedException) {
            throw TutorException("API 키가 올바르지 않습니다. 설정에서 키를 다시 확인해 주세요.", e)
        } catch (e: PermissionDeniedException) {
            throw TutorException("이 API 키로는 사용할 수 없습니다. (권한 없음)", e)
        } catch (e: NotFoundException) {
            throw TutorException("모델을 찾을 수 없습니다: $model", e)
        } catch (e: RateLimitException) {
            throw TutorException("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.", e)
        } catch (e: BadRequestException) {
            val msg = e.message ?: ""
            if (msg.contains("credit", ignoreCase = true)) {
                throw TutorException("API 크레딧이 부족합니다. console.anthropic.com에서 충전해 주세요.", e)
            }
            throw TutorException("요청 오류: $msg", e)
        } catch (e: InternalServerException) {
            throw TutorException("Claude 서버 오류입니다. 잠시 후 다시 시도해 주세요.", e)
        } catch (e: AnthropicServiceException) {
            throw TutorException("API 오류 (${e.statusCode()}): ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw TutorException("인터넷 연결을 확인해 주세요.", e)
        }

        val message = accumulator.message()
        val answer = message.content().mapNotNull { b -> b.text().orElse(null)?.text() }.joinToString("")
        val stop = message.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) {
            throw TutorException("Claude가 이 요청에 답하지 않았습니다. 질문을 바꿔 다시 시도해 주세요.")
        }
        history += userMessage
        history += message.toParam()
        transcript += userMessage to answer
        if (stop == StopReason.MAX_TOKENS) {
            // 답이 길이 제한에 걸려 잘렸으면 화면에도 알려 준다 (기록에는 원문만 남긴다)
            val note = "\n\n(답변이 길어 여기서 끊겼습니다. '이어서'라고 보내면 계속합니다.)"
            onDelta(note)
            return answer + note
        }
        return answer
    }

    /** 진행 중인 답변을 멈춘다. 공유 클라이언트는 닫지 않는다. */
    fun cancel() {
        cancelled = true
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

        /** 모든 과목 공통 규칙 (작은 화면에서 읽기 쉽게) */
        val COMMON_RULES = """
            - The answer is shown in a small window floating over the screen. Lead with the conclusion and keep it short and precise, usually within 8 lines. No greetings, and do not restate the question.
            - Make it easy to scan: one point per line, start list items with '・', and put key expressions and the conclusion in **bold**. Do not use headings (#), tables, or code blocks.
            - If a new captured image arrives in the same conversation, answer about that image while building on the earlier context.
            - If characters in the image are blurry or cut off, do not guess; say that you cannot read them reliably. If you are not sure about something, say so.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a personal tutor for a Korean learner who studies Japanese with Anki.
            The user sends an image cropped from an Anki card screen, together with a short question.

            Rules:
            - First read the Japanese in the image accurately.
            - When you quote Japanese, add the hiragana reading in parentheses the first time it appears, e.g. 勉強(べんきょう).
            - For "why does it mean this" questions, split the sentence into chunks and explain the role of each part (conjugated form, particle, grammar pattern).
            - For kanji example requests, give the on'yomi and kun'yomi plus 3–5 common words with their readings and meanings.
            - If you are not sure about a grammar explanation, say that you are not sure.
        """.trimIndent() + "\n" + COMMON_RULES

        // 빠른 질문 (Claude에게 보내는 실제 문장). 화면에 보이는 버튼 이름은 Subjects에서 한국어로 정한다.
        const val Q_GRAMMAR = "Translate this sentence into natural Korean, then explain the grammar that makes it mean that."
        const val Q_KANJI = "Give the readings (on'yomi and kun'yomi) of the kanji shown here, with common example words."
        const val Q_WORDS = "List the words that appear here with their readings and meanings."
        const val Q_RADICAL = "For the kanji shown here, give the radical, the components (whether each part carries meaning or sound), and the stroke count."

        fun radicalQuestion(kanji: String) =
            "For the kanji 「$kanji」, give: its radical; its components (whether each part carries meaning or sound); total stroke count; on'yomi and kun'yomi; meaning; 2–3 common words; and kanji that look similar and are easy to confuse with it."
    }
}
