package com.teslacharging.alert

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Short-lived foreground service that checks the Tesla charge state,
 * posts an alarm-style alert if needed, then schedules the next alarm.
 *
 * Running as a foreground service ensures Android cannot kill it mid-request.
 */
class ChargingCheckService : Service() {
    private val TAG = "ChargingCheckService"
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() within 5 seconds
        val notification = NotificationHelper.showStatusNotification(
            this,
            "Tesla Charging Alert",
            "Checking charging status…"
        )
        startForeground(NotificationHelper.ID_STATUS, notification)

        scope.launch {
            try {
                runCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during check", e)
            } finally {
                // Schedule next alarm then stop this service
                if (Prefs.isMonitoringEnabled(this@ChargingCheckService)) {
                    AlarmScheduler.scheduleNextCheck(this@ChargingCheckService)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun runCheck() {
        val token = Prefs.getApiToken(this)
        val vehicleId = Prefs.getVehicleId(this)
        val baseUrl = Prefs.getApiBaseUrl(this)
        val wakeVehicle = Prefs.isWakeVehicle(this)

        if (token.isBlank() || vehicleId.isBlank()) {
            Log.w(TAG, "API token or vehicle ID not configured – skipping check")
            return
        }

        Log.d(TAG, "Checking charge state for vehicle $vehicleId")

        when (val result = TeslaApiClient.getChargeState(baseUrl, token, vehicleId, wakeVehicle)) {
            is TeslaApiClient.ApiResult.VehicleAsleep -> {
                Log.d(TAG, "Vehicle is asleep – no idle fee risk, skipping alert")
            }
            is TeslaApiClient.ApiResult.Error -> {
                Log.e(TAG, "API error: ${result.message}")
            }
            is TeslaApiClient.ApiResult.Success -> {
                val cs = result.chargeState
                Log.d(TAG, "State=${cs.chargingState}  Battery=${cs.batteryLevel}%")
                maybeAlert(cs)
            }
        }
    }

    private fun maybeAlert(cs: TeslaApiClient.ChargeState) {
        val (shouldAlert, title, message) = when (cs.chargingState) {
            "Complete" -> if (Prefs.isAlertOnComplete(this)) Triple(
                true,
                "Tesla Fully Charged – Unplug Now!",
                "Battery at ${cs.batteryLevel}% (limit ${cs.chargeLimitSoc}%). " +
                        "Unplug to avoid idle fees at the Supercharger."
            ) else Triple(false, "", "")

            "Stopped" -> if (Prefs.isAlertOnStopped(this)) Triple(
                true,
                "Tesla Plugged In But Not Charging!",
                "Battery at ${cs.batteryLevel}%. Charging has stopped. " +
                        "Check your Tesla to avoid idle fees."
            ) else Triple(false, "", "")

            "NoPower" -> if (Prefs.isAlertOnNoPower(this)) Triple(
                true,
                "No Power to Tesla!",
                "Tesla is plugged in but receiving no power. " +
                        "Check the charger or cable."
            ) else Triple(false, "", "")

            else -> Triple(false, "", "")
        }

        if (shouldAlert) {
            Log.d(TAG, "Firing alert: $title")
            NotificationHelper.showChargingAlert(this, title, message)
        }
    }
}
