package com.teslacharging.alert

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
            
            // Check for exact alarm permission on Android 12+ to avoid crash
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.e("BootReceiver", "Cannot reschedule: SCHEDULE_EXACT_ALARM permission not granted")
                    // We might want to notify the user or use an inexact alarm here
                    return
                }
            }

            AlarmScheduler.scheduleNextCheck(context)
        }
    }
}
