package com.arb.datetimepill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** A tile that jumps to the system navigation settings page. */
class NavTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = "Navigation"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        // Likely addresses for the page; the first one this phone recognises is used
        val candidates = listOf(
            Intent("android.settings.SYSTEM_NAVIGATION_SETTINGS"),
            Intent("android.settings.GESTURE_NAVIGATION_SETTINGS"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$SystemNavigationGestureSettingsActivity"
            ),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$GestureNavigationSettingsActivity"
            )
        )

        var chosen: Intent? = null
        for (candidate in candidates) {
            if (packageManager.resolveActivity(candidate, 0) != null) {
                chosen = candidate
                break
            }
        }

        if (chosen == null) {
            // Fallback: open Settings and say where to go
            chosen = Intent(Settings.ACTION_SETTINGS)
            Toast.makeText(
                this,
                "Go to System, then Gestures, then System navigation",
                Toast.LENGTH_LONG
            ).show()
        }

        chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, chosen, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(chosen)
        }
    }
}
