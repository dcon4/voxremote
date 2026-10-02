package dev.minios.ocremote.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "VoiceController"

/** SharedPreferences file for state the notification service must survive on. */
private const val VOICE_STATE_PREFS = "voice_state"
private const val KEY_LAST_REPLY = "last_reply"
private const val KEY_LAST_SERVER_ID = "last_server_id"
private const val KEY_LAST_SESSION_ID = "last_session_id"

/** Android TTS chokes on very long utterances; chunk like ARYA does. */
private const val MAX_TTS_CHUNK = 3500

private const val ANNOUNCE_PREFIX = "voxremote:announce:"

/**
 * App-wide voice hub.
 *
 * - Text-to-speech for assistant replies: chunked, serialized, with a
 *   generation counter so a new utterance skips any still-queued chunks.
 * - Remembers the last assistant reply so the notification can speak it
 *   even after the app process was restarted.
 * - Remembers the last opened session so the notification "Listen" action
 *   can jump straight back into that chat.
 * - Optional spoken cue before dictation starts (for blind/screen-reader use).
 */
@Singleton
/** A text-to-speech engine the user can pick in Settings. */
data class TtsEngineInfo(val label: String, val packageName: String)

class VoiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences(VOICE_STATE_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Bumped whenever speech is restarted or stopped; stale chunks check it. */
    private val generation = AtomicInteger(0)
    private var pendingAnnounceRunnable: (() -> Unit)? = null
    private var pendingAnnounceUtterance: String? = null
    private var currentEnginePackage: String = ""
    private var engineFallbackNotified = false

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /**
     * One-shot "start dictating now" trigger from the notification Listen
     * action. replay=1 so a cold-start (app process created by the service)
     * still delivers to ChatScreen once it composes; the consumer resets the
     * replay cache after handling so it fires exactly once.
     */
    private val _micTrigger = MutableSharedFlow<Unit>(replay = 1)
    val micTrigger: SharedFlow<Unit> = _micTrigger.asSharedFlow()

    fun requestMicStart() {
        _micTrigger.tryEmit(Unit)
    }

    fun consumeMicTrigger() {
        _micTrigger.resetReplayCache()
    }

    init {
        scope.launch {
            val preferred = settingsRepository.voiceTtsEngine.first()
            createTts(preferred)
        }
    }

    /**
     * Create (or recreate) the TTS engine.
     *
     * [enginePackage] lets the user pick a favorite engine such as Ivona —
     * the platform default alone would silently ignore it. If the chosen
     * engine is missing or fails to init, fall back to the system default.
     */
    private fun createTts(enginePackage: String) {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        currentEnginePackage = enginePackage
        engineFallbackNotified = false

        val callback = TextToSpeech.OnInitListener { status -> onTtsInit(status, enginePackage) }
        tts = if (enginePackage.isBlank()) {
            TextToSpeech(context, callback)
        } else {
            AppLogger.i(TAG, "Initializing TTS engine: $enginePackage")
            TextToSpeech(context, callback, enginePackage)
        }
    }

    private fun onTtsInit(status: Int, requestedEngine: String) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                AppLogger.w(TAG, "TTS language not available for ${Locale.getDefault()}")
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handleUtteranceDone(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleUtteranceDone(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleUtteranceDone(utteranceId)
                }
            })
            ttsReady = true
            AppLogger.i(TAG, "Text-to-speech ready (engine=${requestedEngine.ifBlank { "system default" }})")
            // Speak anything requested before init finished.
            synchronized(this) {
                pendingAnnounceRunnable?.let { run ->
                    pendingAnnounceRunnable = null
                    run()
                }
            }
        } else {
            AppLogger.e(TAG, "TTS engine failed to initialize (status=$status, engine=$requestedEngine)")
            if (requestedEngine.isNotBlank() && !engineFallbackNotified) {
                engineFallbackNotified = true
                AppLogger.w(TAG, "Falling back to the system default TTS engine")
                createTts("")
            }
        }
    }

    /** Switch engines at runtime (called when the user picks one in Settings). */
    fun applyEngine(enginePackage: String) {
        if (enginePackage == currentEnginePackage && ttsReady) return
        stopSpeaking()
        createTts(enginePackage)
    }

    /** The engine package currently in use ("" = system default). */
    fun currentEngine(): String = currentEnginePackage

    private fun handleUtteranceDone(utteranceId: String?) {
        if (utteranceId == null) return
        if (utteranceId.startsWith(ANNOUNCE_PREFIX)) {
            synchronized(this) {
                if (utteranceId == pendingAnnounceUtterance) {
                    pendingAnnounceUtterance = null
                    val run = pendingAnnounceRunnable
                    pendingAnnounceRunnable = null
                    run?.invoke()
                }
            }
            return
        }
        // End of the current generation's chunk stream -> not speaking anymore.
        if (utteranceId.startsWith("voxremote:")) {
            val parts = utteranceId.split(":")
            if (parts.size >= 3) {
                val utteranceGen = parts[1].toIntOrNull() ?: return
                if (utteranceGen == generation.get() && parts[2] == LAST_CHUNK_MARKER) {
                    _speaking.value = false
                }
            }
        }
    }

    // ============ Reply speech ============

    /** True while a reply (or cue) is being spoken. */
    val isSpeaking: Boolean get() = _speaking.value

    /** Stop any current speech and skip whatever chunks are still queued. */
    fun stopSpeaking() {
        generation.incrementAndGet()
        _speaking.value = false
        tts?.stop()
    }

    /** Speak raw assistant text (markdown is cleaned for the ear). */
    fun speak(rawText: String) {
        val cleaned = plainTextForSpeech(rawText)
        if (cleaned.isBlank()) return
        speakCleaned(cleaned)
    }

    /** Speak the last remembered assistant reply, or a short cue if none. */
    fun speakLastReply() {
        val stored = prefs.getString(KEY_LAST_REPLY, null)
        if (stored.isNullOrBlank()) {
            speakCleaned(context.getString(R.string.voice_no_reply))
            return
        }
        speak(stored)
    }

    private fun speakCleaned(cleaned: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            AppLogger.w(TAG, "TTS not ready yet; speech dropped")
            return
        }
        stopSpeaking()
        val gen = generation.incrementAndGet()
        val chunks = splitForSpeech(cleaned, MAX_TTS_CHUNK)
        _speaking.value = true
        synchronized(this) {
            chunks.forEachIndexed { index, chunk ->
                val marker = if (index == chunks.lastIndex) LAST_CHUNK_MARKER else "$index"
                val id = "voxremote:$gen:$marker"
                val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                engine.speak(chunk, mode, null, id)
            }
        }
        if (chunks.isEmpty()) _speaking.value = false
    }

    /**
     * Speak a short cue (e.g. "Listening") before the mic opens, then run
     * [onDone]. When the announce setting is off, [onDone] runs immediately.
     * If the user stops speech while the cue plays, [onDone] is skipped so
     * a cancelled mic start does not race ahead.
     */
    fun announceListening(onDone: () -> Unit) {
        scope.launch {
            var delivered = false
            fun finish() {
                if (!delivered) {
                    delivered = true
                    onDone()
                }
            }
            try {
                val enabled = settingsRepository.voiceAnnounceListening.first()
                if (!enabled || !ttsReady) {
                    finish()
                    return@launch
                }
                val engine = tts ?: run { finish(); return@launch }
                stopSpeaking()
                val gen = generation.get()
                val utteranceId = ANNOUNCE_PREFIX + gen
                synchronized(this@VoiceController) {
                    pendingAnnounceUtterance = utteranceId
                    pendingAnnounceRunnable = {
                        if (generation.get() == gen) finish()
                    }
                }
                engine.speak(context.getString(R.string.voice_listening), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                // Safety net: if onDone never fires (engine stall), start anyway.
                kotlinx.coroutines.delay(1_500)
                synchronized(this@VoiceController) {
                    if (pendingAnnounceUtterance == utteranceId) {
                        pendingAnnounceUtterance = null
                        val run = pendingAnnounceRunnable
                        pendingAnnounceRunnable = null
                        run?.invoke()
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Announce cue failed; starting dictation anyway", e)
            } finally {
                // Never strand the caller: the mic must always open.
                finish()
            }
        }
    }

    // ============ Remembered state ============

    /** Persist the newest assistant reply for the notification Read action. */
    fun saveLastReply(text: String) {
        if (text.isBlank()) return
        prefs.edit().putString(KEY_LAST_REPLY, text).apply()
    }

    /** Whether replies should be spoken automatically when a run finishes. */
    suspend fun shouldReadReplies(): Boolean = settingsRepository.voiceReadReplies.first()

    /** Remember the chat the notification "Listen" action should open. */
    fun rememberSession(serverId: String, sessionId: String) {
        if (serverId.isBlank() || sessionId.isBlank()) return
        prefs.edit()
            .putString(KEY_LAST_SERVER_ID, serverId)
            .putString(KEY_LAST_SESSION_ID, sessionId)
            .apply()
    }

    data class LastSession(val serverId: String, val sessionId: String)

    fun lastSession(): LastSession? {
        val serverId = prefs.getString(KEY_LAST_SERVER_ID, null)
        val sessionId = prefs.getString(KEY_LAST_SESSION_ID, null)
        return if (!serverId.isNullOrBlank() && !sessionId.isNullOrBlank()) {
            LastSession(serverId, sessionId)
        } else {
            null
        }
    }

    // ============ Speech text cleanup ============

    /**
     * Turn markdown-heavy assistant output into something that sounds right
     * when spoken: code blocks keep their words but lose their symbols,
     * links collapse to their text, markers disappear, whitespace collapses.
     */
    fun plainTextForSpeech(raw: String): String {
        var text = raw

        // Fenced code blocks: keep the words, strip the symbol noise.
        text = CODE_FENCE.replace(text) { match ->
            val body = match.groupValues[1]
            " " + sanitizeCode(body) + " "
        }
        // Unclosed fence at the end of a truncated reply.
        text = Regex("(?s)```.*").replace(text, " ")

        // Images first, then links: keep the label text.
        text = Regex("!\\[[^\\]]*\\]\\([^)]*\\)").replace(text, " ")
        text = Regex("\\[([^\\]]+)\\]\\([^)]*\\)").replace(text) { it.groupValues[1] }

        // Headings, emphasis, strikethrough, inline code.
        text = Regex("(?m)^\\s*#{1,6}\\s*").replace(text, "")
        text = Regex("\\*\\*|__|~~|`").replace(text, "")
        text = Regex("(?m)^\\s*>\\s*").replace(text, "")
        // List bullets: announce as a list item pause.
        text = Regex("(?m)^\\s*[-+]\\s+").replace(text, ", ")
        // Table pipes read as garbage.
        text = text.replace('|', ' ')
        // Any leftover HTML tags.
        text = Regex("</?[a-zA-Z][^>]*>").replace(text, " ")

        text = WHITESPACE.replace(text, " ")
        return text.trim()
    }

    private fun sanitizeCode(body: String): String {
        var code = body
        // Line comments lose their hash/slash noise but keep the words.
        code = CODE_SYMBOLS.replace(code, " ")
        code = WHITESPACE.replace(code, " ")
        return code.trim()
    }

    /** Split long speech into sentence-friendly chunks the engine won't drop. */
    private fun splitForSpeech(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.length > maxChars) {
            val window = remaining.substring(0, maxChars)
            val breakAt = maxOf(
                window.lastIndexOf(". "),
                window.lastIndexOf("! "),
                window.lastIndexOf("? "),
                window.lastIndexOf("; "),
                window.lastIndexOf('\n'),
            )
            val cut = if (breakAt > maxChars / 2) breakAt + 1 else maxChars
            chunks += remaining.substring(0, cut).trim()
            remaining = remaining.substring(cut).trim()
        }
        if (remaining.isNotEmpty()) chunks += remaining
        return chunks
    }

    companion object {
        /** Marker in the utterance id of the final chunk of a generation. */
        private const val LAST_CHUNK_MARKER = "last"

        private val CODE_FENCE = Regex("(?s)```[^\\n]*\\n(.*?)```")
        private val CODE_SYMBOLS = Regex("[{}\\[\\]<>=~*#|$%^&+\\\\]")
        private val WHITESPACE = Regex("\\s{2,}")

        /**
         * Every TTS engine installed on this phone (Ivona, Google, Samsung…),
         * so the user can pick their favorite instead of the platform default.
         *
         * Uses TextToSpeech.getEngines() — the same API ARYA and
         * Voice-Only-Email use — because a raw PackageManager query misses
         * engines like Ivona. Safe to call from the main thread: it suspends
         * until the engine binds (5s cap) instead of blocking.
         */
        suspend fun queryEngines(context: Context): List<TtsEngineInfo> {
            val engines = kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                awaitEngineList(context)
            }
            return engines.orEmpty()
        }

        private suspend fun awaitEngineList(context: Context): List<TtsEngineInfo> =
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { status ->
                    val result = try {
                        val list = tts?.engines.orEmpty()
                        if (list.isNotEmpty()) {
                            list.map { info ->
                                TtsEngineInfo(
                                    label = info.label?.toString()?.ifBlank { info.name } ?: info.name,
                                    packageName = info.name,
                                )
                            }
                        } else {
                            // Fall back to at least the default engine (ARYA behavior).
                            val def = tts?.defaultEngine
                            if (def != null && status == TextToSpeech.SUCCESS) {
                                listOf(TtsEngineInfo(def.substringAfterLast('.'), def))
                            } else {
                                emptyList()
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Engine enumeration failed", e)
                        emptyList()
                    }
                    if (cont.isActive) cont.resume(result.sortedBy { it.label.lowercase() })
                }
                try {
                    tts = TextToSpeech(context, listener)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not create TTS for engine listing", e)
                    if (cont.isActive) cont.resume(emptyList())
                }
                cont.invokeOnCancellation { tts?.shutdown() }
            }
    }
}
