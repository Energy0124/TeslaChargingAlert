package com.teslacharging.alert

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OAuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callbackUri = intent?.data
        if (callbackUri == null) {
            finishToSettings("Tesla sign-in callback was empty.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val message = runCatching {
                TeslaAuthManager.completeLogin(this@OAuthCallbackActivity, callbackUri)
                "Tesla login connected successfully."
            }.getOrElse { error ->
                error.message ?: "Tesla login failed."
            }

            withContext(Dispatchers.Main) {
                finishToSettings(message)
            }
        }
    }

    private fun finishToSettings(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_AUTH_MESSAGE, message)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}
