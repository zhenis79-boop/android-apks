package com.genis.wavoicereader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isServiceEnabled(context)) {
            val serviceIntent = Intent(context, VoiceWatcherService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
