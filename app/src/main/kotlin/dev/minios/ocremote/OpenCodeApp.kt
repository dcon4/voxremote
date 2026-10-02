package dev.minios.ocremote

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.logging.AppLogger
import dev.minios.ocremote.voice.VoiceControllerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OC Remote Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {
    @Inject lateinit var diagnosticLogRepository: DiagnosticLogRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(diagnosticLogRepository)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLogger.recordCrash(thread, error)
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
        // The persistent voice notification must come up on every launch.
        // It previously started only when the Settings toggle was flipped,
        // so after any app restart RemoteFix had no notification to see.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            runCatching {
                if (settingsRepository.voiceNotification.first()) {
                    VoiceControllerService.start(this@OpenCodeApp)
                }
            }.onFailure {
                AppLogger.w("OpenCodeApp", "Voice notification could not start", it)
            }
        }
    }
}
