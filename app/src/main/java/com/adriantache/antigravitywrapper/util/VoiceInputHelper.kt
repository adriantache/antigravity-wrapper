package com.adriantache.antigravitywrapper.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

object VoiceInputHelper {

    /**
     * Appends a spoken transcript onto an existing draft without clobbering prior text
     * or introducing duplicate spaces.
     */
    fun mergeVoiceTranscript(baseDraft: String, transcript: String): String {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return baseDraft
        if (baseDraft.isEmpty()) return cleaned
        return if (baseDraft.last().isWhitespace()) {
            baseDraft + cleaned
        } else {
            "$baseDraft $cleaned"
        }
    }

    /**
     * Maps [SpeechRecognizer] error codes to user-facing messages, returning null for
     * benign stop/no-speech states when the user stopped listening or nothing was spoken.
     */
    fun mapSpeechError(errorCode: Int, userRequestedStop: Boolean, hasPartialTranscript: Boolean): String? {
        return when (errorCode) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission is required for voice input"
            SpeechRecognizer.ERROR_AUDIO ->
                "Could not access microphone audio"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Voice input requires a network connection"
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                if (userRequestedStop || hasPartialTranscript) null else "No speech detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                "Voice recognizer is busy — try again"
            SpeechRecognizer.ERROR_CLIENT ->
                if (userRequestedStop) null else "Voice input stopped unexpectedly"
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
                "Voice recognition service is unavailable"
            else ->
                if (userRequestedStop) null else "Voice input failed"
        }
    }

    fun createRecognitionIntent(packageName: String, prompt: String? = null): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (!prompt.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            }
        }
    }
}

enum class VoiceStartResult {
    STARTED,
    FALLBACK_TO_INTENT
}

/**
 * Main-thread controller around Android's [SpeechRecognizer] for live dictation into the
 * native composer bar. Creates a fresh recognizer per session to avoid OEM busy-state bugs
 * and guarantees clean teardown on stop, cancel, or dispose.
 */
class VoiceDictationController(
    private val context: Context,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onTranscriptUpdate: (String) -> Unit,
    private val onError: (message: String, isPermissionError: Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var baseDraft: String = ""
    private var latestPartial: String = ""
    private var isActive: Boolean = false
    private var userRequestedStop: Boolean = false
    private var stopTimeoutRunnable: Runnable? = null

    fun start(currentDraft: String): VoiceStartResult {
        cancel()

        val instance = createBestSpeechRecognizer() ?: return VoiceStartResult.FALLBACK_TO_INTENT
        baseDraft = currentDraft
        latestPartial = ""
        userRequestedStop = false
        isActive = true
        recognizer = instance
        onListeningChanged(true)

        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isActive) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) {
                    latestPartial = text
                    onTranscriptUpdate(VoiceInputHelper.mergeVoiceTranscript(baseDraft, text))
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isActive) return
                val finalText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                val chosen = finalText.ifEmpty { latestPartial }
                if (chosen.isNotEmpty()) {
                    onTranscriptUpdate(VoiceInputHelper.mergeVoiceTranscript(baseDraft, chosen))
                }
                finishInternal()
            }

            override fun onError(error: Int) {
                if (!isActive) return
                val hadPartial = latestPartial.isNotEmpty()
                if (hadPartial) {
                    onTranscriptUpdate(VoiceInputHelper.mergeVoiceTranscript(baseDraft, latestPartial))
                }
                val wasUserStop = userRequestedStop
                finishInternal()
                val message = VoiceInputHelper.mapSpeechError(
                    errorCode = error,
                    userRequestedStop = wasUserStop,
                    hasPartialTranscript = hadPartial
                )
                if (!message.isNullOrBlank()) {
                    onError(message, error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                }
            }
        })

        return try {
            val intent = VoiceInputHelper.createRecognitionIntent(context.packageName)
            instance.startListening(intent)
            VoiceStartResult.STARTED
        } catch (_: Exception) {
            finishInternal()
            VoiceStartResult.FALLBACK_TO_INTENT
        }
    }

    fun stop() {
        if (!isActive) return
        if (userRequestedStop) {
            // Second tap while stopping: force-finish immediately
            if (latestPartial.isNotEmpty()) {
                onTranscriptUpdate(VoiceInputHelper.mergeVoiceTranscript(baseDraft, latestPartial))
            }
            finishInternal()
            return
        }
        userRequestedStop = true
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            finishInternal()
            return
        }
        val timeout = Runnable {
            if (isActive) {
                if (latestPartial.isNotEmpty()) {
                    onTranscriptUpdate(VoiceInputHelper.mergeVoiceTranscript(baseDraft, latestPartial))
                }
                finishInternal()
            }
        }
        stopTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 1500L)
    }

    fun cancel() {
        if (!isActive && recognizer == null) return
        userRequestedStop = true
        finishInternal()
    }

    private fun finishInternal() {
        stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        stopTimeoutRunnable = null
        val old = recognizer
        recognizer = null
        val wasActive = isActive
        isActive = false
        latestPartial = ""
        if (old != null) {
            try {
                old.cancel()
            } catch (_: Exception) {
                // Ignore recognizer cancel exceptions
            }
            try {
                old.destroy()
            } catch (_: Exception) {
                // Ignore recognizer destroy exceptions
            }
        }
        if (wasActive) {
            onListeningChanged(false)
        }
    }

    private fun createBestSpeechRecognizer(): SpeechRecognizer? {
        return try {
            when {
                SpeechRecognizer.isRecognitionAvailable(context) ->
                    SpeechRecognizer.createSpeechRecognizer(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
