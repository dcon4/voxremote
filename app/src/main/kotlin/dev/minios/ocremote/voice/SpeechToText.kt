package dev.minios.ocremote.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.minios.ocremote.R
import dev.minios.ocremote.logging.AppLogger

private const val TAG = "SpeechToText"

/** UI/state callbacks for dictation. All invoked on the main thread. */
interface SpeechToTextListener {
    /** Microphone state changed, including stitch re-listens. */
    fun onListeningChanged(listening: Boolean)

    /** A complete utterance (stitched across early cuts if needed). */
    fun onTranscript(text: String)

    /** User-facing failure message, already localized. */
    fun onSpeechError(message: String)

    /** The session ended with no usable speech (timeout / no match). */
    fun onNoSpeech()
}

/**
 * Dictation with utterance stitching.
 *
 * The phone's speech service often declares a result "final" after only
 * 1-2 seconds of silence, ignoring the requested pause window. Like ARYA,
 * when a final result arrives too early we keep re-listening in short
 * sessions, append what we hear, and only finish after [pauseSeconds] of
 * real silence — so mid-sentence pauses don't cut your sentence in half.
 */
class SpeechToText(
    private val context: Context,
    private val listenSeconds: () -> Int,
    private val pauseSeconds: () -> Int,
    private val listener: SpeechToTextListener,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var active = false
    private var listening = false

    // Stitching state (mirrors ARYA's home_screen.dart).
    private var stitchConfirming = false
    private var stitchBase = ""
    private var stitchSession = ""
    private var stitchRelistens = 0
    private var suppressResults = false
    private var lastWords = ""
    private var lastSpeechAtMs = 0L
    private var stitchTimer: Runnable? = null

    // The rolling partial transcript of the current (non-stitch) session.
    private var partialWords = ""

    private var listenTimeout: Runnable? = null

    fun isActive(): Boolean = active

    fun isListening(): Boolean = listening || stitchConfirming

    /** Must be called from the main thread, with RECORD_AUDIO granted. */
    fun start() {
        cancelTimers()
        resetStitch()
        active = true
        suppressResults = false
        lastWords = ""
        partialWords = ""
        lastSpeechAtMs = System.currentTimeMillis()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fail(context.getString(R.string.voice_permission_denied))
            return
        }

        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        engine.setRecognitionListener(recognitionListener)
        beginSession(engine)
        scheduleListenTimeout()
    }

    /** Stop listening. During a stitch window this means "use what I have". */
    fun stop() {
        if (!active) return
        if (stitchConfirming) {
            finishStitch()
            return
        }
        // Manual stop with partial words: deliver them.
        val words = accumulatedWords()
        teardown()
        if (words.isNotBlank()) {
            listener.onTranscript(words)
        } else {
            listener.onNoSpeech()
        }
    }

    /** Abandon everything silently (screen closed, navigation away). */
    fun cancel() {
        if (!active) return
        teardown()
    }

    // ============ Sessions ============

    private fun beginSession(engine: SpeechRecognizer) {
        val pauseMs = pauseSeconds().coerceIn(1, 30) * 1000L
        val listenMs = listenSeconds().coerceIn(10, 300) * 1000L
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Requested pause window; many engines honor these.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseMs)
            // Overall session cap; we also enforce it ourselves below.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, listenMs)
        }
        try {
            engine.startListening(intent)
            setListening(true)
        } catch (e: Exception) {
            AppLogger.e(TAG, "startListening failed", e)
            fail(context.getString(R.string.voice_recognition_failed))
        }
    }

    private fun scheduleListenTimeout() {
        cancelListenTimeout()
        val totalMs = (listenSeconds().coerceIn(10, 300) * 1000L) + (pauseSeconds() * 1000L)
        val runnable = Runnable {
            if (!active) return@Runnable
            if (stitchConfirming) {
                finishStitch()
            } else {
                val words = accumulatedWords()
                teardown()
                if (words.isNotBlank()) listener.onTranscript(words) else listener.onNoSpeech()
            }
        }
        listenTimeout = runnable
        mainHandler.postDelayed(runnable, totalMs)
    }

    private fun cancelListenTimeout() {
        listenTimeout?.let(mainHandler::removeCallbacks)
        listenTimeout = null
    }

    private fun teardown() {
        active = false
        setListening(false)
        cancelTimers()
        resetStitch()
        suppressResults = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        recognizer?.destroy()
        recognizer = null
        suppressResults = false
    }

    private fun cancelTimers() {
        stitchTimer?.let(mainHandler::removeCallbacks)
        stitchTimer = null
        cancelListenTimeout()
    }

    private fun resetStitch() {
        stitchConfirming = false
        stitchBase = ""
        stitchSession = ""
        stitchRelistens = 0
    }

    private fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        listener.onListeningChanged(value || stitchConfirming)
    }

    private fun fail(message: String) {
        val wasActive = active
        teardown()
        if (wasActive) listener.onSpeechError(message)
    }

    private fun accumulatedWords(): String {
        val fromStitch = if (stitchBase.isNotBlank()) {
            if (stitchSession.isNotBlank() && !stitchBase.endsWith(stitchSession)) {
                "$stitchBase $stitchSession"
            } else {
                stitchBase
            }
        } else {
            ""
        }
        return fromStitch.ifBlank { lastWords.ifBlank { partialWords } }
    }

    // ============ Stitch logic ============

    private fun onFinalResult(words: String) {
        if (suppressResults) return

        if (stitchConfirming) {
            handleStitchFinal(words)
            return
        }

        if (words.isBlank()) {
            // An empty final means this session heard nothing useful.
            if (stitchBase.isBlank() && lastWords.isBlank() && partialWords.isBlank()) {
                // Keep waiting within the overall timeout; restart the session.
                restartSessionSoft()
            }
            return
        }

        lastWords = words
        lastSpeechAtMs = System.currentTimeMillis()

        if (needsStitchConfirm(words)) {
            beginStitchConfirm(words)
        } else {
            val finished = words
            teardown()
            listener.onTranscript(finished)
        }
    }

    /**
     * True when this utterance may have been cut short by early
     * end-of-speech detection and should be stitched with any continuation.
     */
    private fun needsStitchConfirm(words: String): Boolean {
        val wordCount = words.trim().split(Regex("\\s+")).size
        // One or two words cannot be split by a mid-utterance pause.
        if (wordCount < 3) return false
        val silence = System.currentTimeMillis() - lastSpeechAtMs
        val pauseMs = pauseSeconds().coerceIn(1, 30) * 1000L
        // If the engine honored our pause window, the final result already
        // arrived after (nearly) the full silence — dispatch immediately.
        return silence < (pauseMs * 85) / 100
    }

    private fun beginStitchConfirm(words: String) {
        stitchConfirming = true
        stitchBase = words
        stitchSession = ""
        stitchRelistens = 0
        lastSpeechAtMs = System.currentTimeMillis()
        AppLogger.i(TAG, "Possible early cut; stitching for \"${words.take(60)}\"")
        listener.onListeningChanged(true)
        resetStitchTimer()
        relisten()
    }

    private fun resetStitchTimer() {
        stitchTimer?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            if (stitchConfirming) finishStitch()
        }
        stitchTimer = runnable
        mainHandler.postDelayed(runnable, pauseSeconds().coerceIn(1, 30) * 1000L)
    }

    private fun relisten() {
        if (!stitchConfirming || stitchRelistens >= MAX_STITCH_RELISTENS) return
        stitchRelistens++
        val engine = recognizer
        if (engine == null) {
            finishStitch()
            return
        }
        // The final result can arrive while the session is winding down;
        // give it a beat before restarting the mic.
        mainHandler.postDelayed({
            if (!stitchConfirming) return@postDelayed
            try {
                engine.cancel()
                beginSession(engine)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Stitch re-listen failed", e)
                finishStitch()
            }
        }, 300)
    }

    private fun handleStitchFinal(words: String) {
        if (words.isNotBlank() && !stitchBase.endsWith(words)) {
            stitchBase = if (stitchBase.isBlank()) words else "$stitchBase $words"
            lastSpeechAtMs = System.currentTimeMillis()
            resetStitchTimer()
            AppLogger.i(TAG, "Stitch segment: \"${stitchBase.take(60)}\"")
        }
        stitchSession = ""
        if (stitchRelistens < MAX_STITCH_RELISTENS) {
            relisten()
        }
        // else: the timer still governs the finish.
    }

    private fun finishStitch() {
        if (!stitchConfirming) return
        val finished = accumulatedWords()
        teardown()
        if (finished.isNotBlank()) {
            listener.onTranscript(finished)
        } else {
            listener.onNoSpeech()
        }
    }

    private fun restartSessionSoft() {
        val engine = recognizer ?: return fail(context.getString(R.string.voice_recognition_failed))
        mainHandler.postDelayed({
            if (!active || stitchConfirming) return@postDelayed
            try {
                engine.cancel()
                beginSession(engine)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Soft restart failed", e)
                fail(context.getString(R.string.voice_recognition_failed))
            }
        }, 200)
    }

    // ============ RecognitionListener ============

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(errorCode: Int) {
            if (suppressResults || !active) return
            when (errorCode) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail(context.getString(R.string.voice_permission_denied))
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // A busy engine during stitching: retry within the window.
                    if (stitchConfirming) relisten() else restartSessionSoft()
                }
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (stitchConfirming) {
                        // Nothing more heard inside the window; the timer finishes.
                        return
                    }
                    if (lastWords.isBlank() && partialWords.isBlank() && stitchBase.isBlank()) {
                        teardown()
                        listener.onNoSpeech()
                    }
                    // Otherwise keep the accumulated words; overall timeout will flush.
                }
                else -> {
                    AppLogger.w(TAG, "Recognition error $errorCode")
                    if (stitchConfirming) {
                        relisten()
                    } else if (lastWords.isBlank() && partialWords.isBlank()) {
                        fail(context.getString(R.string.voice_recognition_failed))
                    } else {
                        // Deliver what we heard rather than losing it.
                        val words = accumulatedWords()
                        teardown()
                        listener.onTranscript(words)
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (suppressResults) return
            val words = extract(results)
            onFinalResult(words)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (suppressResults) return
            val words = extract(partialResults)
            if (words.isBlank()) return
            lastSpeechAtMs = System.currentTimeMillis()
            if (stitchConfirming) {
                stitchSession = words
            } else {
                partialWords = words
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun extract(results: Bundle?): String {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
        return list.firstOrNull().orEmpty().trim()
    }

    companion object {
        private const val MAX_STITCH_RELISTENS = 30
    }
}
