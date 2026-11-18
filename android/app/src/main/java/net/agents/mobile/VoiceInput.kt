package net.pdd.comagic.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class VoiceInput(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var listening = false

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    init {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                listening = false
                onError(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results.firstResult()
                if (text.isBlank()) onError("没听清，再试一次") else onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults.firstResult()
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    fun start() {
        if (listening) return
        listening = true
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                listening = false
                onError("无法启动语音识别")
            }
    }

    fun stop() {
        if (!listening) return
        runCatching { recognizer.stopListening() }
    }

    fun destroy() {
        listening = false
        runCatching { recognizer.destroy() }
    }

    private fun Bundle?.firstResult(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别不可用"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络异常"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，再试一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，稍后再试"
        else -> "语音识别失败（$error）"
    }
}
