package com.smartscan.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin wrapper around [SpeechRecognizer]. Must be used from the main thread.
 * - [startContinuous]: hands-free command loop (restarts after every result / timeout).
 * - [listenOnce]: one dictation with longer silence tolerance (metadata).
 */
class VoiceController(private val context: Context, private val language: String) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var continuous = false
    private var onText: ((String) -> Unit)? = null
    private var onceCallback: ((String?) -> Unit)? = null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startContinuous(callback: (String) -> Unit) {
        stop()
        continuous = true
        onText = callback
        start(longSilence = false)
    }

    fun listenOnce(callback: (String?) -> Unit) {
        stop()
        onceCallback = callback
        start(longSilence = true)
    }

    val isContinuous: Boolean get() = continuous

    fun stop() {
        continuous = false
        onText = null
        onceCallback = null
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        _listening.value = false
        _partial.value = ""
    }

    fun destroy() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun ensure(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

    private fun start(longSilence: Boolean) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (longSilence) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
        }
        try {
            ensure().startListening(intent)
            _listening.value = true
        } catch (e: Exception) {
            _listening.value = false
            onceCallback?.let { onceCallback = null; it(null) }
        }
    }

    private fun deliver(text: String?) {
        _partial.value = ""
        val once = onceCallback
        if (once != null) {
            onceCallback = null
            _listening.value = false
            once(text)
            return
        }
        if (!text.isNullOrBlank()) onText?.invoke(text)
        restartLater(150)
    }

    private fun restartLater(ms: Long) {
        if (!continuous) {
            _listening.value = false
            return
        }
        main.postDelayed({ if (continuous) start(longSilence = false) }, ms)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.let { _partial.value = it }
        }

        override fun onResults(results: Bundle?) {
            deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
        }

        override fun onError(error: Int) {
            if (onceCallback != null) {
                deliver(null)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    runCatching { recognizer?.cancel() }
                    restartLater(600)
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartLater(150)
                else -> restartLater(1000)
            }
        }
    }
}
