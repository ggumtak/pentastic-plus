package com.ggumtak.pentastic

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea

/**
 * 손글씨 → 글자 후보 (구글 ML Kit 필기 인식, 일본어). 인식은 기기 안에서 하므로 API 비용이 없다.
 * 처음 한 번 인식 모델을 내려받는다.
 */
object Handwriting {

    private var model: DigitalInkRecognitionModel? = null
    private var recognizer: DigitalInkRecognizer? = null
    private var downloading = false
    private val waiting = mutableListOf<(String?) -> Unit>()

    /** 모델을 준비한다. 성공하면 onReady(null), 실패하면 onReady(오류 문구). 메인 스레드에서 호출된다. */
    fun prepare(onReady: (String?) -> Unit) {
        if (recognizer != null) {
            onReady(null)
            return
        }
        waiting += onReady
        if (downloading) return
        downloading = true
        val m = try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ja")
                ?: throw IllegalStateException("일본어 필기 모델을 찾지 못했습니다.")
            DigitalInkRecognitionModel.builder(id).build()
        } catch (e: Exception) {
            finish("필기 인식을 시작하지 못했습니다: ${e.message}")
            return
        }
        model = m
        val manager = RemoteModelManager.getInstance()
        DebugLog.add("필기 인식 모델 준비 시작")
        manager.download(m, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build())
                DebugLog.add("필기 인식 모델 준비 완료")
                finish(null)
            }
            .addOnFailureListener { e ->
                DebugLog.add("필기 인식 모델 실패: ${e.message}")
                finish("필기 인식 모델을 내려받지 못했습니다. 인터넷 연결을 확인해 주세요. (${e.message})")
            }
    }

    /** 모델을 이미 받아 두었으면 인식기를 미리 연다 (새로 내려받지는 않는다). */
    fun prepareIfDownloaded() {
        try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ja") ?: return
            val m = DigitalInkRecognitionModel.builder(id).build()
            RemoteModelManager.getInstance().isModelDownloaded(m)
                .addOnSuccessListener { downloaded -> if (downloaded) prepare { } }
        } catch (_: Exception) {
        }
    }

    private fun finish(error: String?) {
        downloading = false
        val list = waiting.toList()
        waiting.clear()
        list.forEach { it(error) }
    }

    /** 획 목록(각 획은 x, y, 시각 목록)으로 후보 글자를 받는다. 결과는 메인 스레드로 온다. */
    fun recognize(strokes: List<List<InkView.Pt>>, width: Float, height: Float, onResult: (List<String>) -> Unit) {
        val r = recognizer ?: return onResult(emptyList())
        if (strokes.isEmpty()) return onResult(emptyList())
        val ink = Ink.builder().apply {
            strokes.forEach { s ->
                val sb = Ink.Stroke.builder()
                s.forEach { p -> sb.addPoint(Ink.Point.create(p.x, p.y, p.t)) }
                addStroke(sb.build())
            }
        }.build()
        val ctx = RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(width, height))
            .build()
        r.recognize(ink, ctx)
            .addOnSuccessListener { result -> onResult(result.candidates.map { it.text }.distinct().take(10)) }
            .addOnFailureListener { onResult(emptyList()) }
    }
}
