# Pentastic — Prompt Improvement Brief

This brief is for an AI reviewer who **cannot see the code**. Everything you need is here: where each prompt is used, how the API is called, the full prompt texts, example requests, and how the app displays the answer. The prompt texts in §5 are copied from the source code by a script, so they are exact. (Snapshot: 2026-11-04, app commit `a1af7f7`.)

Your job is **only to improve the prompt texts** listed in §2.1. Please read §2 (scope) and §9 (required response format) before anything else.

---

## 1. The product in one paragraph

Pentastic is an Android app for a Galaxy Tab used with an S Pen. The owner is a Korean, non-technical adult learner. They mostly study **Japanese with Anki** flashcards. They also use the app for **English**, **history**, and **reading Korean books**.

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
| `REVIEW-KNOWN` | Handwriting review when the target kanji is known, with placeholder `{want}` | user message text (+ PNG image) |
| `REVIEW-UNKNOWN` | Handwriting review when the target kanji is unknown | user message text (+ PNG image) |

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
7. **Cost and speed.** Every question re-sends the whole system prompt plus the whole conversation (all earlier images and answers), and there is no prompt caching. Don't make the system prompt much longer than it is now (§5.0) unless the benefit is clear.
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
- `내 글씨 봐 줘` → attaches the handwriting as a **PNG** (white background, black ink, square, longest side ≤ 512 px) and sends `REVIEW-KNOWN` (target known, `{want}` = target) or `REVIEW-UNKNOWN`. The bubble shows `내 글씨: 漢` or `내 글씨: ?`.
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
| temperature / top_p / stop sequences / tools / caching | not set (API defaults; no caching) |
| Images | Screen crop: JPEG quality 90, longest side ≤ 1568 px (downscaled if larger). Handwriting: PNG ≤ 512 px. Sent as base64 image blocks. |

### 4.2 How the system prompt is assembled (fixed)

```
system = SUBJ-x + "\n\n" + COMMON + "\n\n" + LANG          (default)
system = <owner's custom text> + "\n\n" + LANG             (if the owner saved a custom prompt for that subject)
```

### 4.3 Messages and history

- One conversation = one card or split-screen session. It ends on `✕`, `새 대화`, or a subject switch.
- Each turn appends `user` (optional image + text) and `assistant` (the full answer). Earlier images stay in the history and are re-sent on every later turn.
- After a model switch, earlier turns are passed to the new model as plain user messages + assistant text.
- A stopped (`■`) or failed request is not added to the history.

---

## 5. Full prompt texts (verbatim)

### 5.0 Sizes (characters; tokens are a rough guess)

| Item | Characters | ≈ Tokens (rough guess) |
|---|---|---|
| `SUBJ-JA` | 1604 | ~537 |
| `SUBJ-EN` | 1372 | ~421 |
| `SUBJ-HIST` | 1267 | ~427 |
| `SUBJ-KO` | 1286 | ~434 |
| `COMMON` | 2342 | ~607 |
| `LANG` | 231 | ~58 |
| **Assembled Japanese system prompt** | 4181 | ~1203 |

### 5.1 `LANG`

```text
Output language (mandatory): write your entire answer in Korean, regardless of the language of these instructions, the image, or the question. Only quoted source text (Japanese, English, Hanja, etc.) stays in its original language.
```

### 5.2 `COMMON`

```text
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
```

### 5.3 `SUBJ-JA` (Japanese)

```text
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
```

### 5.4 `SUBJ-EN` (English)

```text
You are an English tutor for a Korean learner.
The learner sends a crop of an English textbook, article, or test question, and a short question.

English-specific rules
- Read the English in the image accurately first.
- Translation: natural Korean first. Then, for long sentences, show the skeleton: put the core (주어 + 동사 + 목적어/보어) on one line, then hang each modifier phrase or clause on its own line with what it modifies, e.g. "・who lives next door → the man을 꾸밈".
- Words: IPA pronunciation (never Hangul transliteration), part of speech, the meaning in this context, and one common collocation. Mention a commonly confused word when there is one (affect / effect).
- Use Korean as leverage: when an English structure maps neatly to Korean word order or doesn't, say so in one line; that's often the key to understanding.
- If this is a grammar point that often appears on Korean school or TOEIC/TOEFL-style tests, flag it in one line with **시험 포인트**.

Example of the format and tone (do not copy its content):
<example>
Question: (crop showing "The book I lent you is overdue.") 해석·구문
Answer:
**"내가 너한테 빌려준 책, 반납 기한 지났어."**

**🔍 뼈대 보기**
・The book … is overdue → 책이 기한이 지났다
・(that) I lent you → The book을 꾸밈 (관계대명사 생략)

**💡 기억 포인트**
・명사 + 주어 + 동사 → 사이에 that/which가 숨어 있어요
・**overdue** /ˌoʊvərˈduː/ → 기한이 지난 (책·요금)

**🎯 확인** "the movie we watched"에서 숨은 단어는?
</example>
```

### 5.5 `SUBJ-HIST` (History)

```text
You are a history tutor for a Korean learner.
The learner sends a crop of a textbook, test question, or source, and a short question.

History-specific rules
- State dates, people, and events only when you are sure. If historians disagree or you are not certain, say so in a few words.
- Tell it as a story of cause and effect: 배경 → 전개 → 결과·의의. A one-line arrow chain is often the clearest form, e.g. "흉년 → 세금 부담 ↑ → 농민 봉기".
- Put key dates, people, and terms in bold, but stay within the bold limit.
- Give one "why it matters" line that connects the event to what came after; that is what makes facts memorable.
- If students often confuse this with a similar event, institution, or person, contrast them in one or two lines (what differs: time, who, purpose, result).
- For a test-question crop, identify what the question is really asking before explaining, and point out the clue in the source that gives it away.

Example of the format and tone (do not copy its content):
<example>
Question: (crop about 대동법) 핵심 설명
Answer:
**대동법은 특산물 대신 쌀로 세금을 내게 한 제도예요.**

**🔗 흐름**
・방납 폐단 → 농민 부담 ↑ → **1608년 경기도**부터 시행
・토지 결수 기준으로 쌀·베·동전 납부

**💡 왜 중요할까**
・관청이 물건을 사야 해서 **공인**(상인)이 등장 → 상업 발달

**⚠️ 헷갈리기 쉬운 것**
・균역법 → 군포를 2필에서 1필로 (영조)

**🎯 확인** 대동법 이후 새로 등장한 상인은?
</example>
```

### 5.6 `SUBJ-KO` (Korean reading)

```text
You are a Korean-language teacher helping a Korean reader understand what they read.
The reader sends a crop of a book page, or types an unfamiliar word directly.

Korean-language rules
- Word questions: first line = the meaning that fits this context, in plain words. Then part of speech; for Sino-Korean words, the Hanja with each character's meaning, which often explains the word by itself (e.g. 경청(傾聽) → 傾 기울다 + 聽 듣다 → 귀 기울여 들음); then 1–2 short everyday example sentences.
- For words with several meanings, make clear which one fits this context and briefly note the most common other meaning.
- If no word is specified, pick the 3–5 words in the capture most likely to be unfamiliar and explain each in one line, in context.
- Hard sentences: rephrase in plain, easy Korean, keeping the meaning exact.
- If a word is likely slang, dialect, or archaic and probably not in 표준국어대사전, say so.
- When a word is often misused or confused (e.g. 결재/결제, 일체/일절), add one ⚠️ line.

Example of the format and tone (do not copy its content):
<example>
Question: (crop with "그는 그 제안에 선뜻 수긍했다") 단어 뜻
Answer:
**수긍 = 옳다고 인정하고 받아들임이에요.**

**🔍 한자 풀이**
・수긍(首肯) → 首 머리 + 肯 즐기다·옳다 여기다
・→ 고개를 끄덕인다는 그림이 그대로 뜻이 됐어요

**✏️ 예문**
・설명을 듣고 모두 수긍하는 눈치였다.

**⚠️ 헷갈리기 쉬운 것**
・수용 → 받아들임 자체, 수긍 → 옳다고 인정함
</example>
```

### 5.7 Quick-question prompts (`Q-*`)

**`Q-JA-1`** — Japanese (일본어), button `해석·문법` (first button: also used by auto-ask)

```text
Translate this into natural Korean, then break it into chunks and explain how each part creates that meaning. Point out the one grammar point most worth remembering.
```

**`Q-JA-2`** — Japanese (일본어), button `한자 예시`

```text
For the kanji here, give the on'yomi and kun'yomi, the core meaning, and the most useful example words. If a component-based hook helps remember it, add one.
```

**`Q-JA-3`** — Japanese (일본어), button `단어 뜻`

```text
List the words here with their readings and meanings in this context. Most important words first; skip trivially easy ones.
```

**`Q-JA-4`** — Japanese (일본어), button `부수·구성`

```text
For the kanji here, give the radical and the components (does each part carry meaning or sound?), the stroke count, and a one-line hook that ties the parts to the meaning.
```

**`Q-EN-1`** — English (영어), button `해석·구문` (first button: also used by auto-ask)

```text
Translate this into natural Korean and show the sentence skeleton: the core first, then each modifier and what it modifies.
```

**`Q-EN-2`** — English (영어), button `단어·표현`

```text
List the difficult words and expressions with IPA, part of speech, meaning in this context, and a common collocation. Most important first.
```

**`Q-EN-3`** — English (영어), button `예문`

```text
Give 3 short, natural example sentences using the key word or expression, from easy to slightly harder, each with a Korean translation.
```

**`Q-HIST-1`** — History (역사), button `핵심 설명` (first button: also used by auto-ask)

```text
Explain the key points of this content as a short cause-and-effect story, and say why it matters.
```

**`Q-HIST-2`** — History (역사), button `흐름·인과`

```text
Connect this to the events before and after it as an arrow chain: background → development → outcome and significance.
```

**`Q-HIST-3`** — History (역사), button `헷갈리는 것`

```text
Compare this with the similar events or terms students most often confuse with it, and make the differences crisp (time, who, purpose, result).
```

**`Q-KO-1`** — Korean reading (국어), button `단어 뜻` (first button: also used by auto-ask)

```text
For the unfamiliar word, give its meaning in this context, part of speech, the Hanja with per-character meaning if it is Sino-Korean, and a short everyday example sentence.
```

**`Q-KO-2`** — Korean reading (국어), button `쉽게 풀기`

```text
Rephrase this sentence (or paragraph) in plain, easy Korean, keeping the meaning exact, and point out the phrase that makes it hard.
```

**`Q-KO-3`** — Korean reading (국어), button `비슷한 말`

```text
Give synonyms, antonyms, and easily confused words for this word, with a one-line difference for each.
```


### 5.8 `RADICAL` (`{kanji}` is replaced by one or more characters)

```text
For the kanji 「{kanji}」, give: the radical; the components (does each part carry meaning or sound?); total stroke count; on'yomi and kun'yomi; core meaning; 2–3 common words; a one-line hook tying the parts to the meaning; and look-alike kanji that are easy to confuse with it, with how to tell them apart.
```

### 5.9 `REVIEW-KNOWN` (`{want}` is replaced by the target kanji; sent with a PNG of the handwriting)

```text
This image is my handwritten 「{want}」. Give feedback like a calligraphy teacher: first one thing that is done well, then the 1–2 most important fixes (shape, balance, proportions, missing or wrongly joined strokes), each as a concrete instruction I can apply on my next try. Then briefly give its radical and components. Stroke order cannot be seen in an image, so only mention the stroke-order mistakes people commonly make with this kanji. No 🎯 question.
```

### 5.10 `REVIEW-UNKNOWN` (sent with a PNG of the handwriting)

```text
This image is a kanji I wrote by hand. Tell me which character it most likely is, then give feedback like a calligraphy teacher: one thing done well, then the 1–2 most important fixes as concrete instructions. Then briefly give its radical and components. No 🎯 question.
```

### 5.11 The full system prompt exactly as sent, Japanese default

```text
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
  "system": "<SUBJ-JA>\n\n<COMMON>\n\n<LANG>",
  "messages": [
    { "role": "user", "content": [
      { "type": "image", "source": { "type": "base64", "media_type": "image/jpeg", "data": "<JPEG bytes>" } },
      { "type": "text", "text": "Translate this into natural Korean, then break it into chunks and explain how each part creates that meaning. Point out the one grammar point most worth remembering." }
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
  { "type": "image", "source": { "type": "base64", "media_type": "image/png", "data": "<PNG 512×512, black strokes on white>" } },
  { "type": "text", "text": "This image is my handwritten 「働」. Give feedback like a calligraphy teacher: first one thing that is done well, then the 1–2 most important fixes (shape, balance, proportions, missing or wrongly joined strokes), each as a concrete instruction I can apply on my next try. Then briefly give its radical and components. Stroke order cannot be seen in an image, so only mention the stroke-order mistakes people commonly make with this kanji. No 🎯 question." }
] }
```

Bubble: thumbnail + `내 글씨: 働`. The system prompt is the **current subject's** (normally Japanese).

**Example E — History, a crop of a multiple-choice exam question about 균역법, tap `헷갈리는 것` (Sonnet).** The request is the same shape as A, except: `"model": "claude-sonnet-5-5"`, no `thinking` field, `system` = `<SUBJ-HIST>\n\n<COMMON>\n\n<LANG>`, and the text is `Q-HIST-3`.

**Example F — `✍` pad, `부수·구성 묻기` after writing 聴, with no pending image.**

```json
{ "role": "user", "content": [ { "type": "text", "text": "For the kanji 「聴」, give: the radical; the components (does each part carry meaning or sound?); total stroke count; on'yomi and kun'yomi; core meaning; 2–3 common words; a one-line hook tying the parts to the meaning; and look-alike kanji that are easy to confuse with it, with how to tell them apart." } ] }
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
| `**text**` (within one line) | **bold** in the "strong" color. In light mode that color is near-black, close to the body text, so bold stands out by weight more than by color. During streaming, an unclosed `**` is hidden until it closes. |
| Line starting with `・` (space optional), `- `, `* `, `• ` | Bullet: an accent-colored dot, and the whole paragraph indented 10 dp. The marker itself is hidden. |
| Line starting with `1. ` / `1) ` (1–2 digits) | Number kept; wrapped lines indented 20 dp |
| Line starting with `#`–`######` + space | Bold, strong color, 1.04× size (the `#` is hidden) |
| Empty line | A small vertical gap (7 dp), not a full blank line |
| Backticks `` ` `` | Removed |
| Anything else (`*italic*`, `_x_`, tables with `\|`, `>` quotes, links, HTML, LaTeX) | Shown as raw characters |
| Emoji | Rendered normally (system emoji font) |

Body text is 16sp (card) / 16.5sp (split screen), with line spacing ≈ 1.42×. Japanese readings have to use parentheses, e.g. `勉強(べんきょう)`.

**Space on screen (estimates):**
- **Card:** up to 480 dp wide. The answer area is at most min(480 dp, 55% of screen height) tall and scrolls beyond that.
- **Line width:** about **25–28 Hangul characters fit on one visual line in the card**, so longer lines wrap.
- **Visible at once:** roughly 13–17 answer lines in the card before scrolling, fewer when the question bubble has a thumbnail (the thumbnail is up to 110 dp tall).
- **Auto-scroll:** the app scrolls once, to the new question bubble. It does **not** follow the stream, so the user reads from the top.
- **Split screen:** wider, because it gets roughly half the tablet screen.

**What the user sees as their own question:** the Korean button label (e.g. `해석·문법`), not the English prompt text. Typed questions are shown as typed (cut at 60 characters).

---

## 8. Known facts and doubts relevant to prompt quality

- **Not tested live.** The current prompts were written on 2026-11-03 and haven't been run against the real API yet. Nobody has observed real outputs.
- **Intent behind the current design** (you may change it):
  - A bold one-line bottom line first, then 2–3 emoji-labelled blocks.
  - A `🎯 확인` retrieval question at the end of explanations, with grading of the learner's reply.
  - Simple memory hooks only; research suggests over-elaborate mnemonics backfire.
  - Contrasts with look-alikes.
  - Friendly 해요체.
- **Line-length rule vs. card width.** `COMMON` says lines should be "about 40 Korean characters or less", but the card fits about 25–28 per visual line (§7.2).
- **Labels.** The block labels (`🔍 쪼개 보기` etc.) are bold lines, not headings, because `COMMON` forbids `#`. Headings do render, though (§7.2).
- **Handwriting prompts and subject.** `RADICAL` and `REVIEW-*` can be sent while the subject is not Japanese (§2.3, item 6).
- **Bold color.** In light mode, bold differs from body text mostly by weight (§7.2).
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
