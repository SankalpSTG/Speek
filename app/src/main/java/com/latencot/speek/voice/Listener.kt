package com.latencot.speek.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Speech to text with the phone's own recogniser. [heard] carries the words as they are
 * recognised; [onFinal] gets the whole utterance once you stop talking.
 * Must be created and used on the main thread.
 */
class Listener(context: Context, private val onFinal: (String) -> Unit, private val onError: (String) -> Unit) {

    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard.asStateFlow()

    /** Mic loudness, 0..1, for the pulse around the button. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    val available: Boolean get() = recognizer != null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _level.value = 0f
            }
            override fun onError(error: Int) {
                stopped()
                // Silence is not a failure: you tapped the mic and said nothing.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_CLIENT) {
                    onError(describe(error))
                }
            }
            override fun onResults(results: Bundle?) {
                val text = best(results)
                stopped()
                if (text.isNotBlank()) onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults).takeIf { it.isNotBlank() }?.let { _heard.value = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun start() {
        val r = recognizer ?: return onError("This phone has no speech recogniser.")
        _heard.value = ""
        _listening.value = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    /** Stop listening; what was said so far is still recognised and delivered. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        stopped()
    }

    fun destroy() {
        recognizer?.destroy()
    }

    private fun stopped() {
        _listening.value = false
        _level.value = 0f
        _heard.value = ""
    }

    private fun best(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Speek needs the microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy; try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        else -> "Speech recognition failed (code $error)."
    }
}
