package com.teslacharging.alert

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.teslacharging.alert.TeslaApiClient.ApiResult
import com.teslacharging.alert.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val NOTIF_PERM_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        NotificationHelper.createChannels(this)
        requestNotificationPermission()

        binding.btnToggleMonitoring.setOnClickListener { toggleMonitoring() }
        binding.btnCheckNow.setOnClickListener { checkNow() }
        binding.btnDndPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        binding.btnAlarmPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------------

    private fun toggleMonitoring() {
        if (!Prefs.isMonitoringEnabled(this)) {
            if (!Prefs.hasOAuthSession(this) || Prefs.getVehicleId(this).isBlank()) {
                Snackbar.make(
                    binding.root,
                    "Connect your Tesla account and choose a Vehicle ID in Settings first.",
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
                return
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Snackbar.make(
                        binding.root,
                        "Exact Alarm permission is required for monitoring.",
                        Snackbar.LENGTH_LONG
                    ).setAction("Grant") {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }.show()
                    return
                }
            }

            Prefs.setMonitoringEnabled(this, true)
            AlarmScheduler.scheduleNextCheck(this)
        } else {
            Prefs.setMonitoringEnabled(this, false)
            AlarmScheduler.cancel(this)
        }
        updateUi()
    }

    private fun checkNow() {
        if (!Prefs.hasOAuthSession(this) || Prefs.getVehicleId(this).isBlank()) {
            Snackbar.make(
                binding.root,
                "Connect your Tesla account and Vehicle ID in Settings.",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        binding.tvStatus.text = "Checking…"
        binding.tvChargingState.text = "State: —"
        binding.tvBatteryLevel.text = "Battery: —"
        binding.btnCheckNow.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val result = TeslaApiClient.getChargeState(
                this@MainActivity,
                Prefs.getApiBaseUrl(this@MainActivity),
                Prefs.getVehicleId(this@MainActivity),
                Prefs.isWakeVehicle(this@MainActivity)
            )
            withContext(Dispatchers.Main) {
                binding.btnCheckNow.isEnabled = true
                applyResult(result)
            }
        }
    }

    private fun applyResult(result: ApiResult) {
        when (result) {
            is ApiResult.VehicleAsleep -> {
                binding.tvChargingState.text = "State: Sleeping"
                binding.tvBatteryLevel.text = "Battery: —"
                binding.tvStatus.text = "Vehicle is asleep – no idle fee risk"
            }
            is ApiResult.Error -> {
                binding.tvChargingState.text = "State: Error"
                binding.tvBatteryLevel.text = "Battery: —"
                binding.tvStatus.text = "Error: ${result.message}"
            }
            is ApiResult.Success -> {
                val cs = result.chargeState
                binding.tvChargingState.text = "State: ${cs.chargingState}"
                binding.tvBatteryLevel.text = "Battery: ${cs.batteryLevel}% / limit ${cs.chargeLimitSoc}%"
                binding.tvStatus.text = when (cs.chargingState) {
                    "Charging" -> {
                        val kw = if (cs.chargeRate > 0) " @ %.1f kW".format(cs.chargeRate) else ""
                        val eta = if (cs.minutesToFullCharge > 0) ", ${cs.minutesToFullCharge} min to full" else ""
                        "Charging$kw$eta"
                    }
                    "Complete" -> "⚠ Fully charged – unplug to avoid idle fees!"
                    "Stopped"  -> "⚠ Plugged in but not charging!"
                    "Disconnected" -> "Not plugged in"
                    "NoPower"  -> "⚠ No power at charger!"
                    else       -> cs.chargingState
                }
            }
        }
    }

    private fun updateUi() {
        val enabled = Prefs.isMonitoringEnabled(this)
        val interval = Prefs.getCheckInterval(this)

        binding.btnToggleMonitoring.text = if (enabled) "Stop Monitoring" else "Start Monitoring"
        binding.tvMonitoringStatus.text =
            if (enabled) "Active – checks every $interval min" else "Inactive"

        // DND Permission
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasDnd = nm.isNotificationPolicyAccessGranted
        binding.btnDndPermission.visibility = if (hasDnd) View.GONE else View.VISIBLE
        binding.tvDndStatus.text =
            if (hasDnd) "DND bypass: Enabled" else "DND bypass: Not granted (tap below to allow)"
        binding.tvDndStatus.setTextColor(
            if (hasDnd) getColor(R.color.status_ok) else getColor(R.color.status_warn)
        )

        // Exact Alarm Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val hasAlarmPerm = alarmManager.canScheduleExactAlarms()
            binding.cardAlarmPermission.visibility = if (hasAlarmPerm) View.GONE else View.VISIBLE
            binding.tvAlarmStatus.text = if (hasAlarmPerm) "Exact alarms: Granted" else "Exact alarms: Not granted (required for Android 12+)"
            binding.tvAlarmStatus.setTextColor(
                if (hasAlarmPerm) getColor(R.color.status_ok) else getColor(R.color.status_warn)
            )
        } else {
            binding.cardAlarmPermission.visibility = View.GONE
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIF_PERM_CODE
            )
        }
    }
}
