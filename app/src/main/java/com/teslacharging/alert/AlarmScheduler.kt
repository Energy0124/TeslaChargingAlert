package com.teslacharging.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object AlarmScheduler {
    const val ACTION_CHECK_CHARGING = "com.teslacharging.alert.CHECK_CHARGING"
    private const val REQUEST_CODE = 42

    /**
     * Schedule the next charging check using setAlarmClock(), which:
     *  - Fires even during Doze mode
     *  - Bypasses battery optimizations
     *  - Shows the alarm clock icon in the status bar
     *  - Does NOT require SCHEDULE_EXACT_ALARM permission (it IS an alarm clock)
     */
    fun scheduleNextCheck(context: Context) {
        val intervalMs = Prefs.getCheckInterval(context) * 60_000L
        val triggerAt = System.currentTimeMillis() + intervalMs

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
