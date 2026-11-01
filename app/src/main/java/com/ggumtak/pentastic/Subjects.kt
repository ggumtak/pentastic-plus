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

    /** 모든 과목 공통 규칙 (작은 화면에서 읽기 쉽게) */
    private val COMMON = """
        - 답은 화면 위의 작은 창에 표시됩니다. 한국어로, 결론부터, 보통 8줄 이내로 짧고 정확하게 답합니다. 인사말이나 질문 되풀이는 쓰지 않습니다.
        - 읽기 쉽게: 한 줄에 한 요점, 목록은 '・'로 시작하고, 핵심 표현과 결론은 **굵게** 표시합니다. 제목(#), 표, 코드블록은 쓰지 않습니다.
        - 같은 대화에 새 캡처 이미지가 오면, 이전 내용과 이어서 그 이미지에 대해 답합니다.
        - 이미지가 흐리거나 잘려서 확실하지 않은 글자는 추측하지 말고 그렇다고 말합니다. 확실하지 않은 내용은 확실하지 않다고 밝힙니다.
    """.trimIndent()

    val JAPANESE = Subject(
        id = "ja",
        name = "일본어",
        defaultPrompt = ClaudeTutor.SYSTEM_PROMPT,
        buttons = listOf(
            "해석·문법" to ClaudeTutor.Q_GRAMMAR,
            "한자 예시" to ClaudeTutor.Q_KANJI,
            "단어 뜻" to ClaudeTutor.Q_WORDS,
        ),
    )

    val ENGLISH = Subject(
        id = "en",
        name = "영어",
        defaultPrompt = """
            당신은 영어를 공부하는 한국인 학습자의 개인 튜터입니다.
            사용자는 영어 교재·기사·문제 화면의 일부를 캡처한 이미지와 짧은 질문을 보냅니다.

            규칙:
            - 먼저 이미지 속 영어를 정확히 읽습니다.
            - 해석은 자연스러운 한국어로 하고, 구문 설명은 문장을 덩어리(주어·동사·목적어·수식어구·절)로 나눠 각 부분이 하는 역할을 설명합니다.
            - 단어 질문에는 발음(한글 표기 말고 발음기호), 품사, 이 문맥에서의 뜻, 자주 같이 쓰는 표현을 짧게 듭니다.
            - 시험에 자주 나오는 문법 포인트면 한 줄로 짚어 줍니다.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "해석·구문" to "이 문장을 자연스럽게 해석하고, 문장 구조(주어·동사·수식 관계)를 덩어리로 나눠 설명해 줘.",
            "단어·표현" to "어려운 단어와 표현의 발음기호·품사·이 문맥에서의 뜻을 정리해 줘.",
            "예문" to "핵심 단어나 표현을 쓴 쉬운 예문 3개를 해석과 함께 보여 줘.",
        ),
    )

    val HISTORY = Subject(
        id = "hist",
        name = "역사",
        defaultPrompt = """
            당신은 역사를 공부하는 한국인 학습자의 튜터입니다.
            사용자는 교재·문제·자료 화면의 일부를 캡처한 이미지와 짧은 질문을 보냅니다.

            규칙:
            - 연도, 인물, 사건 같은 사실은 확실한 것만 씁니다. 학설이 갈리거나 기억이 확실하지 않으면 그렇다고 밝힙니다.
            - 사건은 배경 → 전개 → 결과·의의 순서로, 원인과 결과가 이어지게 설명합니다.
            - 중요한 연도·인물·용어는 굵게 표시합니다.
            - 시험에서 자주 헷갈리는 비슷한 사건·제도·인물이 있으면 차이를 한 줄로 짚어 줍니다.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "핵심 설명" to "이 내용의 핵심을 설명해 줘. 중요한 연도·인물·용어는 굵게 표시해 줘.",
            "흐름·인과" to "앞뒤 사건과 연결해서 배경 → 전개 → 결과·의의 흐름으로 설명해 줘.",
            "헷갈리는 것" to "시험에서 자주 헷갈리는 비슷한 사건·용어와 비교해서 차이를 정리해 줘.",
        ),
        defaultModel = ClaudeTutor.MODEL_SONNET,
    )

    val KOREAN = Subject(
        id = "ko",
        name = "국어",
        defaultPrompt = """
            당신은 책을 읽는 한국인 독자를 돕는 국어 선생님입니다.
            사용자는 책 화면의 일부를 캡처하거나, 모르는 단어를 직접 써서 묻습니다.

            규칙:
            - 단어를 물으면: 이 문맥에 맞는 뜻을 먼저 한 줄로 쓰고, 이어서 품사, 한자어라면 한자와 글자별 뜻, 짧은 예문 1~2개를 듭니다.
            - 뜻이 여러 개인 단어는 이 문맥에 맞는 뜻이 무엇인지 분명히 표시합니다.
            - 사용자가 단어를 특정하지 않았으면, 캡처 안에서 어려워 보이는 단어 3~5개를 골라 문맥상 뜻을 짧게 설명합니다.
            - 문장이 어려우면 쉬운 말로 풀어 씁니다.
            - 표준국어대사전에 없을 법한 신조어·방언·옛말이면 그렇다고 밝힙니다.
        """.trimIndent() + "\n" + COMMON,
        buttons = listOf(
            "단어 뜻" to "모르는 단어의 이 문맥에서의 뜻, 품사, 한자어라면 한자와 글자별 뜻, 짧은 예문을 알려 줘.",
            "쉽게 풀기" to "이 문장(문단)을 쉬운 말로 풀어서 설명해 줘.",
            "비슷한 말" to "이 단어의 비슷한 말, 반대말, 헷갈리기 쉬운 말을 알려 줘.",
        ),
    )

    val all = listOf(JAPANESE, ENGLISH, HISTORY, KOREAN)

    fun byId(id: String?): Subject = all.firstOrNull { it.id == id } ?: JAPANESE
}
