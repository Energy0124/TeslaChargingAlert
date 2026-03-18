package com.teslacharging.alert

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.teslacharging.alert.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadSettings()

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnFetchVehicles.setOnClickListener { fetchVehicles() }
        binding.btnPasteToken.setOnClickListener { pasteFromClipboard() }
        binding.tvTokenHelp.setOnClickListener { showTokenHelp() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // -------------------------------------------------------------------------

    private fun loadSettings() {
        binding.etApiToken.setText(Prefs.getApiToken(this))
        binding.etVehicleId.setText(Prefs.getVehicleId(this))
        binding.etApiBaseUrl.setText(Prefs.getApiBaseUrl(this))
        binding.etCheckInterval.setText(Prefs.getCheckInterval(this).toString())
        binding.switchAlertComplete.isChecked = Prefs.isAlertOnComplete(this)
        binding.switchAlertStopped.isChecked = Prefs.isAlertOnStopped(this)
        binding.switchAlertNoPower.isChecked = Prefs.isAlertOnNoPower(this)
        binding.switchWakeVehicle.isChecked = Prefs.isWakeVehicle(this)
    }

    private fun saveSettings() {
        val token    = binding.etApiToken.text.toString().trim()
        val vehicleId = binding.etVehicleId.text.toString().trim()
        val baseUrl  = binding.etApiBaseUrl.text.toString().trim()
            .ifEmpty { Prefs.DEFAULT_API_BASE_URL }
        val interval = binding.etCheckInterval.text.toString().trim()
            .toIntOrNull()?.coerceIn(1, 60) ?: Prefs.DEFAULT_CHECK_INTERVAL

        Prefs.setApiToken(this, token)
        Prefs.setVehicleId(this, vehicleId)
        Prefs.setApiBaseUrl(this, baseUrl)
        Prefs.setCheckInterval(this, interval)
        Prefs.setAlertOnComplete(this, binding.switchAlertComplete.isChecked)
        Prefs.setAlertOnStopped(this, binding.switchAlertStopped.isChecked)
        Prefs.setAlertOnNoPower(this, binding.switchAlertNoPower.isChecked)
        Prefs.setWakeVehicle(this, binding.switchWakeVehicle.isChecked)

        // If monitoring is active and interval changed, reschedule
        if (Prefs.isMonitoringEnabled(this)) {
            AlarmScheduler.scheduleNextCheck(this)
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fetchVehicles() {
        val token = binding.etApiToken.text.toString().trim()
        if (token.isBlank()) {
            Toast.makeText(this, "Enter your API token first", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = binding.etApiBaseUrl.text.toString().trim()
            .ifEmpty { Prefs.DEFAULT_API_BASE_URL }

        binding.btnFetchVehicles.isEnabled = false
        binding.btnFetchVehicles.text = "Fetching…"

        lifecycleScope.launch(Dispatchers.IO) {
            val vehicles = TeslaApiClient.getVehicles(baseUrl, token)
            withContext(Dispatchers.Main) {
                binding.btnFetchVehicles.isEnabled = true
                binding.btnFetchVehicles.text = "Fetch My Vehicles"

                when {
                    vehicles.isEmpty() ->
                        Toast.makeText(this@SettingsActivity,
                            "No vehicles found. Check your token.", Toast.LENGTH_LONG).show()

                    vehicles.size == 1 -> {
                        binding.etVehicleId.setText(vehicles[0].id.toString())
                        Toast.makeText(this@SettingsActivity,
                            "Auto-filled: ${vehicles[0].displayName} (${vehicles[0].vin})",
                            Toast.LENGTH_LONG).show()
                    }

                    else -> {
                        val names = vehicles.map { "${it.displayName} – ${it.vin}\nID: ${it.id}" }
                            .toTypedArray()
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("Select Vehicle")
                            .setItems(names) { _, idx ->
                                binding.etVehicleId.setText(vehicles[idx].id.toString())
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun pasteFromClipboard() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.text?.toString()
        if (text != null) binding.etApiToken.setText(text)
        else Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
    }

    private fun showTokenHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("How to get your Tesla API token")
            .setMessage(
                "Option 1 – Tesla Fleet API (recommended):\n" +
                "1. Register at developer.tesla.com\n" +
                "2. Create an application and obtain OAuth credentials\n" +
                "3. Complete the OAuth flow to get a Bearer token\n\n" +
                "Option 2 – Third-party tools:\n" +
                "Search for 'tesla_auth' on GitHub or the 'Auth App for Tesla' " +
                "on the App Store / Play Store.\n\n" +
                "Paste the resulting Bearer token into the API Token field.\n\n" +
                "Base URL:\n" +
                "• Owner API (personal use): https://owner-api.teslamotors.com\n" +
                "• Fleet API (NA): https://fleet-api.prd.na.vn.cloud.tesla.com\n" +
                "• Fleet API (EU): https://fleet-api.prd.eu.vn.cloud.tesla.com"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
