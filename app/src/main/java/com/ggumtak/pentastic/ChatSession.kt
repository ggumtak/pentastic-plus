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

    /** 가장 최근 캡처 미리보기 (머리줄 썸네일) */
    var latestPreview: Bitmap? = null
        private set

    /** 아직 보내지 않은 캡처. 다음 질문에 함께 보내고, 성공하면 비운다. */
    private var pendingImage: (() -> ByteArray)? = null
    private var pendingPreview: Bitmap? = null
    private var captureCount = 0

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
    private var generation = 0

    val isEmpty get() = entries.isEmpty() && latestPreview == null

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun changed() = listeners.toList().forEach { it() }

    /** 새 캡처를 대화에 붙인다. 설정에서 자동 요청을 켰으면 바로 '해석·문법'을 묻는다. */
    fun attach(preview: Bitmap, image: () -> ByteArray) {
        pendingImage = image
        pendingPreview = preview
        latestPreview = preview
        captureCount++
        noticeIsError = false
        notice = if (entries.isEmpty()) "아직 전송 전이에요. 버튼을 누르거나 아래에 직접 물어보세요."
        else "새 캡처를 붙였어요. 버튼을 누르거나 아래에 물어보세요."
        changed()
        if (prefs.autoAsk) send(ClaudeTutor.Q_GRAMMAR, "해석·문법")
    }

    /** 대화를 비우고 새로 시작한다. 진행 중인 답변은 멈춘다. */
    fun reset() {
        generation++
        tutor?.cancel()
        tutor = null
        entries.clear()
        pendingImage = null
        pendingPreview = null
        latestPreview = null
        captureCount = 0
        busy = false
        waiting = false
        notice = null
        noticeIsError = false
        changed()
    }

    fun send(question: String, label: String) {
        if (busy) return
        val client = ApiClient.get(app)
        if (client == null) {
            notice = "API 키가 없어요. ⚙ 설정에서 키를 넣어 주세요."
            noticeIsError = true
            changed()
            return
        }
        val t = tutor ?: ClaudeTutor(client, prefs.model, prefs.systemPrompt).also { tutor = it }

        val image = pendingImage
        // 두 번째 캡처부터는 어떤 이미지에 대한 질문인지 답변 안에 작은 그림으로 표시
        val entryThumb = if (image != null && captureCount > 1) pendingPreview else null
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
                    if (pendingImage === image) pendingImage = null
                    busy = false
                    waiting = false
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
        entry.error = msg
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
