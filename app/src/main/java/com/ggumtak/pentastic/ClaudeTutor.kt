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
 * Android 의존성이 없어서 JVM에서 그대로 테스트할 수 있다.
 */
class ClaudeTutor(
    apiKey: String,
    private val model: String,
    baseUrl: String? = null,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    /** 지금까지 주고받은 메시지 (첫 user 메시지에 이미지가 들어 있다). */
    private val history = mutableListOf<MessageParam>()

    @Volatile
    var cancelled = false

    /**
     * 질문을 보내고 답변을 스트리밍으로 받는다. 첫 질문에만 이미지를 붙인다.
     * 성공하면 history에 이번 질문과 답변을 추가한다.
     * @return 최종 답변 텍스트
     * @throws TutorException 사용자에게 보여줄 한국어 메시지를 담은 오류
     */
    fun ask(image: ByteArray?, imageMediaType: String, question: String, onDelta: (String) -> Unit): String {
        val userContent = buildList {
            if (image != null && history.isEmpty()) {
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
            .system(SYSTEM_PROMPT)
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
                    if (cancelled) return ""
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
        return answer
    }

    fun close() {
        cancelled = true
        client.close()
    }

    class TutorException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        const val MODEL_HAIKU = "claude-haiku-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"

        val SYSTEM_PROMPT = """
            당신은 Anki로 일본어를 공부하는 한국인 학습자의 개인 튜터입니다.
            사용자는 Anki 카드 화면의 일부를 캡처한 이미지와 짧은 질문을 보냅니다.

            규칙:
            - 먼저 이미지 속 일본어를 정확히 읽습니다. 흐리거나 잘려서 확실하지 않은 글자는 추측하지 말고 그렇다고 말합니다.
            - 한국어로, 짧고 정확하게 답합니다. 질문에 필요한 내용만 씁니다.
            - 일본어를 인용할 때는 처음 한 번 괄호 안에 히라가나 읽기를 붙입니다. 예: 勉強(べんきょう)
            - "왜 이런 뜻이 되는지" 질문에는 문장을 덩어리로 나눠 각 부분(활용형, 조사, 문형)이 하는 역할을 설명합니다.
            - 한자 예시 요청에는 음독·훈독과 자주 쓰는 단어 3~5개를 읽기·뜻과 함께 듭니다.
            - 화면이 작은 패널이므로 마크다운 기호(#, **, 표, 코드블록)는 쓰지 말고, 일반 문장과 '・' 목록만 씁니다.
            - 확실하지 않은 문법 설명은 확실하지 않다고 밝힙니다.
        """.trimIndent()

        const val Q_GRAMMAR = "이 문장을 자연스럽게 해석하고, 왜 그런 뜻이 되는지 문법을 설명해 줘."
        const val Q_KANJI = "여기 나온 한자의 읽기(음독·훈독)와 자주 쓰는 단어 예시를 알려 줘."
        const val Q_WORDS = "나온 단어들의 읽기와 뜻을 정리해 줘."
    }
}
