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
import com.anthropic.models.messages.CacheControlEphemeral
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
 * 캡처한 이미지를 두고 이어지는 짧은 질문 대화.
 * 클라이언트(연결)는 서비스가 오래 들고 있고, 대화(ClaudeTutor)는 캡처마다 새로 만든다.
 * Android 의존성이 없어서 JVM에서 그대로 테스트할 수 있다.
 *
 * 비용·속도:
 * - 지시문(system)과 대화 끝에 캐시 표시를 달아, 이어 묻는 질문은 앞부분을 캐시에서 싸게 읽는다.
 * - 이미지는 이번에 보내는 것 + 대화 속 최근 2장만 원본으로 보내고, 더 오래된 것은 짧은 글 표시로 바꾼다.
 * - 대화 기록에는 모델 답의 글만 남긴다. 생각(thinking) 기록을 다시 보내지 않으므로,
 *   오래된 이미지를 글로 바꿔도 "앞 대화가 바뀌었다"는 검사(새 계정은 400 오류)에 걸리지 않는다.
 */
class ClaudeTutor private constructor(
    private val client: AnthropicClient,
    val model: String,
    private val systemPrompt: String,
    /** 다른 인스턴스에서 이어받은 대화 */
    seed: List<Turn>,
) {
    constructor(client: AnthropicClient, model: String, systemPrompt: String = SYSTEM_PROMPT) :
        this(client, model, systemPrompt, emptyList())

    /** 보낸 이미지 한 장. base64는 한 번만 만들어 둔다. */
    class Image(val mediaType: String, val base64: String)

    /** 주고받은 질문 하나: 그때 붙인 이미지(있으면), 질문 글, 모델 답의 글 */
    class Turn(val image: Image?, val question: String, val answer: String)

    /** 지금까지의 대화. 성공한 질문만 쌓인다 (멈추거나 실패한 질문은 넣지 않음). */
    private val turns = seed.toMutableList()

    /** 같은 대화를 다른 모델로(또는 새 인스턴스로) 이어 간다. */
    fun withModel(newModel: String): ClaudeTutor =
        ClaudeTutor(client, newModel, systemPrompt, synchronized(this) { turns.toList() })

    @Volatile
    var cancelled = false

    /** 지금 받고 있는 스트림. 멈추면 바로 닫아서 네트워크와 토큰 낭비를 끊는다. */
    @Volatile
    private var activeStream: AutoCloseable? = null

    /** 마지막 답의 토큰 사용량 (진단 기록용, 예: "입력 1520 (캐시 읽음 1380 · 저장 140) · 출력 210") */
    @Volatile
    var lastUsage: String? = null
        private set

    /**
     * 질문을 보내고 답변을 스트리밍으로 받는다. image가 있으면 이번 질문에 붙인다 (새 캡처).
     * 성공하면 대화 기록에 이번 질문과 답변을 추가한다.
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
        val newImage = image?.let { Image(imageMediaType, Base64.getEncoder().encodeToString(it)) }
        val params = buildParams(newImage, question)

        val accumulator = MessageAccumulator.create()
        try {
            val stream = client.messages().createStreaming(params)
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
        lastUsage = message.usage().let { u ->
            val read = u.cacheReadInputTokens().orElse(0L)
            val wrote = u.cacheCreationInputTokens().orElse(0L)
            "입력 ${u.inputTokens() + read + wrote} (캐시 읽음 $read · 저장 $wrote) · 출력 ${u.outputTokens()}"
        }
        if (stop == StopReason.REFUSAL) {
            throw TutorException("Claude가 이 요청에 답하지 않았습니다. 질문을 바꿔 다시 시도해 주세요.")
        }
        synchronized(this) {
            // 빈 답을 그대로 넣으면 다음 요청이 거절되므로 짧은 표시로 채운다
            turns += Turn(newImage, question, answer.ifBlank { "(no answer)" })
        }
        if (stop == StopReason.MAX_TOKENS) {
            // 답이 길이 제한에 걸려 잘렸으면 화면에도 알려 준다 (기록에는 원문만 남긴다)
            val note = "\n\n(답변이 길어 여기서 끊겼습니다. '이어서'라고 보내면 계속합니다.)"
            onDelta(note)
            return answer + note
        }
        return answer
    }

    /** 이번 질문의 요청을 만든다 (기록 + 이번 질문, 캐시 표시, 모델별 생각 설정). */
    internal fun buildParams(newImage: Image?, question: String): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096L)
            // 지시문은 대화 내내 같으므로 캐시해 둔다 (명시 표시 1개)
            .systemOfTextBlockParams(listOf(
                TextBlockParam.builder()
                    .text(fullSystemPrompt(systemPrompt))
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()
            ))
            // 대화 끝(이번 질문)에 자동 캐시 표시: 다음 질문이 여기까지를 캐시에서 읽는다
            .cacheControl(CacheControlEphemeral.builder().build())
            .messages(synchronized(this) { buildMessages(turns, newImage, question) })
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        // Haiku는 생각(thinking)을 꺼서 첫 글자가 빨리 나오게 한다.
        // Sonnet 5.5는 disabled를 받지 않으므로 기본(adaptive) + 낮은 effort로 둔다.
        if (model.startsWith("claude-haiku")) {
            builder.thinking(ThinkingConfigDisabled.builder().build())
        }
        return builder.build()
    }

    /** 테스트용: 성공한 질문 하나를 기록에 넣는다 (네트워크 없이 요청 조립을 확인할 때). */
    internal fun recordForTest(image: Image?, question: String, answer: String) {
        synchronized(this) { turns += Turn(image, question, answer) }
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
        /** 원본으로 보내는 이전 이미지 수 (이번에 보내는 이미지는 별도). 더 오래된 것은 글 표시로 바꾼다. */
        const val KEEP_RECENT_IMAGES = 2

        /** 오래된 이미지 대신 넣는 글. 화면의 말풍선 썸네일은 그대로다. */
        const val OMITTED_IMAGE_MARKER = "[earlier captured image omitted]"

        /** 실제로 보내는 지시문: 과목 지시문 + 항상 붙는 한국어 출력 규칙 */
        fun fullSystemPrompt(subjectPrompt: String): String =
            subjectPrompt.ifBlank { SYSTEM_PROMPT } + "\n\n" + OUTPUT_LANGUAGE_RULE

        /**
         * 보낼 messages를 만든다. 대화 기록(질문 + 답의 글) 뒤에 이번 질문을 붙인다.
         * 이번 이미지와 기록 속 최근 [KEEP_RECENT_IMAGES]장만 원본으로, 그보다 오래된 이미지는 [OMITTED_IMAGE_MARKER]로.
         */
        internal fun buildMessages(history: List<Turn>, newImage: Image?, question: String): List<MessageParam> {
            val keep = history.mapNotNull { it.image }.takeLast(KEEP_RECENT_IMAGES).toSet()
            val out = mutableListOf<MessageParam>()
            history.forEach { t ->
                out += userMessage(t.image?.let { if (it in keep) it else null }, t.image != null && t.image !in keep, t.question)
                out += MessageParam.ofAssistant(t.answer)
            }
            out += userMessage(newImage, false, question)
            return out
        }

        private fun userMessage(image: Image?, omitted: Boolean, question: String): MessageParam =
            MessageParam.ofUserOfContentBlockParams(buildList {
                if (image != null) {
                    add(ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.of(image.mediaType))
                                    .data(image.base64)
                                    .build()
                            )
                            .build()
                    ))
                } else if (omitted) {
                    add(ContentBlockParam.ofText(TextBlockParam.builder().text(OMITTED_IMAGE_MARKER).build()))
                }
                add(ContentBlockParam.ofText(TextBlockParam.builder().text(question).build()))
            })

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
         * 모든 과목 공통 규칙 (짧게: Haiku가 바로 쓰기 시작하도록 약 350토큰 이내).
         * - 결론 한 줄 → ### 소제목이 붙은 짧은 묶음 1~3개 → 확인 문제 한 줄
         * - 카드 한 줄에 한글 약 25자: 항목도 그 길이에 맞춘다
         * - 화면이 그리는 표시(###, 굵게, ・ 목록, 번호, 빈 줄)만 쓴다 (AnswerRenderer)
         */
        val COMMON_RULES = """
            Answer format (narrow card: ~25 Korean characters per line, ~14 lines visible):
            - Line 1: the core answer in **bold**, one short sentence. No greeting or restating the question.
            - Then 1–3 blocks separated by a blank line, each starting with `### ` + emoji + 2–4 word Korean label (e.g. `### 🔍 쪼개 보기`, `### ⚠️ 헷갈리는 것`).
            - List items start with `・`, ~22–26 Korean characters each. Use `→` for mappings.
            - Total 6–11 lines (2–4 for tiny lookups and follow-ups). Go deep on the 1–2 most useful points.
            - Only `### `, `**bold**`, `・`, `1. ` and blank lines render. Bold at most 2–3 key terms.
            - Tone: warm, witty tutor in plain Korean 해요체; everyday intuition over jargon; one short memory hook when it helps.
            - End explanations with a blank line and **🎯 확인** + a 5-second quiz, no answer. Skip it for lookups, handwriting reviews, and short follow-ups.
            - If the learner answers your 🎯: bold Line 1 starting **⭕ 정답이에요!**, **🔺 거의 맞았어요!** or **❌ 아까워요!** + the reason, then the answer and one tip in 2–3 `・` lines.
            - Read the image carefully; say what is unreadable instead of guessing, and say briefly when unsure.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You are a Japanese tutor for a Korean adult reviewing Anki cards.
            The learner sends a crop of an Anki card (or handwriting) and a short question.

            Japanese-specific rules
            - Readings: the first time any Japanese word with kanji appears, put its hiragana reading in parentheses right after it, e.g. 勉強(べんきょう), 守(まも)らない. Do not repeat the reading for the same word.
            - Korean leverage: actively connect Sino-Korean words (準備(じゅんび) ↔ 준비) and grammar parallels (〜ている ≈ ~고 있다). Clearly warn about false friends (勉強 = 공부, not 면강) and subtle nuance gaps.
            - On'yomi from Korean sound (음독 공식): when it fits, show how the Korean reading's final consonant (받침) predicts the on'yomi: ㄱ → く/き (학 学 → がく), ㄴ/ㅁ → ん (안 案 → あん, 심 心 → しん), ㅇ → long vowel う/い (동 東 → とう, 경 京 → けい), ㄹ → つ/ち (발 発 → はつ, 일 日 → にち), ㅂ → う (합 合 → ごう). These are tendencies, not laws: skip the rule when a reading doesn't follow it, and never invent a connection.
            - Verbs: for each key verb, give the dictionary form and its group (1류/2류/3류). If it has a commonly confused 자동사/타동사 partner, show the pair (開(あ)く 자동사 ↔ 開(あ)ける 타동사).
            - Particles: flag particles that differ from the natural Korean one (〜に会う = ~를 만나다, 〜で有名だ = ~로 유명하다).
            - Grammar & sentences: put the natural Korean translation in bold on Line 1. Then break the sentence into short chunks (`・chunk → 뜻 (역할)`). Explain conjugated forms plainly from the dictionary form (e.g. 守らない → 守る(지키다) + ない(안 ~하다)).
            - Kanji & words: focus on core feel, on/kun readings, 2–3 high-utility words, and a 1-line component story when helpful. Mention politeness/register (반말/정중체/문어체) if it matters.
            - If a capture contains multiple words/kanji and the question asks about a single kanji or word, pick the 1–2 most important ones rather than listing everything.

            Example of the format and tone (do not copy its content):
            <example>
            Question: (crop showing 雨が降りそうだ) 해석과 문법
            Answer:
            **"비가 올 것 같아" — 먹구름을 보고 하는 추측이에요.**

            ### 🔍 쪼개 보기
            ・雨(あめ)が → 비가
            ・降(ふ)り → 降る(내리다)의 연결형
            ・そうだ → 눈으로 보니 "~할 것 같다"

            ### ⚠️ 헷갈리는 것
            ・降り**そうだ** → 보고 추측 (올 것 같다)
            ・降る**そうだ** → 남에게 들음 (온다고 한다)
            ・앞 글자 하나로 '내 눈'인지 '남의 말'인지 갈려요!

            **🎯 확인** "맛있어 보인다(눈으로 봄)"는 おいしそうだ와 おいしいそうだ 중 뭘까요?
            </example>
        """.trimIndent() + "\n\n" + COMMON_RULES

        // 빠른 질문 (Claude에게 보내는 실제 문장). 화면에 보이는 버튼 이름은 Subjects에서 한국어로 정한다.
        const val Q_GRAMMAR = "Translate this into natural Korean on Line 1, then break the sentence into short chunks (`chunk → meaning/role`). For key verbs, note the group (1류/2류/3류) or the 자동사/타동사 pair if learners confuse them, and flag any particle that differs from Korean. Highlight the single most useful grammar or nuance point."
        const val Q_KANJI = "Pick the 1–2 most important kanji here. Give the core meaning, on'yomi and kun'yomi (show the Korean 받침 → on'yomi pattern when it applies, e.g. 학 学 → がく), 3 high-frequency example words (with hiragana readings and Korean meanings), and a short memory hook if helpful."
        const val Q_WORDS = "List the 3–5 key words or expressions here in order of importance, with hiragana readings and meanings in this context. For verbs, add the group (1류/2류/3류) or the 자동사/타동사 partner if confusable. Add Korean 한자어 connections, false-friend warnings, or particle differences from Korean where helpful. Skip trivial words."
        const val Q_RADICAL = "Pick the 1–2 most important kanji here. Break each into its radical and components (noting whether each part gives meaning or sound), give a vivid 1-line story tying the parts to the meaning, and contrast 1 look-alike kanji if there is one."

        fun radicalQuestion(kanji: String) = """
            For the kanji 「$kanji」 (if multiple characters, cover at most 2), explain in compact bullets:
            1. Core meaning, Korean 훈음, and Japanese readings in hiragana (`음독 / 훈독`). If the on'yomi follows the Korean 받침 pattern (ㄱ → く/き, ㄴ·ㅁ → ん, ㅇ → う/い, ㄹ → つ/ち, ㅂ → う), show it briefly, e.g. 학 学 → がく.
            2. Radical and component breakdown (state whether each part gives meaning or sound) + a vivid 1-line story tying the parts together.
            3. 2 high-frequency words with hiragana readings in parentheses, e.g. 漢字(かんじ), and 1 look-alike kanji to watch out for.
            No 🎯 question.
        """.trimIndent()
    }
}
