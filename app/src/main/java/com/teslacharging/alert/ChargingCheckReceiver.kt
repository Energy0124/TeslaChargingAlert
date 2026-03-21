package com.teslacharging.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Triggered by AlarmManager every N minutes.
 * Starts a foreground service to do the actual network call so the
 * process cannot be killed mid-request.
 */
class ChargingCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_CHECK_CHARGING) return
        if (!Prefs.isMonitoringEnabled(context)) return

        Log.d("ChargingCheckReceiver", "Alarm fired – starting check service")
        val serviceIntent = Intent(context, ChargingCheckService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
