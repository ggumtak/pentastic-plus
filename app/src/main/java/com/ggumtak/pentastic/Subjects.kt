package com.ggumtak.pentastic

/** 과목 하나: 이름, 기본 지시문(프롬프트), 빠른 질문 버튼 3개. */
class Subject(
    val id: String,
    val name: String,
    val defaultPrompt: String,
    /** (버튼 이름, 실제로 보내는 질문). 첫 번째가 '자동으로 바로 물어보기'에 쓰인다. */
    val buttons: List<Pair<String, String>>,
    /** 기본 모델. 사실 확인이 중요한 과목은 Sonnet. */
    val defaultModel: String = ClaudeTutor.MODEL_HAIKU,
)

object Subjects {

    /** 모든 과목 공통 규칙 (작은 화면에서 읽기 쉽게). 지시문은 영어, 답은 한국어 (ClaudeTutor.OUTPUT_LANGUAGE_RULE). */
    private val COMMON = ClaudeTutor.COMMON_RULES

    val JAPANESE = Subject(
        id = "ja",
        name = "일본어",
        defaultPrompt = ClaudeTutor.SYSTEM_PROMPT,
        buttons = listOf(
            "해석·문법" to ClaudeTutor.Q_GRAMMAR,
            "한자 예시" to ClaudeTutor.Q_KANJI,
            "단어 뜻" to ClaudeTutor.Q_WORDS,
            "부수·구성" to ClaudeTutor.Q_RADICAL,
        ),
    )

    val ENGLISH = Subject(
        id = "en",
        name = "영어",
        defaultPrompt = """
            You are a personal tutor for a Korean learner who studies English.
            The user sends an image cropped from an English textbook, article, or test question, together with a short question.

            Rules:
            - First read the English in the image accurately.
            - Translate into natural Korean. For sentence-structure questions, split the sentence into chunks (subject, verb, object, modifiers, clauses) and explain the role of each part.
            - For word questions, briefly give the pronunciation (IPA, not Hangul transliteration), part of speech, meaning in this context, and common collocations.
            - If it is a grammar point that often appears on exams, point it out in one line.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "해석·구문" to "Translate this sentence into natural Korean, and break down its structure (subject, verb, modifiers) chunk by chunk.",
            "단어·표현" to "List the difficult words and expressions with their IPA pronunciation, part of speech, and meaning in this context.",
            "예문" to "Show 3 easy example sentences using the key word or expression, each with a Korean translation.",
        ),
    )

    val HISTORY = Subject(
        id = "hist",
        name = "역사",
        defaultPrompt = """
            You are a history tutor for a Korean learner.
            The user sends an image cropped from a textbook, test question, or source material, together with a short question.

            Rules:
            - State facts such as dates, people, and events only when you are sure of them. If scholars disagree or you are not certain, say so.
            - Explain events in the order background → development → outcome and significance, linking causes to effects.
            - Put important dates, people, and terms in bold.
            - If there are similar events, institutions, or people that students often confuse on exams, point out the difference in one line.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "핵심 설명" to "Explain the key points of this content. Put important dates, people, and terms in bold.",
            "흐름·인과" to "Connect this to the events before and after it, and explain the flow: background → development → outcome and significance.",
            "헷갈리는 것" to "Compare this with similar events or terms that students often confuse on exams, and summarize the differences.",
        ),
        defaultModel = ClaudeTutor.MODEL_SONNET,
    )

    val KOREAN = Subject(
        id = "ko",
        name = "국어",
        defaultPrompt = """
            You are a Korean-language teacher helping a Korean reader understand books.
            The user either sends an image cropped from a book page or types an unfamiliar word directly.

            Rules:
            - When asked about a word: first give, in one line, the meaning that fits this context; then the part of speech; if it is a Sino-Korean word, the Hanja and the meaning of each character; and 1–2 short example sentences.
            - For words with several meanings, make clear which meaning fits this context.
            - If the user did not specify a word, pick 3–5 words in the capture that look difficult and briefly explain their meaning in context.
            - If a sentence is hard, rephrase it in plain, easy Korean.
            - If the word is likely slang, dialect, or archaic and probably not in the Standard Korean Language Dictionary (표준국어대사전), say so.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "단어 뜻" to "For the unfamiliar word, give its meaning in this context, part of speech, the Hanja and per-character meaning if it is Sino-Korean, and a short example sentence.",
            "쉽게 풀기" to "Rephrase this sentence (paragraph) in plain, easy Korean and explain it.",
            "비슷한 말" to "Give synonyms, antonyms, and easily confused words for this word.",
        ),
    )

    val all = listOf(JAPANESE, ENGLISH, HISTORY, KOREAN)

    fun byId(id: String?): Subject = all.firstOrNull { it.id == id } ?: JAPANESE
}
