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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// Vordergrunddienst für laufende Transkriptionen.
///
/// Zwei Aufgaben: Android beendet bzw. friert den Prozess nicht ein, solange
/// eine lange Datei läuft und man die App verlassen hat — und der Fortschritt
/// steht als Benachrichtigung in der Statusleiste. Ab Android 16 als
/// Live-Update (Chip in der Statusleiste, Sperrbildschirm), darunter als
/// normale Fortschrittsbenachrichtigung.
///
/// Die Arbeit selbst macht TranscriptionJobs; der Dienst spiegelt nur dessen
/// Zustand. Kurze Aufträge zeigen nichts an: Die Benachrichtigung ist
/// zurückgestellt, das System blendet sie erst nach etwa 10 s ein.
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TranscriptionJobs.cancel()
            // Kam der Knopfdruck erst nach dem Ende, läuft hier sonst ein
            // gestarteter Dienst ohne Aufgabe weiter.
            if (!observing) stopSelf(startId)
            return START_NOT_STICKY
        }
        lastStartId = startId
        // Nach startForegroundService muss startForeground in jedem Fall
        // folgen — auch wenn der Auftrag schon fertig ist.
        val running = TranscriptionJobs.running.value
        try {
            startInForeground(progressNotification(running ?: TranscriptionJobs.Running("Transkription", null, null)))
        } catch (e: Exception) {
            // Lieber ohne Statusleisten-Anzeige weiterrechnen als die App beenden.
            android.util.Log.e("TranscriptionService", "startForeground fehlgeschlagen", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!observing) {
            if (running == null) {
                stopSelfNow()
            } else {
                observing = true
                scope.launch { observe() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun observe() {
        val nm = NotificationManagerCompat.from(this)
        while (true) {
            val r = TranscriptionJobs.running.value ?: break
            if (nm.areNotificationsEnabled()) {
                runCatching { nm.notify(ID_PROGRESS, progressNotification(r)) }
            }
            // Häufigere Updates drosselt das System ohnehin.
            delay(500)
        }
        observing = false
        stopSelfNow()
    }

    /// stopSelf mit der letzten Start-ID: Kam inzwischen ein neuer Auftrag
    /// dazu, läuft der Dienst für ihn weiter.
    private fun stopSelfNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    /// Bewusst ohne ServiceCompat: androidx.core 1.17 maskiert den Typ auf die
    /// Werte von Android 14 und verschluckt dabei mediaProcessing (erst ab 15).
    /// Beim System kam dann „type none“ an — und das beendet die App.
    private fun startInForeground(notification: android.app.Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 ->
                startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 ->
                startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ID_PROGRESS, notification)
        }
    }

    /// Android 15+: Die Tageszeit für diese Dienstart ist aufgebraucht (6 h).
    /// Dann sofort beenden, sonst beendet das System die App.
    override fun onTimeout(startId: Int, fgsType: Int) {
        TranscriptionJobs.cancel()
        stopSelfNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(r: TranscriptionJobs.Running): android.app.Notification {
        ensureChannels(this)
        val percent = r.fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(r.title)
            .setContentText(r.detail ?: "Transkription läuft")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            // Android 16: als Live-Update in Statusleiste und Sperrbildschirm
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(percent?.let { "$it %" } ?: "…")
        if (!r.cancelling) {
            b.addAction(0, "Abbrechen", PendingIntent.getService(
                this, 1,
                Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            val style = NotificationCompat.ProgressStyle()
                .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100)))
                .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.ic_mic))
            if (percent == null) style.setProgressIndeterminate(true) else style.setProgress(percent)
            b.setStyle(style)
        } else {
            b.setProgress(100, percent ?: 0, percent == null)
        }
        return b.build()
    }

    companion object {
        private const val ACTION_CANCEL = "dev.whisper.transcribe.action.CANCEL_TRANSCRIPTION"
        private const val CHANNEL_PROGRESS = "transcription_progress"
        private const val CHANNEL_DONE = "transcription_done"
        private const val ID_PROGRESS = 1001
        private const val ID_DONE = 1002

        /// Beim Start eines Auftrags aufrufen, solange die App im Vordergrund
        /// ist — aus dem Hintergrund verbietet Android das Starten (ab 12).
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TranscriptionService::class.java))
            } catch (e: Exception) {
                // Ohne Dienst läuft der Auftrag trotzdem, nur ohne Schutz im Hintergrund.
                android.util.Log.w("TranscriptionService", "Vordergrunddienst nicht gestartet", e)
            }
        }

        /// Fertig, während die App nicht sichtbar ist: kurz Bescheid geben.
        fun finished(context: Context, outcome: TranscriptionJobs.Outcome) {
            if (AppVisibility.isVisible) return
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            ensureChannels(context)
            val text = outcome.text?.takeIf { it.isNotBlank() }
            val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(if (text != null) "Transkription fertig" else (outcome.message ?: "Transkription beendet"))
                .setContentText(text ?: "")
                .setStyle(text?.let { NotificationCompat.BigTextStyle().bigText(it) })
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            runCatching { nm.notify(ID_DONE, n) }
        }

        /// Beim Öffnen der App ist die Fertig-Meldung überflüssig.
        fun clearFinished(context: Context) = NotificationManagerCompat.from(context).cancel(ID_DONE)

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW))
            }
            if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }
}

/// Ob ein Fenster der Haupt-App sichtbar ist (MainActivity onStart/onStop).
object AppVisibility {
    @Volatile private var started = 0
    val isVisible: Boolean get() = started > 0
    fun onStart() { started++ }
    fun onStop() { started = (started - 1).coerceAtLeast(0) }
}
