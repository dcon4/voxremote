package dev.minios.ocremote.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "VoiceControllerService"

/**
 * Persistent voice controller notification, modeled on ARYA's background
 * service so RemoteFix can drive it with headset buttons.
 *
 * Notification action order is a public contract — RemoteFix maps
 * play/pause/next/previous to action indices:
 *   0 Listen, 1 Stop, 2 Read reply, 3 Auto-read toggle.
 */
@AndroidEntryPoint
class VoiceControllerService : Service() {

    @Inject lateinit var voiceController: VoiceController
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null
    private var autoReadOn = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        // startForeground must run synchronously within 5s of a start call;
        // the auto-read label is refreshed as soon as the setting loads.
        startForeground(NOTIFICATION_ID, buildNotification())
        serviceScope.launch {
            autoReadOn = settingsRepository.voiceReadReplies.first()
            refreshNotification()
        }
        instance = this
        AppLogger.i(TAG, "Voice controller service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> openAppForDictation()
            ACTION_STOP -> voiceController.stopSpeaking()
            ACTION_READ -> voiceController.speakLastReply()
            ACTION_TOGGLE_AUTOREAD -> serviceScope.launch {
                val newValue = !settingsRepository.voiceReadReplies.first()
                settingsRepository.setVoiceReadReplies(newValue)
                autoReadOn = newValue
                refreshNotification()
            }
            else -> {
                // START_STICKY restart (null intent) or unknown action.
                serviceScope.launch {
                    autoReadOn = settingsRepository.voiceReadReplies.first()
                    refreshNotification()
                }
            }
        }
        return START_STICKY
    }

    /** Jump into the last opened chat and start dictating there. */
    private fun openAppForDictation() {
        val launch = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_MIC
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not open app for dictation", e)
        }
    }

    private fun refreshNotification() {
        try {
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not refresh voice notification", e)
        }
    }

    // ============ Notification ============

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_notification_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_notification_text)
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, VoiceControllerService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val autoReadLabel = getString(
            if (autoReadOn) R.string.voice_notification_auto_on
            else R.string.voice_notification_auto_off
        )

        val style = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(
                getString(R.string.voice_notification_text) + " · " + autoReadLabel
            )
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(style)
            // Action order is the RemoteFix mapping contract — do not reorder.
            .addAction(
                R.drawable.ic_stat_mic,
                getString(R.string.voice_action_listen),
                actionPendingIntent(ACTION_LISTEN, 10)
            )
            .addAction(
                R.drawable.ic_stat_stop,
                getString(R.string.voice_action_stop),
                actionPendingIntent(ACTION_STOP, 11)
            )
            .addAction(
                R.drawable.ic_stat_read,
                getString(R.string.voice_action_read),
                actionPendingIntent(ACTION_READ, 12)
            )
            .addAction(
                R.drawable.ic_stat_autoread,
                getString(R.string.voice_action_autoread),
                actionPendingIntent(ACTION_TOGGLE_AUTOREAD, 13)
            )
            .build()
    }

    // ============ Direct Bluetooth control (ARYA-style) ============

    private fun setupMediaSession() {
        val session = MediaSession(this, "voxremote_voice_controller")
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        -> {
                            openAppForDictation()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        -> {
                            voiceController.stopSpeaking()
                            return true
                        }
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }

            override fun onPlay() {
                openAppForDictation()
            }

            override fun onPause() {
                voiceController.stopSpeaking()
            }

            override fun onStop() {
                voiceController.stopSpeaking()
            }
        })
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_NONE, 0, 0f)
                .build()
        )
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.isActive = true
        mediaSession = session
    }

    override fun onDestroy() {
        instance = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        AppLogger.i(TAG, "Voice controller service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voxremote_voice"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_LISTEN = "com.dcon4.voxremote.action.LISTEN"
        const val ACTION_STOP = "com.dcon4.voxremote.action.STOP_SPEECH"
        const val ACTION_READ = "com.dcon4.voxremote.action.READ_REPLY"
        const val ACTION_TOGGLE_AUTOREAD = "com.dcon4.voxremote.action.TOGGLE_AUTOREAD"
        const val ACTION_STOP_SERVICE = "com.dcon4.voxremote.action.STOP_SERVICE"

        @Volatile
        private var instance: VoiceControllerService? = null

        fun start(context: Context) {
            val intent = Intent(context, VoiceControllerService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Background FGS start restrictions: retried on next app open.
                AppLogger.w(TAG, "Could not start voice service from background", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceControllerService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                instance?.stopSelf()
                AppLogger.w(TAG, "Could not stop voice service cleanly", e)
            }
        }

        fun isRunning(): Boolean = instance != null
    }
}
