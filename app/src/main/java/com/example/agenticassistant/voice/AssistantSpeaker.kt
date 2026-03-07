package com.example.agenticassistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Text-to-Speech wrapper for the assistant.
 * Speaks assistant responses aloud to the user.
 */
class AssistantSpeaker(context: Context) {

    companion object {
        private const val TAG = "AssistantSpeaker"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (isInitialized) {
                    Log.d(TAG, "TTS initialized successfully")
                } else {
                    Log.e(TAG, "TTS language not supported")
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    /**
     * Speaks the given message aloud.
     * If TTS is not initialized yet, the message is silently dropped.
     */
    fun speak(message: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak: $message")
            return
        }
        Log.d(TAG, "Speaking: $message")
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "assistant_utterance")
    }

    /**
     * Stops any ongoing speech.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Releases TTS resources. Call in Activity.onDestroy().
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS")
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

