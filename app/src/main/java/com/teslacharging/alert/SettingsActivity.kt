package com.teslacharging.alert

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

        intent.getStringExtra(EXTRA_AUTH_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnFetchVehicles.setOnClickListener { fetchVehicles() }
        binding.btnTeslaLogin.setOnClickListener { startTeslaLogin() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.tvTokenHelp.setOnClickListener { showTokenHelp() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_AUTH_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        loadSettings()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadSettings() {
        binding.etClientId.setText(Prefs.getClientId(this))
        binding.etClientSecret.setText(Prefs.getClientSecret(this))
        binding.etVehicleId.setText(Prefs.getVehicleId(this))
        binding.etApiBaseUrl.setText(Prefs.getApiBaseUrl(this))
        binding.etCheckInterval.setText(Prefs.getCheckInterval(this).toString())
        binding.switchAlertComplete.isChecked = Prefs.isAlertOnComplete(this)
        binding.switchAlertStopped.isChecked = Prefs.isAlertOnStopped(this)
        binding.switchAlertNoPower.isChecked = Prefs.isAlertOnNoPower(this)
        binding.switchWakeVehicle.isChecked = Prefs.isWakeVehicle(this)
        updateAuthStatus()
    }

    private fun updateAuthStatus() {
        val hasSession = Prefs.hasOAuthSession(this)
        val expiresAt = Prefs.getAccessTokenExpiresAt(this)
        binding.tvAuthStatus.text = if (!hasSession) {
            "Not connected. Save your Tesla client ID and secret, then sign in."
        } else {
            val expiresText = if (expiresAt > 0) {
                " Access token expires at ${java.text.DateFormat.getDateTimeInstance().format(expiresAt * 1000)}."
            } else {
                ""
            }
            "Connected to Tesla.$expiresText"
        }
        binding.btnLogout.isEnabled = hasSession
    }

    private fun saveSettings() {
        val previousClientId = Prefs.getClientId(this)
        val previousClientSecret = Prefs.getClientSecret(this)
        val previousBaseUrl = Prefs.getApiBaseUrl(this)

        val clientId = binding.etClientId.text.toString().trim()
        val clientSecret = binding.etClientSecret.text.toString().trim()
        val vehicleId = binding.etVehicleId.text.toString().trim()
        val baseUrl = binding.etApiBaseUrl.text.toString().trim()
            .ifEmpty { Prefs.DEFAULT_API_BASE_URL }
        val interval = binding.etCheckInterval.text.toString().trim()
            .toIntOrNull()?.coerceIn(1, 60) ?: Prefs.DEFAULT_CHECK_INTERVAL

        Prefs.setClientId(this, clientId)
        Prefs.setClientSecret(this, clientSecret)
        Prefs.setVehicleId(this, vehicleId)
        Prefs.setApiBaseUrl(this, baseUrl)
        Prefs.setCheckInterval(this, interval)
        Prefs.setAlertOnComplete(this, binding.switchAlertComplete.isChecked)
        Prefs.setAlertOnStopped(this, binding.switchAlertStopped.isChecked)
        Prefs.setAlertOnNoPower(this, binding.switchAlertNoPower.isChecked)
        Prefs.setWakeVehicle(this, binding.switchWakeVehicle.isChecked)

        if ((previousClientId != clientId || previousClientSecret != clientSecret || previousBaseUrl != baseUrl) &&
            Prefs.hasOAuthSession(this)
        ) {
            Prefs.clearAuthSession(this)
            updateAuthStatus()
            Toast.makeText(
                this,
                "Tesla credentials changed. Sign in again to refresh your session.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (Prefs.isMonitoringEnabled(this)) {
            AlarmScheduler.scheduleNextCheck(this)
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun startTeslaLogin() {
        saveSettings()
        val clientId = binding.etClientId.text.toString().trim()
        val baseUrl = binding.etApiBaseUrl.text.toString().trim()
            .ifEmpty { Prefs.DEFAULT_API_BASE_URL }

        val loginIntent = runCatching {
            TeslaAuthManager.buildLoginIntent(this, clientId, baseUrl)
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: "Unable to start Tesla login.", Toast.LENGTH_LONG).show()
            return
        }

        startActivity(loginIntent)
    }

    private fun logout() {
        TeslaAuthManager.clearSession(this)
        updateAuthStatus()
        Toast.makeText(this, "Tesla session cleared", Toast.LENGTH_SHORT).show()
    }

    private fun fetchVehicles() {
        if (!Prefs.hasOAuthSession(this)) {
            Toast.makeText(this, "Sign in to Tesla first", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = binding.etApiBaseUrl.text.toString().trim()
            .ifEmpty { Prefs.DEFAULT_API_BASE_URL }

        binding.btnFetchVehicles.isEnabled = false
        binding.btnFetchVehicles.text = "Fetching…"

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { TeslaApiClient.getVehicles(this@SettingsActivity, baseUrl) }
            withContext(Dispatchers.Main) {
                binding.btnFetchVehicles.isEnabled = true
                binding.btnFetchVehicles.text = "Fetch My Vehicles"

                if (result.exceptionOrNull() is TeslaApiClient.UnauthorizedException) {
                    Toast.makeText(
                        this@SettingsActivity,
                        result.exceptionOrNull()?.message ?: "Tesla login expired. Sign in again.",
                        Toast.LENGTH_LONG
                    ).show()
                    updateAuthStatus()
                    return@withContext
                }

                val vehicles = result.getOrElse { emptyList() }
                when {
                    vehicles.isEmpty() ->
                        Toast.makeText(
                            this@SettingsActivity,
                            "No vehicles found. Check your Tesla app credentials and base URL.",
                            Toast.LENGTH_LONG
                        ).show()

                    vehicles.size == 1 -> {
                        binding.etVehicleId.setText(vehicles[0].id.toString())
                        Toast.makeText(
                            this@SettingsActivity,
                            "Auto-filled: ${vehicles[0].displayName} (${vehicles[0].vin})",
                            Toast.LENGTH_LONG
                        ).show()
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

    private fun showTokenHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Tesla OAuth setup")
            .setMessage(
                "1. Create or open your app at developer.tesla.com.\n" +
                "2. Copy its client ID and client secret into this screen.\n" +
                "3. Add this redirect URI to your Tesla app settings:\n" +
                "${TeslaAuthManager.redirectUri}\n\n" +
                "4. Save settings, then tap Sign In With Tesla.\n" +
                "5. After Tesla redirects back, use Fetch My Vehicles to choose the right vehicle.\n\n" +
                "This app requests offline access so it can refresh access tokens automatically."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        const val EXTRA_AUTH_MESSAGE = "auth_message"
    }
}
