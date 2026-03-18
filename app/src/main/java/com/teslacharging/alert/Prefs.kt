package com.teslacharging.alert

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val PREF_FILE = "tesla_prefs"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_VEHICLE_ID = "vehicle_id"
    private const val KEY_CHECK_INTERVAL = "check_interval_minutes"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_ALERT_ON_COMPLETE = "alert_on_complete"
    private const val KEY_ALERT_ON_STOPPED = "alert_on_stopped"
    private const val KEY_ALERT_ON_NO_POWER = "alert_on_no_power"
    private const val KEY_WAKE_VEHICLE = "wake_vehicle"

    const val DEFAULT_API_BASE_URL = "https://fleet-api.prd.na.vn.cloud.tesla.com"
    const val DEFAULT_CHECK_INTERVAL = 10

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiToken(context: Context): String =
        prefs(context).getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(context: Context, token: String) =
        prefs(context).edit().putString(KEY_API_TOKEN, token).apply()

    fun getVehicleId(context: Context): String =
        prefs(context).getString(KEY_VEHICLE_ID, "") ?: ""

    fun setVehicleId(context: Context, id: String) =
        prefs(context).edit().putString(KEY_VEHICLE_ID, id).apply()

    fun getCheckInterval(context: Context): Int =
        prefs(context).getInt(KEY_CHECK_INTERVAL, DEFAULT_CHECK_INTERVAL)

    fun setCheckInterval(context: Context, minutes: Int) =
        prefs(context).edit().putInt(KEY_CHECK_INTERVAL, minutes).apply()

    fun isMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITORING_ENABLED, false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL

    fun setApiBaseUrl(context: Context, url: String) =
        prefs(context).edit().putString(KEY_API_BASE_URL, url).apply()

    fun isAlertOnComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_ON_COMPLETE, true)

    fun setAlertOnComplete(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALERT_ON_COMPLETE, enabled).apply()

    fun isAlertOnStopped(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_ON_STOPPED, true)

    fun setAlertOnStopped(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALERT_ON_STOPPED, enabled).apply()

    fun isAlertOnNoPower(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALERT_ON_NO_POWER, true)

    fun setAlertOnNoPower(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALERT_ON_NO_POWER, enabled).apply()

    fun isWakeVehicle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_VEHICLE, false)

    fun setWakeVehicle(context: Context, wake: Boolean) =
        prefs(context).edit().putBoolean(KEY_WAKE_VEHICLE, wake).apply()
}
