package dev.whisper.transcribe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Vordergrunddienst für den Meeting-Modus. Ohne ihn schaltet Android das
/// Mikrofon stumm, sobald der Bildschirm ausgeht, und beendet lange
/// Auswertungen im Hintergrund. Spiegelt nur MeetingSession; die Arbeit
/// passiert dort.
class MeetingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wake: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FINISH -> { MeetingSession.finish(this); return START_NOT_STICKY }
            ACTION_PAUSE -> { MeetingSession.pause(); return START_NOT_STICKY }
        }
        val s = MeetingSession.state.value
        try {
            startInForeground(notification(s), recording = s.phase == MeetingSession.Phase.RECORDING || s.phase == MeetingSession.Phase.PAUSED)
        } catch (e: Exception) {
            android.util.Log.e("MeetingService", "startForeground fehlgeschlagen", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) {
            // Auswertung langer Meetings: CPU wach halten, Bildschirm darf aus
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whisper:meeting").apply { acquire(6 * 60 * 60 * 1000L) }
        }
        if (!observing) {
            observing = true
            scope.launch {
                var lastPhase = s.phase
                while (MeetingSession.isActive) {
                    val st = MeetingSession.state.value
                    // Wechsel Aufnahme → Auswertung: Diensttyp anpassen
                    if (st.phase != lastPhase) {
                        runCatching { startInForeground(notification(st), recording = st.phase != MeetingSession.Phase.PROCESSING) }
                        lastPhase = st.phase
                    }
                    val nm = NotificationManagerCompat.from(this@MeetingService)
                    if (nm.areNotificationsEnabled()) runCatching { nm.notify(ID, notification(st)) }
                    delay(1000)
                }
                observing = false
                stopNow()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: android.app.Notification, recording: Boolean) {
        when {
            recording && Build.VERSION.SDK_INT >= 30 ->
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            !recording && Build.VERSION.SDK_INT >= 35 ->
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            !recording && Build.VERSION.SDK_INT >= 29 ->
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ID, n)
        }
    }

    private fun stopNow() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (MeetingSession.state.value.phase == MeetingSession.Phase.PROCESSING) MeetingSession.cancelProcessing()
        else MeetingSession.finish(this)
        stopNow()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: MeetingSession.State): android.app.Notification {
        ensureChannel(this)
        val title = when (s.phase) {
            MeetingSession.Phase.RECORDING -> "Meeting · Aufnahme läuft"
            MeetingSession.Phase.PAUSED -> "Meeting · pausiert"
            MeetingSession.Phase.PROCESSING -> "Meeting wird ausgewertet"
            else -> "Meeting"
        }
        val text = when (s.phase) {
            MeetingSession.Phase.RECORDING, MeetingSession.Phase.PAUSED -> formatClock(s.seconds)
            MeetingSession.Phase.PROCESSING -> s.detail
            else -> ""
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_MEETING)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (s.phase == MeetingSession.Phase.RECORDING) {
            b.addAction(0, "Pause", PendingIntent.getService(this, 2,
                Intent(this, MeetingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE))
            b.addAction(0, "Beenden & auswerten", PendingIntent.getService(this, 3,
                Intent(this, MeetingService::class.java).setAction(ACTION_FINISH), PendingIntent.FLAG_IMMUTABLE))
        }
        if (s.phase == MeetingSession.Phase.PROCESSING) {
            val p = s.progress
            b.setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
        }
        return b.build()
    }

    companion object {
        const val ACTION_OPEN_MEETING = "dev.whisper.transcribe.action.OPEN_MEETING"
        private const val ACTION_FINISH = "dev.whisper.transcribe.action.MEETING_FINISH"
        private const val ACTION_PAUSE = "dev.whisper.transcribe.action.MEETING_PAUSE"
        private const val CHANNEL = "meeting"
        private const val ID = 1003

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MeetingService::class.java))
            } catch (e: Exception) {
                android.util.Log.w("MeetingService", "Vordergrunddienst nicht gestartet", e)
            }
        }

        fun stop(context: Context) {
            // Der Dienst beendet sich selbst, sobald die Sitzung nicht mehr aktiv ist.
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_meeting),
                    NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
