package com.teslacharging.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object AlarmScheduler {
    const val ACTION_CHECK_CHARGING = "com.teslacharging.alert.CHECK_CHARGING"
    private const val REQUEST_CODE = 42

    /**
     * Schedule the next charging check using setAlarmClock().
     * Note: On Android 14+ (API 34), this requires SCHEDULE_EXACT_ALARM permission.
     */
    fun scheduleNextCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Safety check for Android 12+ to avoid SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e("AlarmScheduler", "Cannot schedule exact alarm: permission missing")
                return
            }
        }

        val intervalMs = Prefs.getCheckInterval(context) * 60_000L
        val triggerAt = System.currentTimeMillis() + intervalMs

        // When user taps the clock icon in status bar, open the app
        val showIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmInfo = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
        alarmManager.setAlarmClock(alarmInfo, receiverPendingIntent(context))
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(receiverPendingIntent(context))
    }

    fun isScheduled(context: Context): Boolean =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ChargingCheckReceiver::class.java).setAction(ACTION_CHECK_CHARGING),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) != null

    private fun receiverPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, ChargingCheckReceiver::class.java).setAction(ACTION_CHECK_CHARGING),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
