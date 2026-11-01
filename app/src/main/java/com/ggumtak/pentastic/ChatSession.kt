package com.ggumtak.pentastic

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.anthropic.client.AnthropicClient
import kotlin.concurrent.thread

/** 앱 전체에서 하나만 쓰는 API 클라이언트. 키가 바뀌지 않는 한 연결을 재사용해서 빠르다. */
object ApiClient {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    fun get(ctx: Context): AnthropicClient? {
        val key = Prefs(ctx).apiKey
        if (key.isBlank()) return null
        if (client == null || clientKey != key) {
            client?.close()
            client = ClaudeTutor.newClient(key)
            clientKey = key
        }
        return client
    }
}

/**
 * 지금 진행 중인 대화 하나. 떠 있는 카드와 분할 화면(AI 챗)이 같은 대화를 함께 본다.
 * 화면 쪽은 addListener로 변경 알림을 받아 다시 그린다. 모든 상태 변경과 알림은 메인 스레드에서 일어난다.
 */
class ChatSession private constructor(private val app: Context) {

    class Entry(val label: String, val thumb: Bitmap?) {
        val raw = StringBuilder()
        var error: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(app)
    private val listeners = LinkedHashSet<() -> Unit>()

    val entries = mutableListOf<Entry>()

    /** 아직 보내지 않은 캡처. 다음 질문에 함께 보내고, 성공하면 비운다. */
    private var pendingImage: (() -> ByteArray)? = null
    private var pendingPreview: Bitmap? = null

    /** 지금 전송 중인 캡처 (전송 중엔 '보낼 이미지' 칸에서 숨긴다. 실패하면 다시 보인다) */
    private var inFlight: (() -> ByteArray)? = null

    /** 입력칸 위에 크게 보여줄 '보낼 이미지'. 없으면 null. */
    val attachedPreview: Bitmap?
        get() = if (pendingImage != null && pendingImage !== inFlight) pendingPreview else null

    var busy = false
        private set

    /** 첫 글자가 오기 전까지 보여줄 상태 문구 ("읽는 중…") */
    var waiting = false
        private set

    /** 답변 영역 위 안내 문구와 오류 여부 */
    var notice: String? = null
        private set
    var noticeIsError = false
        private set

    private var tutor: ClaudeTutor? = null
    private var tutorSubject: String? = null

    val subject: Subject get() = prefs.subject

    val model: String get() = prefs.modelFor(prefs.subject)

    /** 지금 과목의 모델을 Haiku ↔ Sonnet으로 바꾼다. 대화는 이어진다. */
    fun toggleModel() {
        val subj = prefs.subject
        val next = if (prefs.modelFor(subj) == ClaudeTutor.MODEL_SONNET) ClaudeTutor.MODEL_HAIKU else ClaudeTutor.MODEL_SONNET
        prefs.setModelFor(subj, next)
        DebugLog.add("모델 변경: ${subj.name} → $next")
        changed()
    }

    /** 과목을 바꾼다. 과목마다 지시문이 달라서 대화는 새로 시작하고, 붙여 둔 캡처는 남긴다. */
    fun switchSubject(id: String) {
        if (busy || id == prefs.subjectId) return
        prefs.subjectId = id
        DebugLog.add("과목 변경: ${prefs.subject.name}")
        generation++
        tutor = null
        entries.clear()
        notice = null
        noticeIsError = false
        changed()
    }
    private var generation = 0

    val isEmpty get() = entries.isEmpty() && pendingImage == null

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** 새 캡처를 대화에 붙인다. 설정에서 자동 요청을 켰으면 바로 '해석·문법'을 묻는다. */
    fun attach(preview: Bitmap, image: () -> ByteArray, allowAutoAsk: Boolean = true) {
        DebugLog.add("대화에 이미지 붙음 (${preview.width}x${preview.height}, 화면 ${listeners.size}개)")
        pendingImage = image
        pendingPreview = preview
        inFlight = null
        noticeIsError = false
        notice = null
        changed()
        if (allowAutoAsk && prefs.autoAsk) prefs.subject.buttons.first().let { (label, q) -> send(q, label) }
    }

    /** '보낼 이미지'를 뺀다 (✕). */
    fun removeAttachment() {
        if (busy) return
        pendingImage = null
        pendingPreview = null
        changed()
    }

    /** 대화를 비우고 새로 시작한다. 진행 중인 답변은 멈춘다. */
    fun reset() {
        generation++
        tutor?.cancel()
        tutor = null
        entries.clear()
        pendingImage = null
        pendingPreview = null
        inFlight = null
        busy = false
        waiting = false
        notice = null
        noticeIsError = false
        changed()
    }

    fun send(question: String, label: String) {
        if (busy) {
            DebugLog.add("질문 무시: 이전 답변 진행 중")
            return
        }
        val client = ApiClient.get(app)
        if (client == null) {
            notice = "API 키가 없어요. ⚙ 설정에서 키를 넣어 주세요."
            noticeIsError = true
            changed()
            return
        }
        val subj = prefs.subject
        val model = prefs.modelFor(subj)
        // 설정 화면 등에서 과목이 바뀌었으면 새 지시문으로 새로 시작
        if (tutor != null && tutorSubject != subj.id) tutor = null
        // 모델만 바뀌었으면 대화를 이어서 새 모델로
        tutor?.let { if (it.model != model) tutor = it.withModel(model) }
        val t = tutor ?: ClaudeTutor(client, model, prefs.promptFor(subj)).also {
            tutor = it
            tutorSubject = subj.id
        }

        val image = pendingImage
        // 이미지를 함께 보내는 질문이면 대화 안에 그 이미지를 보여준다
        val entryThumb = if (image != null) pendingPreview else null
        inFlight = image
        DebugLog.add("질문 전송 (이미지 포함=${image != null}, 대화 ${entries.size + 1}번째)")
        val entry = Entry(if (label.length > 60) label.take(60) + "…" else label, entryThumb)
        entries += entry
        notice = null
        busy = true
        waiting = true
        changed()

        val gen = generation
        thread(name = "claude-ask") {
            try {
                t.ask(image?.invoke(), "image/png", question) { delta ->
                    main.post {
                        if (gen != generation) return@post
                        waiting = false
                        entry.raw.append(delta)
                        changed()
                    }
                }
                main.post {
                    if (gen != generation) return@post
                    // 이미지를 함께 보낸 질문이 성공했으면, 다음 질문부터는 대화 기록에 있는 이미지를 쓴다
                    if (pendingImage === image) {
                        pendingImage = null
                        pendingPreview = null
                    }
                    inFlight = null
                    busy = false
                    waiting = false
                    DebugLog.add("답변 완료 (${entry.raw.length}자)")
                    changed()
                }
            } catch (e: ClaudeTutor.TutorException) {
                main.post { fail(gen, entry, e.message ?: "오류가 발생했어요.") }
            } catch (e: Exception) {
                main.post { fail(gen, entry, "오류: ${e.javaClass.simpleName} ${e.message ?: ""}") }
            }
        }
    }

    private fun fail(gen: Int, entry: Entry, msg: String) {
        if (gen != generation) return
        DebugLog.add("답변 오류: $msg")
        entry.error = msg
        inFlight = null
        busy = false
        waiting = false
        changed()
    }

    companion object {
        @Volatile
        private var instance: ChatSession? = null

        fun get(ctx: Context): ChatSession =
            instance ?: synchronized(this) {
                instance ?: ChatSession(ctx.applicationContext).also { instance = it }
            }
    }
}
