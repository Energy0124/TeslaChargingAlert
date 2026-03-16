package com.teslacharging.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Re-schedules the alarm after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        if (Prefs.isMonitoringEnabled(context)) {
            Log.d("BootReceiver", "Rescheduling alarm after boot")
            AlarmScheduler.scheduleNextCheck(context)
        }
    }
}
