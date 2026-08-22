package dev.whisper.transcribe

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/// Schnelleinstellungs-Kachel: ein Tipp von überall öffnet das Diktat-Overlay.
class DictationTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, DictationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val modelConfigured = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .contains("model_file")
        qsTile?.let {
            it.state = if (modelConfigured)
                android.service.quicksettings.Tile.STATE_INACTIVE
            else
                android.service.quicksettings.Tile.STATE_UNAVAILABLE
            it.updateTile()
        }
    }
}
