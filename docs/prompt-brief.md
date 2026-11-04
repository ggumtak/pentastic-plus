# Pentastic — Prompt Improvement Brief

This brief is for an AI reviewer who **cannot see the code**. Everything you need is here: where each prompt is used, how the API is called, the full prompt texts, example requests, and how the app displays the answer. The prompt texts in §5 are copied from the source code by a script, so they are exact. (Snapshot: 2026-11-04, after the Japanese-focused update.)

Your job is **only to improve the prompt texts** listed in §2.1. Please read §2 (scope) and §9 (required response format) before anything else.

---

## 1. The product in one paragraph

Pentastic is an Android app for a Galaxy Tab used with an S Pen. **Right now the owner uses it only for Japanese**; the English, History and Korean subjects are unused placeholders, so optimise for Japanese. The owner is a Korean, non-technical adult learner. They mostly study **Japanese with Anki** flashcards. They also use the app for **English**, **history**, and **reading Korean books**.

When they get stuck, they press and hold the S Pen button. The screen freezes, and they drag a box around the part they want to ask about. A small chat card appears over Anki, next to the selected region. They tap a quick-question button (or type a question), and Claude answers in Korean inside that card. They can keep asking follow-up questions in the same card. Instead of the floating card, they can also keep a split-screen chat ("AI 챗") open beside Anki.

**The owner's goal for this review:** answers that help learning more, are easier to understand, are easier to scan at a glance, and are a bit more fun. The owner is not sure the current answers read well.

---

## 2. Scope — what you may and may not change

### 2.1 In scope (the only things you may change)

You may rewrite the text of these prompt items, which are identified by ID:

| ID | What it is | Sent as |
|---|---|---|
| `SUBJ-JA`, `SUBJ-EN`, `SUBJ-HIST`, `SUBJ-KO` | Subject-specific part of the system prompt, including its `<example>` block | system prompt (part 1) |
| `COMMON` | Shared rules appended to every subject | system prompt (part 2) |
| `LANG` | Output-language rule, always appended last | system prompt (part 3) |
| `Q-JA-1`…`Q-JA-4`, `Q-EN-1`…`Q-EN-3`, `Q-HIST-1`…`Q-HIST-3`, `Q-KO-1`…`Q-KO-3` | Text sent when a quick-question button is tapped | user message text |
| `RADICAL` | "Ask about radical/components" template, with placeholder `{kanji}` | user message text |
| `REVIEW-KNOWN` | Handwriting review when the target kanji is known, with placeholder `{want}` | user message text (+ PNG image with stroke-order numbers) |
| `REVIEW-UNKNOWN` | Handwriting review when the target kanji is unknown | user message text (+ PNG image with stroke-order numbers) |

You may move rules between `SUBJ-*` and `COMMON`, shorten them, reorder them, rewrite them, or add or replace `<example>` blocks inside them. The way the system prompt is assembled is fixed (§4.2), so anything that moves must still be plain text inside one of these IDs.

### 2.2 Out of scope — do not propose any of these

- Code, UI, layout, colors, fonts, or the answer renderer (§7). Treat the rendering rules as fixed facts.
- Button labels (the Korean words on the buttons), the number of buttons, or which subjects exist.
- Models, or API parameters such as `max_tokens`, `effort`, `thinking`, `temperature`, prompt caching, stop sequences, tools, structured/JSON output, or prefilling the assistant turn.
- Few-shot examples as fake earlier conversation turns. Only examples written inside the prompt text are possible.
- The image pipeline (crop size, JPEG/PNG), conversation-history handling, or new features (settings toggles, spoilers, buttons, etc.).

If a goal truly cannot be reached with prompt text alone, mention it in **one line** under "Limits" (§9) and stop there. Do not describe the code change.

### 2.3 Hard constraints your prompts must respect

1. **Every answer must be in Korean.** The only exception is quoted source text (Japanese, English, Hanja), which stays in its original language. `LANG` may be reworded but must keep this meaning.
2. **Prompt texts stay in English** (owner's decision; the model follows English instructions most reliably). Korean or Japanese literals inside them are fine, for example Korean label text the model should output, or example sentences.
3. **Only the markup in §7.1 renders.** Anything else shows up as raw characters. Readings must use parentheses, because ruby/furigana is impossible.
4. **The default model is Claude Haiku 5.5 with thinking disabled** (§4.1). It answers immediately, with no hidden reasoning step, so instructions must be clear and easy for a small, fast model to follow. History defaults to Sonnet 5.5.
5. **Placeholders** `{kanji}` (in `RADICAL`) and `{want}` (in `REVIEW-KNOWN`) must stay exactly as written. The app substitutes the character there.
6. **`COMMON` and `LANG` go to every subject.** In addition, `RADICAL`, `REVIEW-KNOWN` and `REVIEW-UNKNOWN` are sent under **whichever subject is currently selected**. This is usually Japanese, but it can be English, History, or Korean, so these three must work under any subject's system prompt.
7. **Cost and speed.** The system prompt and the conversation are prompt-cached (§4.1), so follow-up questions re-read them cheaply. Still keep `COMMON` compact (about 350 tokens): every first question in a conversation pays for the full system prompt, and Haiku should start streaming as fast as possible.
8. **Short answers.** The answer appears in a small card (§7.2). Long answers need scrolling.

---

## 3. Where each prompt is used (screens and buttons)

All buttons below go through the same function. The app sends one Messages API request: **system** = the current subject's system prompt (§4.2), **messages** = conversation history + one new user message. The new user message holds the pending image (if any) followed by the text.

### 3.1 Floating card (main entry point)

- How it opens: hold the S Pen button → screen freezes → drag a rectangle → card appears near the selection.
- The dragged region becomes a **pending image** (JPEG). The image is sent with the **next** question only and then becomes part of the history. Nothing is sent until the user taps a button or sends a typed question. The exception is the "auto-ask" setting (off by default), which immediately sends the first quick-question button of the current subject.
- Header: subject picker `일본어 ▾` (switching subject clears the conversation and keeps the pending image), model toggle `Haiku ⇄` / `Sonnet ⇄` (switching keeps the conversation), `＋ 캡처` (new capture added to the **same** conversation), `연습장`, `⧉` (move the conversation to split-screen), `⚙`, `✕` (close; this ends the conversation).
- **Quick-question chips** (one row, depends on subject):

  | Subject | Button label (shown, Korean) | Prompt ID sent |
  |---|---|---|
  | Japanese (일본어) | `해석·문법` | `Q-JA-1` |
  | Japanese (일본어) | `한자 예시` | `Q-JA-2` |
  | Japanese (일본어) | `단어 뜻` | `Q-JA-3` |
  | Japanese (일본어) | `부수·구성` | `Q-JA-4` |
  | English (영어) | `해석·구문` | `Q-EN-1` |
  | English (영어) | `단어·표현` | `Q-EN-2` |
  | English (영어) | `예문` | `Q-EN-3` |
  | History (역사) | `핵심 설명` | `Q-HIST-1` |
  | History (역사) | `흐름·인과` | `Q-HIST-2` |
  | History (역사) | `헷갈리는 것` | `Q-HIST-3` |
  | Korean reading (국어) | `단어 뜻` | `Q-KO-1` |
  | Korean reading (국어) | `쉽게 풀기` | `Q-KO-2` |
  | Korean reading (국어) | `비슷한 말` | `Q-KO-3` |

- Input box (placeholder "직접 물어보기") + `↑` send: the user's typed text is sent **as-is**, usually in Korean and often short (e.g. "ことで 대신 ので 쓰면?", "더 쉽게", "예문 하나 더", or an answer to a 🎯 question).
- While an answer streams, `↑` becomes `■` (stop).
- `✍` opens the handwriting pad inside the chat (§3.3).

### 3.2 Split-screen chat ("AI 챗")

This is the same chat UI, opened as a separate app window beside Anki. Captures go straight into it while it is visible. It has the same buttons, plus `새 대화` (clear the conversation). The text is slightly larger (16.5sp, compared with 16sp in the card).

### 3.3 `✍` handwriting pad (inside either chat)

- The user writes a kanji. On-device recognition (Google ML Kit, no API) shows candidate characters. Tapping a candidate inserts it into the chat input box; **nothing is sent**.
- Button `부수·구성 묻기` → sends `RADICAL` with `{kanji}` = the input-box text (or the top candidate). There is **no image**, but any pending capture is still attached. The question bubble shows `부수·구성: 漢`.

### 3.4 `연습장` (kanji practice screen)

- A big writing area beside a chat. Optionally the user types a target kanji, and a faint guide character appears for tracing. Live recognition feedback (✓/△/✗) is local and uses no API.
- `내 글씨 봐 줘` → attaches the handwriting as a **PNG** (white background, black ink, square, longest side ≤ 512 px). **Each stroke's starting point has a small red dot and a red number (1, 2, 3…, white outline) showing the order the learner actually wrote the strokes.** Then it sends `REVIEW-KNOWN` (target known, `{want}` = target) or `REVIEW-UNKNOWN`. The bubble shows `내 글씨: 漢` or `내 글씨: ?`.
- `부수·구성` → sends `RADICAL` with the target or top candidate (no new image).
- `그림 붙이기` → only attaches the PNG as the pending image. The user then taps a quick-question button or types.

### 3.5 Settings screen (affects prompts)

- "과목별 답변 방식 (프롬프트)": per subject, a text box shows **`SUBJ-x` + `COMMON`** as one editable text, with `저장` (save) and `기본값으로 되돌리기` (restore default). A saved custom text **replaces** `SUBJ-x + COMMON` for that subject. `LANG` is not shown and is always appended. The hint shown to the owner suggests edits like "예문은 항상 2개씩", "JLPT 급수도 알려 줘", "더 짧게".
- Per-subject model (Haiku / Sonnet) and the auto-ask toggle.

---

## 4. API call details

### 4.1 Request settings

| Item | Value |
|---|---|
| API | Anthropic Messages API, streaming (official `anthropic-java` SDK 3.3.0) |
| Models | `claude-haiku-5-5` (default for Japanese, English, Korean) · `claude-sonnet-5-5` (default for History). User can toggle per subject. |
| `max_tokens` | 4096 |
| Effort | `output_config.effort = "low"` (both models) |
| Thinking | Haiku: **disabled** (fastest first token). Sonnet: not set, so the model's default adaptive thinking applies. Thinking is never shown; only text blocks are displayed. |
| Prompt caching | `cache_control: ephemeral` (5 min) on the system prompt block, plus top-level automatic caching that marks the end of each request. Follow-ups read the earlier prefix from cache. |
| temperature / top_p / stop sequences / tools | not set (API defaults) |
| Images | Screen crop: JPEG quality 90, longest side ≤ 1568 px (downscaled if larger). Handwriting: PNG ≤ 512 px. Sent as base64 image blocks. |

### 4.2 How the system prompt is assembled (fixed)

```
system = SUBJ-x + "\n\n" + COMMON + "\n\n" + LANG          (default)
system = <owner's custom text> + "\n\n" + LANG             (if the owner saved a custom prompt for that subject)
```

### 4.3 Messages and history

- One conversation = one card or split-screen session. It ends on `✕`, `새 대화`, or a subject switch.
- Each turn appends `user` (optional image + text) and `assistant` (the answer **text only**; thinking blocks are never sent back).
- Images: the new image plus the **2 most recent** earlier images are sent as images. Older ones are replaced by the text `[earlier captured image omitted]` (the chat bubbles on screen keep their thumbnails).
- After a model switch, earlier turns are passed to the new model as plain user messages + assistant text.
- A stopped (`■`) or failed request is not added to the history.

---

## 5. Full prompt texts (verbatim)

### 5.0 Sizes (characters; tokens are a rough guess)

| Item | Characters | ≈ Tokens (rough guess) |
|---|---|---|
| `SUBJ-JA` | 2150 | ~768 |
| `SUBJ-EN` | 1325 | ~444 |
| `SUBJ-HIST` | 1268 | ~490 |
| `SUBJ-KO` | 1286 | ~454 |
| `COMMON` | 1107 | ~304 |
| `LANG` | 231 | ~58 |
| **Assembled Japanese system prompt** | 3492 | ~1132 |

### 5.1 `LANG`

```text
Output language (mandatory): write your entire answer in Korean, regardless of the language of these instructions, the image, or the question. Only quoted source text (Japanese, English, Hanja, etc.) stays in its original language.
```

### 5.2 `COMMON`

```text
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
```

### 5.3 `SUBJ-JA` (Japanese)

```text
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
```

### 5.4 `SUBJ-EN` (English)

```text
You are an English tutor for a Korean adult learner.
The learner sends a crop of an English book, article, or test question, and a short question.

English-specific rules
- Read the English in the image accurately first.
- Sentences: put a natural Korean translation in bold on Line 1. For complex sentences, show the core skeleton (`주어 + 동사 + 목적어`) first, then hang modifiers underneath (`・수식어구 → 앞의 명사를 꾸밈`).
- Words & idioms: give natural Korean pronunciation stress in brackets alongside simple phonetic cues (e.g. overdue [오버**두**], `/ˌoʊvərˈduː/`), part of speech, context meaning, the core nuance image, and 1 common collocation. Contrast look-alike words when helpful (affect vs. effect).
- Korean leverage: point out in one line where English word order is the reverse of Korean (e.g. "명사 먼저 던지고 뒤에서 설명 붙이기").
- For test questions, highlight the clue in the sentence and add a `### 📌 시험 포인트` block.

Example of the format and tone (do not copy its content):
<example>
Question: (crop showing "The book I lent you is overdue.") 해석·구문
Answer:
**"내가 빌려준 책, 반납 기한 지났어."**

### 🔍 뼈대 보기
・**The book** is **overdue** → 그 책은 기한이 지났다
・(that) I lent you → 어떤 책인지 뒤에서 꾸밈

### 💡 원어민 느낌
・명사 뒤에 바로 `주어+동사`가 오면 `~가 …한 (명사)`로 읽으세요
・**overdue** [오버**두**] → 기한 넘긴 (책·요금·일)

**🎯 확인** "우리가 본 영화"를 영어로 하면 the movie 다음에 뭐가 올까요?
</example>
```

### 5.5 `SUBJ-HIST` (History)

```text
You are a history tutor for a Korean adult learner.
The learner sends a crop of a history book, source text, or exam question, and a short question.

History-specific rules
- State dates, people, and events only when certain. If uncertain or debated, say so briefly.
- Tell history as a vivid cause-and-effect chain: `배경 → 사건/내용 → 결과·나비효과`. Keep arrow chains punchy so each step fits on one line.
- Bold only the 2–3 most crucial terms, kings, or dates.
- Always explain "왜 중요할까?" in plain language (who gained or lost, and how society changed afterward).
- Contrast commonly confused events/institutions in 1–2 crisp lines (왕/시기 · 대상 · 핵심 차이).
- If the image is a test question, identify the decisive clue word in the source box (사료) first, then explain why the answer follows from that clue.

Example of the format and tone (do not copy its content):
<example>
Question: (crop about 대동법) 핵심 설명
Answer:
**대동법은 특산물 대신 '땅 넓이 기준 쌀'로 세금을 통일한 개혁이에요.**

### 🔗 흐름과 인과
・방납 폐단(중간 수탈) → 농민 파산 속출
・**광해군(1608)** 경기도 시작 → 집집마다 내던 특산물을 **토지(결) 기준 쌀·베·동전**으로 변경
・땅 없는 농민은 세금 해방, 지주 부담은 증가!

### 💡 왜 중요할까
・관청 물품을 조달하는 **공인** 등장 → 조선 후기 상업·시장 경제 폭발

### ⚠️ 헷갈리는 것
・**대동법**(광해군) → 특산물을 쌀로 (공납 개혁)
・**균역법**(영조) → 군포 2필을 1필로 (군역 개혁)

**🎯 확인** 대동법 시행으로 관청 물품을 대신 사들이며 성장한 상인은?
</example>
```

### 5.6 `SUBJ-KO` (Korean reading)

```text
You are a Korean reading companion helping an adult reader enjoy and understand books.
The reader sends a crop of a book page or types an unfamiliar word.

Korean-language rules
- Word questions: put the context-fitting meaning in plain, everyday Korean in bold on Line 1. For Sino-Korean words, break down the Hanja characters into a vivid picture (e.g. 경청(傾聽) → 傾 기울다 + 聽 듣다 = 귀를 쫑긋 기울여 들음), then give 1 short everyday example.
- If a word has multiple meanings, focus on the one used in the capture and mention the other common sense in one short line.
- If no specific word was asked about, pick the 2–4 hardest words in the capture and explain each in 1–2 lines in context.
- Complex sentences: rewrite the sentence into smooth, plain Korean on Line 1, then explain the tricky vocabulary or logic structure underneath.
- Flag commonly confused word pairs (결재/결제, 지양/지향, 일체/일절) in a `### ⚠️ 헷갈리는 것` block when relevant.

Example of the format and tone (do not copy its content):
<example>
Question: (crop with "그는 그 제안에 선뜻 수긍했다") 단어 뜻
Answer:
**수긍(首肯)은 "그 말이 맞네" 하고 고개를 끄덕여 인정한다는 뜻이에요.**

### 🔍 한자 그림 풀이
・**首**(머리 수) + **肯**(옳게 여길 긍)
・고개를 끄덕이는 모습이 그대로 단어가 됐어요

### ✏️ 일상 예문
・설명을 듣고 나니 모두 수긍하는 분위기였다.

### ⚠️ 헷갈리는 것
・**수긍** → 맞다고 고개 끄덕임 (납득)
・**수용** → 그냥 받아들임 (동의 안 해도 가능)
</example>
```

### 5.7 Quick-question prompts (`Q-*`)

**`Q-JA-1`** — Japanese (일본어), button `해석·문법` (first button: also used by auto-ask)

```text
Translate this into natural Korean on Line 1, then break the sentence into short chunks (`chunk → meaning/role`). For key verbs, note the group (1류/2류/3류) or the 자동사/타동사 pair if learners confuse them, and flag any particle that differs from Korean. Highlight the single most useful grammar or nuance point.
```

**`Q-JA-2`** — Japanese (일본어), button `한자 예시`

```text
Pick the 1–2 most important kanji here. Give the core meaning, on'yomi and kun'yomi (show the Korean 받침 → on'yomi pattern when it applies, e.g. 학 学 → がく), 3 high-frequency example words (with hiragana readings and Korean meanings), and a short memory hook if helpful.
```

**`Q-JA-3`** — Japanese (일본어), button `단어 뜻`

```text
List the 3–5 key words or expressions here in order of importance, with hiragana readings and meanings in this context. For verbs, add the group (1류/2류/3류) or the 자동사/타동사 partner if confusable. Add Korean 한자어 connections, false-friend warnings, or particle differences from Korean where helpful. Skip trivial words.
```

**`Q-JA-4`** — Japanese (일본어), button `부수·구성`

```text
Pick the 1–2 most important kanji here. Break each into its radical and components (noting whether each part gives meaning or sound), give a vivid 1-line story tying the parts to the meaning, and contrast 1 look-alike kanji if there is one.
```

**`Q-EN-1`** — English (영어), button `해석·구문` (first button: also used by auto-ask)

```text
Give a natural Korean translation on Line 1, then show the core sentence skeleton (`주어 + 동사 + 목적어/보어`) and how each modifier chunk attaches to it. Point out 1 key grammar or word-order tip.
```

**`Q-EN-2`** — English (영어), button `단어·표현`

```text
List the 3–5 most important words or idioms here with pronunciation stress in Korean brackets (plus IPA if helpful), context meaning, core nuance image, and 1 common collocation each.
```

**`Q-EN-3`** — English (영어), button `예문`

```text
Pick the single most useful word or expression here and give 3 short, vivid example sentences you can use in real life (casual → polite/work → slightly advanced), each followed by a natural Korean translation on the next line. No 🎯 question.
```

**`Q-HIST-1`** — History (역사), button `핵심 설명` (first button: also used by auto-ask)

```text
Explain the core of this content as a vivid cause-and-effect story (`배경 → 전개 → 결과`), state why it matters in history, and if this is a test question, point out the decisive clue in the source box.
```

**`Q-HIST-2`** — History (역사), button `흐름·인과`

```text
Show what led to this and what came after as a crisp arrow chain (`이전 상황 → 핵심 사건/정책 → 이후 변화와 나비효과`), keeping each step short enough to scan in one line.
```

**`Q-HIST-3`** — History (역사), button `헷갈리는 것`

```text
Compare this topic with the 1–2 similar events, people, or institutions most often confused with it. Make the differences crystal clear side by side (시기/왕 · 핵심 내용 · 결과), and if this is a test question, explain how to spot the trap choice.
```

**`Q-KO-1`** — Korean reading (국어), button `단어 뜻` (first button: also used by auto-ask)

```text
Explain the unfamiliar word(s) in plain Korean fitting this context (if multiple words are in the capture, pick the 2–4 hardest ones). Include Hanja character breakdown as a visual image if Sino-Korean, and 1 short everyday example.
```

**`Q-KO-2`** — Korean reading (국어), button `쉽게 풀기`

```text
Rewrite this sentence or passage into plain, easy-to-digest Korean on Line 1 while keeping the exact meaning, then break down the 1–2 tricky phrases or logic jumps that made it hard to read.
```

**`Q-KO-3`** — Korean reading (국어), button `비슷한 말`

```text
For the key word here (pick the most important one if several appear), give 2–3 useful synonyms, 1 antonym, and any commonly confused look-alike words, each with a crisp 1-line nuance difference. No 🎯 question.
```


### 5.8 `RADICAL` (`{kanji}` is replaced by one or more characters)

```text
For the kanji 「{kanji}」 (if multiple characters, cover at most 2), explain in compact bullets:
1. Core meaning, Korean 훈음, and Japanese readings in hiragana (`음독 / 훈독`). If the on'yomi follows the Korean 받침 pattern (ㄱ → く/き, ㄴ·ㅁ → ん, ㅇ → う/い, ㄹ → つ/ち, ㅂ → う), show it briefly, e.g. 학 学 → がく.
2. Radical and component breakdown (state whether each part gives meaning or sound) + a vivid 1-line story tying the parts together.
3. 2 high-frequency words with hiragana readings in parentheses, e.g. 漢字(かんじ), and 1 look-alike kanji to watch out for.
No 🎯 question.
```

### 5.9 `REVIEW-KNOWN` (`{want}` is replaced by the target kanji; sent with a PNG of the handwriting)

```text
This image is my handwritten kanji 「{want}」. The small red numbers with dots mark where each of my strokes started, in the order I actually wrote them (1, 2, 3…); they are an overlay, not part of the character. Review it like a warm, sharp calligraphy tutor:
- Line 1 (bold): overall impression and 1 specific thing done well.
- Then the 1–2 most important fixes (stroke proportions, center of gravity, angles, or endings like 삐침/갈고리) as concrete visual tips I can apply right away.
- Stroke order: compare my numbered order with the correct order and name any stroke written out of order or started from the wrong end. If it is all correct, say so in one line.
- Form check: tell me if I wrote the Korean traditional form (정자체) instead of the Japanese shinjitai (신자체), or missed a well-known form trap (e.g. 歩 vs 步, 黒 vs 黑, 発 vs 發, 図 vs 圖, 聴 vs 聽).
- Finally, 1–2 lines on its component breakdown (with hiragana reading in parentheses).
No 🎯 question.
```

### 5.10 `REVIEW-UNKNOWN` (sent with a PNG of the handwriting)

```text
This image is a kanji I wrote by hand. The small red numbers with dots mark where each of my strokes started, in the order I actually wrote them (1, 2, 3…); they are an overlay, not part of the character.
- Line 1 (bold): identify which kanji it most likely is (with Korean 훈음 and Japanese hiragana reading in parentheses). If ambiguous between 2 characters, name both.
- Give 1 thing done well, then the 1–2 most important shape/balance fixes as concrete instructions for my next try.
- Stroke order: compare my numbered order with the correct order and name any stroke out of order or started from the wrong end.
- Form check: tell me if it looks like the Korean traditional form (정자체) rather than the Japanese shinjitai (신자체), or misses a well-known form trap (e.g. 歩 vs 步, 黒 vs 黑, 発 vs 發, 図 vs 圖, 聴 vs 聽).
- Briefly show its radical/components and how to tell it apart from its closest look-alike kanji.
No 🎯 question.
```

### 5.11 The full system prompt exactly as sent, Japanese default

```text
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

Output language (mandatory): write your entire answer in Korean, regardless of the language of these instructions, the image, or the question. Only quoted source text (Japanese, English, Hanja, etc.) stays in its original language.
```

---

## 6. Example requests (realistic)

The image bytes and repeated system prompts are abbreviated. Everything else matches what the app sends. The question bubble the user sees is shown as **Bubble**.

**Example A — Japanese, first capture, tap `해석·문법` (Haiku).** The image is a crop of an Anki card that shows the sentence 彼は約束を守らないことで有名だ.

```json
{
  "model": "claude-haiku-5-5",
  "max_tokens": 4096,
  "stream": true,
  "thinking": { "type": "disabled" },
  "output_config": { "effort": "low" },
  "system": [ { "type": "text", "text": "<SUBJ-JA>\n\n<COMMON>\n\n<LANG>", "cache_control": { "type": "ephemeral" } } ],
  "cache_control": { "type": "ephemeral" },
  "messages": [
    { "role": "user", "content": [
      { "type": "image", "source": { "type": "base64", "media_type": "image/jpeg", "data": "<JPEG bytes>" } },
      { "type": "text", "text": "Translate this into natural Korean on Line 1, then break the sentence into short chunks (`chunk → meaning/role`). For key verbs, note the group (1류/2류/3류) or the 자동사/타동사 pair if learners confuse them, and flag any particle that differs from Korean. Highlight the single most useful grammar or nuance point." }
    ] }
  ]
}
```

Bubble: thumbnail + `해석·문법`.

**Example B — follow-up typed question in the same conversation.** `messages` now holds Example A's user turn (with the image), the assistant's answer from A, and the new turn:

```json
{ "role": "user", "content": [ { "type": "text", "text": "ことで 대신 ので 쓰면 뜻이 달라져?" } ] }
```

Bubble: `ことで 대신 ので 쓰면 뜻이 달라져?`.

**Example C — the learner answers the 🎯 question from the previous answer.**

```json
{ "role": "user", "content": [ { "type": "text", "text": "약속을 안 지키는 걸로?" } ] }
```

**Example D — practice screen, `내 글씨 봐 줘` with target 働.**

```json
{ "role": "user", "content": [
  { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "<PNG 512×512, black strokes on white, red stroke-order numbers>" } },
  { "type": "text", "text": "This image is my handwritten kanji 「働」. The small red numbers with dots mark where each of my strokes started, in the order I actually wrote them (1, 2, 3…); they are an overlay, not part of the character. Review it like a warm, sharp calligraphy tutor:\n- Line 1 (bold): overall impression and 1 specific thing done well.\n- Then the 1–2 most important fixes (stroke proportions, center of gravity, angles, or endings like 삐침/갈고리) as concrete visual tips I can apply right away.\n- Stroke order: compare my numbered order with the correct order and name any stroke written out of order or started from the wrong end. If it is all correct, say so in one line.\n- Form check: tell me if I wrote the Korean traditional form (정자체) instead of the Japanese shinjitai (신자체), or missed a well-known form trap (e.g. 歩 vs 步, 黒 vs 黑, 発 vs 發, 図 vs 圖, 聴 vs 聽).\n- Finally, 1–2 lines on its component breakdown (with hiragana reading in parentheses).\nNo 🎯 question." }
] }
```

Bubble: thumbnail + `내 글씨: 働`. The system prompt is the **current subject's** (normally Japanese).

**Example E — History, a crop of a multiple-choice exam question about 균역법, tap `헷갈리는 것` (Sonnet).** The request is the same shape as A, except: `"model": "claude-sonnet-5-5"`, no `thinking` field, `system` = `<SUBJ-HIST>\n\n<COMMON>\n\n<LANG>`, and the text is `Q-HIST-3`.

**Example F — `✍` pad, `부수·구성 묻기` after writing 聴, with no pending image.**

```json
{ "role": "user", "content": [ { "type": "text", "text": "For the kanji 「聴」 (if multiple characters, cover at most 2), explain in compact bullets:\n1. Core meaning, Korean 훈음, and Japanese readings in hiragana (`음독 / 훈독`). If the on'yomi follows the Korean 받침 pattern (ㄱ → く/き, ㄴ·ㅁ → ん, ㅇ → う/い, ㄹ → つ/ち, ㅂ → う), show it briefly, e.g. 학 学 → がく.\n2. Radical and component breakdown (state whether each part gives meaning or sound) + a vivid 1-line story tying the parts together.\n3. 2 high-frequency words with hiragana readings in parentheses, e.g. 漢字(かんじ), and 1 look-alike kanji to watch out for.\nNo 🎯 question." } ] }
```

Bubble: `부수·구성: 聴`.

---

## 7. How the app checks and displays the answer

### 7.1 Checks (the only ones)

- The answer text is streamed and redrawn every ~40 ms. The app does **not** validate the content or format in any way, and it doesn't parse sections or 🎯 lines.
- `stop_reason = refusal` → a red message "Claude가 이 요청에 답하지 않았습니다. 질문을 바꿔 다시 시도해 주세요." is added below any text that already streamed, with a retry button.
- `stop_reason = max_tokens` → the app appends "(답변이 길어 여기서 끊겼습니다. '이어서'라고 보내면 계속합니다.)"
- API or network errors → a red Korean error line plus a `↻ 다시 시도` (retry) button. A stopped answer gets "(멈춤)".
- Before the first character arrives, the app shows "읽는 중…".

### 7.2 Rendering rules (fixed; your prompts must work within these)

The answer is split into lines on `\n`, and **each line is trimmed** (leading spaces are removed, so indentation and nesting are impossible). Then, per line:

| The model writes | User sees |
|---|---|
| `**text**` (within one line) | **bold**. In light mode it also gets a soft yellow highlighter background (~19%), so key terms pop out. During streaming, an unclosed `**` is hidden until it closes. |
| Line starting with `・` (space optional), `- `, `* `, `• ` | Bullet: an accent-colored dot, and the whole paragraph indented 10 dp. The marker itself is hidden. |
| Line starting with `1. ` / `1) ` (1–2 digits) | Number kept; wrapped lines indented 20 dp |
| Line starting with `#`–`######` + space | Section label: bold, **accent color** (indigo in light mode, lavender in dark), 1.06× size, 6 dp extra space above (the `#` is hidden) |
| Kana in parentheses right after a kanji, e.g. `勉強(べんきょう)`, `時々（ときどき）` | The parenthesized reading is shown **smaller (0.85×) and in a muted gray**, so the kanji and the Korean explanation stand out |
| Empty line | A small vertical gap (7 dp), not a full blank line |
| Backticks `` ` `` | Removed |
| Anything else (`*italic*`, `_x_`, tables with `\|`, `>` quotes, links, HTML, LaTeX) | Shown as raw characters |
| Emoji | Rendered normally (system emoji font) |

Body text is 16sp (card) / 16.5sp (split screen). Wrapped lines inside one item use 1.3× line spacing; each `\n` line gets an extra ~5 dp below it, so separate bullets don't blend together. Japanese readings have to use parentheses, e.g. `勉強(べんきょう)`.

**Space on screen (estimates):**
- **Card:** up to 480 dp wide. The answer area is at most min(480 dp, 55% of screen height) tall and scrolls beyond that.
- **Line width:** about **25–28 Hangul characters fit on one visual line in the card**, so longer lines wrap.
- **Visible at once:** roughly 13–17 answer lines in the card before scrolling, fewer when the question bubble has a thumbnail (the thumbnail is up to 110 dp tall).
- **Auto-scroll:** the app scrolls once, to the new question bubble. It does **not** follow the stream, so the user reads from the top.
- **Split screen:** wider, because it gets roughly half the tablet screen.

**What the user sees as their own question:** the Korean button label (e.g. `해석·문법`), not the English prompt text. Typed questions are shown as typed (cut at 60 characters).

---

## 8. Known facts and doubts relevant to prompt quality

- **Not tested live.** The current prompts (written 2026-11-04) haven't been run against the real API yet. Nobody has observed real outputs.
- **Intent behind the current design** (you may change it):
  - A bold one-line bottom line first, then 2–3 emoji-labelled blocks.
  - A `🎯 확인` retrieval question at the end of explanations, with grading of the learner's reply.
  - Simple memory hooks only; research suggests over-elaborate mnemonics backfire.
  - Contrasts with look-alikes.
  - Friendly 해요체.
- **Labels.** Block labels are `### ` headings (accent color, §7.2).
- **Example first lines.** `COMMON` asks for a short Line 1, but some `<example>` first lines are 35–40 Korean characters; the model tends to follow the examples.
- **Handwriting prompts and subject.** `RADICAL` and `REVIEW-*` can be sent while the subject is not Japanese (§2.3, item 6).
- **Custom prompts.** The owner may already have custom prompts saved for some subjects. Those replace `SUBJ-x + COMMON`, so your new defaults won't reach those subjects until the owner restores the default. This is not your concern; just write good defaults.
- **Japanese typical inputs.** Anki cards: a single word with its reading, an example sentence, or the back of a card showing a Korean meaning. Crops range from one word to a whole card.
- **History typical inputs.** Textbook paragraphs and Korean exam-style questions (likely Korean history).
- **Korean typical inputs.** Pages of general books.

---

## 9. Required response format

- Write **prompt texts in English**.
- Write **all explanations in Korean**, because the owner reads them. Keep explanations short.
- Use exactly these sections and headings:

````
## 1. 진단
(Korean, at most 8 bullets: what is weak in the current prompts and why, tied to the owner's goals.)

## 2. 바꾼 프롬프트
### <ID>          ← one subsection per changed ID, IDs exactly as in §2.1
```text
<the COMPLETE new text of this ID — never a diff, never "…", never "same as before">
```
- 바꾼 이유: (Korean, at most 3 lines)

## 3. 그대로 둔 프롬프트
(Comma-separated list of IDs you did not change.)

## 4. 예상 답변 (자체 점검)
For examples A, C and E in §6, write the raw answer text you expect the model to produce under your new prompts,
exactly as the model would output it (markup included), so the owner can judge readability.

## 5. 위험·한계
(Korean, at most 5 bullets. Things that could get worse, and goals prompts alone cannot reach — one line each, no code proposals.)
````

Rules for section 2:
- Use only the IDs in §2.1; don't invent new ones.
- Every placeholder (`{kanji}`, `{want}`) must stay.
- Each block must be paste-ready: the owner's assistant will copy each block verbatim into the app.
