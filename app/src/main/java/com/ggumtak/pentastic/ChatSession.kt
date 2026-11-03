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

    class Entry(val label: String, val thumb: Bitmap?, val question: String) {
        val raw = StringBuilder()
        var error: String? = null
        /** 오류로 끝나서 [다시 시도]를 보여줄지 */
        var retryable = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(app)
    private val listeners = LinkedHashSet<() -> Unit>()

    val entries = mutableListOf<Entry>()

    /** 아직 보내지 않은 캡처. 다음 질문에 함께 보내고, 성공하면 비운다. */
    private var pendingImage: (() -> ByteArray)? = null
    private var pendingPreview: Bitmap? = null
    private var pendingMime = "image/jpeg"

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
    // 답변 스레드(shouldStop)에서도 읽으므로 @Volatile: 멈춤·새 대화가 바로 보여야 스트림이 끊기고 토큰이 낭비되지 않는다
    @Volatile
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
    fun attach(preview: Bitmap, image: () -> ByteArray, allowAutoAsk: Boolean = true, mime: String = "image/jpeg") {
        DebugLog.add("대화에 이미지 붙음 (${preview.width}x${preview.height}, 화면 ${listeners.size}개)")
        pendingImage = image
        pendingPreview = preview
        pendingMime = mime
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

    /** 답변 중이면 멈춘다. 그때까지 받은 글은 남긴다. */
    fun stop() {
        if (!busy) return
        generation++ // 진행 중인 요청의 남은 글과 결과는 무시
        // 진행 중인 스트림을 닫고, 대화는 새 인스턴스로 이어 간다 (멈춘 질문·답은 기록에 넣지 않음)
        tutor?.let { old ->
            old.cancel()
            tutor = old.withModel(old.model)
        }
        main.removeCallbacks(flush)
        flush.run()
        entries.lastOrNull()?.let { if (it.error == null) it.error = "(멈춤)" }
        inFlight = null
        busy = false
        waiting = false
        DebugLog.add("답변 멈춤")
        changed()
    }

    /** 오류로 끝난 마지막 질문을 다시 보낸다. */
    fun retryLast() {
        if (busy) return
        val last = entries.lastOrNull() ?: return
        if (!last.retryable) return
        // 키가 없으면 send()가 보내지 않으므로, 질문을 지우기 전에 확인한다
        if (ApiClient.get(app) == null) {
            notice = "API 키가 없어요. ⚙ 설정에서 키를 넣어 주세요."
            noticeIsError = true
            changed()
            return
        }
        entries.removeAt(entries.lastIndex)
        send(last.question, last.label)
    }

    // 답변 글은 조각마다 다시 그리지 않고 40ms마다 모아서 한 번에 그린다 (긴 답변에서도 부드럽게)
    private val pendingText = StringBuilder()
    private var pendingEntry: Entry? = null
    private var flushScheduled = false
    private val flush = Runnable {
        flushScheduled = false
        val e = pendingEntry
        val chunk = synchronized(pendingText) {
            val t = pendingText.toString()
            pendingText.setLength(0)
            t
        }
        if (e != null && chunk.isNotEmpty()) {
            waiting = false
            e.raw.append(chunk)
            changed()
        }
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
        val mime = pendingMime
        // 이미지를 함께 보내는 질문이면 대화 안에 그 이미지를 보여준다
        val entryThumb = if (image != null) pendingPreview else null
        inFlight = image
        DebugLog.add("질문 전송 (이미지 포함=${image != null}, 대화 ${entries.size + 1}번째, ${model.removePrefix("claude-")})")
        val entry = Entry(if (label.length > 60) label.take(60) + "…" else label, entryThumb, question)
        entries += entry
        notice = null
        busy = true
        waiting = true
        changed()

        val gen = generation
        synchronized(pendingText) { pendingText.setLength(0) }
        pendingEntry = entry
        val started = System.currentTimeMillis()
        thread(name = "claude-ask") {
            var firstLogged = false
            try {
                t.ask(image?.invoke(), mime, question, shouldStop = { gen != generation }) { delta ->
                    if (!firstLogged) {
                        firstLogged = true
                        DebugLog.add("첫 글자까지 ${System.currentTimeMillis() - started}ms")
                    }
                    // 멈춘 뒤에 늦게 온 글은 다음 답에 섞이지 않게 버린다
                    if (gen != generation) return@ask
                    synchronized(pendingText) { pendingText.append(delta) }
                    main.post {
                        if (gen != generation || flushScheduled) return@post
                        flushScheduled = true
                        main.postDelayed(flush, 40)
                    }
                }
                main.post {
                    if (gen != generation) return@post
                    main.removeCallbacks(flush)
                    flush.run()
                    // 이미지를 함께 보낸 질문이 성공했으면, 다음 질문부터는 대화 기록에 있는 이미지를 쓴다
                    if (pendingImage === image) {
                        pendingImage = null
                        pendingPreview = null
                    }
                    inFlight = null
                    busy = false
                    waiting = false
                    DebugLog.add("답변 완료 (${entry.raw.length}자, ${System.currentTimeMillis() - started}ms)")
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
        main.removeCallbacks(flush)
        flush.run()
        DebugLog.add("답변 오류: $msg")
        entry.error = msg
        entry.retryable = true
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
