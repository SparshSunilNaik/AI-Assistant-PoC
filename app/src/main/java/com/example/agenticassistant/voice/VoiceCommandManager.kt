package com.example.agenticassistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Manages voice input via Android SpeechRecognizer.
 * Converts speech to text and classifies recognized text into commands.
 */
class VoiceCommandManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandManager"
    }

    /** All supported voice commands */
    enum class Command {
        SCAN_INBOX,
        SCHEDULE_MEETING,
        DAILY_BRIEFING,
        UNKNOWN
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResult: ((String, Command) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /**
     * Starts listening for a voice command.
     * @param onResult Called with (rawText, parsedCommand) on successful recognition.
     * @param onError Called with an error message on failure.
     */
    fun startListening(
        onResult: (String, Command) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onResult = onResult
        this.onError = onError

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            onError("Speech recognition is not available on this device.")
            return
        }

        // Destroy previous instance if any
        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech started")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech ended")
                }

                override fun onError(error: Int) {
                    val message = mapErrorCode(error)
                    Log.e(TAG, "Recognition error: $message (code $error)")
                    this@VoiceCommandManager.onError?.invoke(message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Recognized text: \"$text\"")
                    val command = parseCommand(text)
                    Log.d(TAG, "Parsed command: $command")
                    this@VoiceCommandManager.onResult?.invoke(text, command)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        Log.d(TAG, "Starting speech recognizer")
        speechRecognizer?.startListening(intent)
    }

    /** Stops listening and releases the recognizer. */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /** Releases all resources. Call in Activity.onDestroy(). */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Parses recognized text into a [Command].
     * Uses keyword matching with normalization.
     */
    fun parseCommand(text: String): Command {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("scan inbox") || lower.contains("check inbox") ||
                lower.contains("check my inbox") || lower.contains("scan my inbox") ||
                lower.contains("check email") || lower.contains("check my email")
                -> Command.SCAN_INBOX

            lower.contains("schedule meeting") || lower.contains("schedule a meeting") ||
                lower.contains("create meeting") || lower.contains("create a meeting") ||
                lower.contains("book meeting") || lower.contains("book a meeting") ||
                lower.contains("set up a meeting") || lower.contains("set up meeting")
                -> Command.SCHEDULE_MEETING

            lower.contains("daily briefing") || lower.contains("today briefing") ||
                lower.contains("what meetings") || lower.contains("meetings today") ||
                lower.contains("my meetings") || lower.contains("today's meetings") ||
                lower.contains("what's on my calendar") || lower.contains("what do i have today")
                -> Command.DAILY_BRIEFING

            else -> Command.UNKNOWN
        }
    }

    private fun mapErrorCode(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions — grant RECORD_AUDIO"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized — try again"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again"
            else -> "Unknown error (code $error)"
        }
    }
}

