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
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
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

    /** 모르는 정보가 있을 때 Claude가 스스로 판단해 웹 검색을 쓸 수 있게 할지 */
    @Volatile
    var webSearch = false

    /** 마지막 질문에서 이 API 키로 웹 검색을 쓸 수 없어 검색 없이 답했는지 */
    var searchUnavailable = false
        private set

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
        /** 웹 검색을 시작할 때 null, 검색어를 알게 되면 그 검색어로 불린다 */
        onSearch: (String?) -> Unit = {},
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

        searchUnavailable = false
        var useSearch = webSearch && !searchBlocked
        val answer = StringBuilder()
        // 서버 검색이 한 번에 끝나지 않으면(pause_turn) 받은 데까지를 붙여 이어서 요청한다
        val assistantTurns = mutableListOf<MessageParam>()
        val sources = LinkedHashSet<String>()
        var rounds = 0
        while (true) {
            val params = buildParams(history + userMessage + assistantTurns, useSearch)
            val message = try {
                streamOnce(params, shouldStop, answer, onSearch, onDelta) ?: return ""
            } catch (e: BadRequestException) {
                // 조직 설정에서 웹 검색이 꺼져 있는 등 검색 도구를 못 쓰면, 검색 없이 다시 묻는다
                val msg = e.message ?: ""
                if (useSearch && answer.isEmpty() && assistantTurns.isEmpty() &&
                    (msg.contains("web_search", ignoreCase = true) || msg.contains("web search", ignoreCase = true))
                ) {
                    searchBlocked = true
                    searchUnavailable = true
                    useSearch = false
                    continue
                }
                throw mapError(e)
            } catch (e: AnthropicServiceException) {
                throw mapError(e)
            } catch (e: AnthropicIoException) {
                throw mapError(e)
            }
            collectSources(message, sources)
            val stop = message.stopReason().orElse(null)
            if (stop == StopReason.REFUSAL) {
                throw TutorException("Claude가 이 요청에 답하지 않았습니다. 질문을 바꿔 다시 시도해 주세요.")
            }
            assistantTurns += message.toParam()
            if (stop == StopReason.PAUSE_TURN && rounds++ < 3) continue
            break
        }
        // 검색해서 답했으면 끝에 출처(사이트 이름)를 짧게 붙인다
        if (sources.isNotEmpty()) {
            val tail = "\n\n🔎 출처: " + sources.take(4).joinToString(" · ")
            answer.append(tail)
            onDelta(tail)
        }
        history += userMessage
        history += assistantTurns
        val text = answer.toString()
        transcript += userMessage to text
        return text
    }

    private fun buildParams(messages: List<MessageParam>, useSearch: Boolean): MessageCreateParams {
        val system = systemPrompt.ifBlank { SYSTEM_PROMPT }
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            .system(if (useSearch) system + "\n\n" + SEARCH_GUIDE else system)
            .messages(messages)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        // Haiku는 생각(thinking)을 꺼서 첫 글자가 빨리 나오게 한다.
        // Sonnet 5.5는 disabled를 받지 않으므로 기본(adaptive) + 낮은 effort로 둔다.
        val haiku = model.startsWith("claude-haiku")
        if (haiku) {
            builder.thinking(ThinkingConfigDisabled.builder().build())
        }
        if (useSearch) {
            // 검색은 Claude가 필요하다고 판단할 때만 쓴다 (한 질문에 최대 3번).
            // Haiku는 기본 검색, Sonnet은 결과를 걸러 읽는 새 검색을 쓴다.
            if (haiku) builder.addTool(WebSearchTool20250305.builder().maxUses(MAX_SEARCHES).build())
            else builder.addTool(WebSearchTool20260209.builder().maxUses(MAX_SEARCHES).build())
        }
        return builder.build()
    }

    /** 한 번 요청해서 답을 흘려받는다. 멈추면 null. */
    private fun streamOnce(
        params: MessageCreateParams,
        shouldStop: () -> Boolean,
        answer: StringBuilder,
        onSearch: (String?) -> Unit,
        onDelta: (String) -> Unit,
    ): Message? {
        val accumulator = MessageAccumulator.create()
        // 검색어는 조각(JSON)으로 흘러오므로 블록별로 모았다가 블록이 끝날 때 읽는다
        val queries = HashMap<Long, StringBuilder>()
        // 검색을 끼고 나뉜 글 사이에만 줄을 띄운다 (출처가 달린 글은 문장 중간에서도 블록이 나뉘므로)
        var toolSinceText = false
        val stream = client.messages().createStreaming(params)
        try {
            for (raw in stream) {
                if (cancelled || shouldStop()) return null
                val event = accumulator.accumulate(raw)
                event.contentBlockStart().ifPresent { st ->
                    val b = st.contentBlock()
                    when {
                        b.isServerToolUse() -> {
                            toolSinceText = true
                            queries[st.index()] = StringBuilder()
                            onSearch(null)
                        }
                        b.isText() -> {
                            if (toolSinceText && answer.isNotEmpty() && !answer.endsWith("\n")) {
                                answer.append("\n\n")
                                onDelta("\n\n")
                            }
                            toolSinceText = false
                        }
                        else -> if (!b.isThinking() && !b.isRedactedThinking()) toolSinceText = true
                    }
                }
                event.contentBlockDelta().ifPresent { d ->
                    val delta = d.delta()
                    delta.text().ifPresent { t ->
                        answer.append(t.text())
                        onDelta(t.text())
                    }
                    delta.inputJson().ifPresent { j -> queries[d.index()]?.append(j.partialJson()) }
                }
                event.contentBlockStop().ifPresent { st ->
                    queries.remove(st.index())?.let { q -> onSearch(queryOf(q.toString())) }
                }
            }
        } finally {
            stream.close()
        }
        return accumulator.message()
    }

    private fun queryOf(json: String): String? = try {
        com.anthropic.core.jsonMapper().readTree(json)?.get("query")?.asText()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** 답에 달린 웹 출처를 사이트 이름(도메인)으로 모은다 */
    private fun collectSources(message: Message, out: MutableSet<String>) {
        message.content().forEach { b ->
            b.text().ifPresent { t ->
                t.citations().ifPresent { list ->
                    list.forEach { c ->
                        c.webSearchResultLocation().ifPresent { w ->
                            val host = try {
                                java.net.URI(w.url()).host?.removePrefix("www.")
                            } catch (_: Exception) {
                                null
                            }
                            if (!host.isNullOrBlank()) out += host
                        }
                    }
                }
            }
        }
    }

    private fun mapError(e: Exception): TutorException = when (e) {
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
        else -> TutorException("오류: ${e.message}", e)
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

        /** 한 질문에서 웹 검색을 최대 몇 번 쓸지 */
        private const val MAX_SEARCHES = 3L

        /** 이 API 키(조직)에서 웹 검색이 막혀 있으면 앱을 다시 켤 때까지 검색 없이 묻는다 */
        @Volatile
        private var searchBlocked = false

        /** 웹 검색을 켰을 때 지시문 끝에 붙이는 안내. 검색은 꼭 필요할 때만 (느리고 비용이 든다). */
        val SEARCH_GUIDE = """
            웹 검색(web_search)을 쓸 수 있습니다. 다음 규칙으로 스스로 판단합니다.
            - 이미지와 이미 아는 지식으로 확실하게 답할 수 있으면 검색하지 않습니다. 일반적인 문법·단어·한자 설명은 대부분 검색이 필요 없습니다.
            - 최신 정보, 특정 인물·작품·회사·사건 같은 고유명사, 신조어·유행어, 시험 일정처럼 바뀔 수 있는 사실, 확실하지 않은 사실, 사용자가 찾아 달라고 한 경우에만 검색합니다.
            - 검색하기 전에 "찾아보겠습니다" 같은 말은 쓰지 않습니다. 검색으로 확인한 내용은 답에 자연스럽게 녹이고, 출처 목록은 따로 쓰지 않습니다 (앱이 표시합니다).
        """.trimIndent()

        const val MODEL_HAIKU = "claude-haiku-5-5"
        const val MODEL_SONNET = "claude-sonnet-5-5"

        val SYSTEM_PROMPT = """
            당신은 Anki로 일본어를 공부하는 한국인 학습자의 개인 튜터입니다.
            사용자는 Anki 카드 화면의 일부를 캡처한 이미지와 짧은 질문을 보냅니다.

            규칙:
            - 먼저 이미지 속 일본어를 정확히 읽습니다. 흐리거나 잘려서 확실하지 않은 글자는 추측하지 말고 그렇다고 말합니다.
            - 답은 화면 위에 떠 있는 작은 카드에 표시됩니다. 한국어로, 결론부터, 보통 8줄 이내로 짧고 정확하게 답합니다. 인사말이나 질문 되풀이는 쓰지 않습니다.
            - 일본어를 인용할 때는 처음 한 번 괄호 안에 히라가나 읽기를 붙입니다. 예: 勉強(べんきょう)
            - "왜 이런 뜻이 되는지" 질문에는 문장을 덩어리로 나눠 각 부분(활용형, 조사, 문형)이 하는 역할을 설명합니다.
            - 한자 예시 요청에는 음독·훈독과 자주 쓰는 단어 3~5개를 읽기·뜻과 함께 듭니다.
            - 읽기 쉽게: 한 줄에 한 요점, 목록은 '・'로 시작하고, 핵심 일본어 표현과 결론은 **굵게** 표시합니다. 제목(#), 표, 코드블록은 쓰지 않습니다.
            - 같은 대화에 새 캡처 이미지가 오면, 이전 내용과 이어서 그 이미지에 대해 답합니다.
            - 확실하지 않은 문법 설명은 확실하지 않다고 밝힙니다.
        """.trimIndent()

        const val Q_GRAMMAR = "이 문장을 자연스럽게 해석하고, 왜 그런 뜻이 되는지 문법을 설명해 줘."
        const val Q_KANJI = "여기 나온 한자의 읽기(음독·훈독)와 자주 쓰는 단어 예시를 알려 줘."
        const val Q_WORDS = "나온 단어들의 읽기와 뜻을 정리해 줘."

        fun radicalQuestion(kanji: String) =
            "「$kanji」 한자의 부수, 구성 요소(각 부분이 뜻과 소리 중 무엇을 나타내는지), 총 획수, 음독·훈독, 뜻, 자주 쓰는 단어 2~3개, 모양이 비슷해서 헷갈리는 한자를 알려 줘."
        const val Q_RADICAL = "여기 나온 한자의 부수와 구성 요소(각 부분이 뜻과 소리 중 무엇을 나타내는지), 획수를 알려 줘."
    }
}
