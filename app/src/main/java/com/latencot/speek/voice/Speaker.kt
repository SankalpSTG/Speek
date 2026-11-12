package com.latencot.speek.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Text to speech with the phone's engine. Lines are queued, so a burst of messages is read in order.
 * A phone whose engine does not start has no read-aloud at all: [available] stays false and the
 * setting is not shown.
 */
class Speaker(context: Context) {

    private var ready = false
    private var failed = false
    private val waiting = mutableListOf<String>()
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
        _available.value = ready
        if (ready) {
            waiting.forEach(::queue)
        }
        waiting.clear()
    }

    fun say(text: String) {
        when {
            ready -> queue(text)
            !failed -> waiting += text
        }
    }

    fun hush() {
        waiting.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun queue(text: String) {
        // The engine limits one utterance's length; long digests are read in pieces.
        text.chunked(TextToSpeech.getMaxSpeechInputLength() - 1).forEach {
            tts.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }
}
