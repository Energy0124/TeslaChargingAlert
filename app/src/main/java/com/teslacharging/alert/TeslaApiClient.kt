package com.teslacharging.alert

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object TeslaApiClient {
    private const val TAG = "TeslaApiClient"
    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 30_000

    data class ChargeState(
        val chargingState: String,   // "Charging", "Complete", "Disconnected", "Stopped", "NoPower"
        val batteryLevel: Int,
        val chargeLimitSoc: Int,
        val minutesToFullCharge: Int,
        val chargeRate: Double,
        val timeToFullCharge: Double
    )

    data class Vehicle(
        val id: Long,
        val vin: String,
        val displayName: String,
        val state: String            // "online", "asleep", "offline"
    )

    sealed class ApiResult {
        data class Success(val chargeState: ChargeState) : ApiResult()
        data class VehicleAsleep(val message: String) : ApiResult()
        data class Error(val message: String) : ApiResult()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun getVehicles(baseUrl: String, apiToken: String): List<Vehicle> {
        return try {
            val body = get("$baseUrl/api/1/vehicles", apiToken)
            val array = JSONObject(body).getJSONArray("response")
            (0 until array.length()).map { i ->
                val v = array.getJSONObject(i)
                Vehicle(
                    id = v.getLong("id"),
                    vin = v.optString("vin", ""),
                    displayName = v.optString("display_name", "Tesla"),
                    state = v.optString("state", "unknown")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getVehicles failed", e)
            emptyList()
        }
    }

    fun getChargeState(
        baseUrl: String,
        apiToken: String,
        vehicleId: String,
        wakeIfNeeded: Boolean
    ): ApiResult {
        return try {
            fetchChargeState(baseUrl, apiToken, vehicleId)
        } catch (e: VehicleAsleepException) {
            if (!wakeIfNeeded) return ApiResult.VehicleAsleep(e.message ?: "Vehicle is asleep")
            Log.d(TAG, "Vehicle asleep — sending wake_up")
            wakeVehicle(baseUrl, apiToken, vehicleId)
            Thread.sleep(15_000)
            try {
                fetchChargeState(baseUrl, apiToken, vehicleId)
            } catch (e2: Exception) {
                Log.e(TAG, "Still offline after wake", e2)
                ApiResult.VehicleAsleep("Vehicle did not wake in time")
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChargeState failed", e)
            ApiResult.Error(e.message ?: "Unknown error")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun fetchChargeState(baseUrl: String, apiToken: String, vehicleId: String): ApiResult {
        val body = get("$baseUrl/api/1/vehicles/$vehicleId/vehicle_data?endpoints=charge_state", apiToken)
        return try {
            val cs = JSONObject(body)
                .getJSONObject("response")
                .getJSONObject("charge_state")
            ApiResult.Success(
                ChargeState(
                    chargingState = cs.optString("charging_state", "Unknown"),
                    batteryLevel = cs.optInt("battery_level", 0),
                    chargeLimitSoc = cs.optInt("charge_limit_soc", 100),
                    minutesToFullCharge = cs.optInt("minutes_to_full_charge", 0),
                    chargeRate = cs.optDouble("charge_rate", 0.0),
                    timeToFullCharge = cs.optDouble("time_to_full_charge", 0.0)
                )
            )
        } catch (e: Exception) {
            ApiResult.Error("Parse error: ${e.message}")
        }
    }

    private fun wakeVehicle(baseUrl: String, apiToken: String, vehicleId: String) {
        try {
            post("$baseUrl/api/1/vehicles/$vehicleId/wake_up", apiToken)
        } catch (e: Exception) {
            Log.w(TAG, "wake_up request failed (may still wake)", e)
        }
    }

    private fun get(urlString: String, apiToken: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        return conn.use { it.readResponse() }
    }

    private fun post(urlString: String, apiToken: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiToken")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            outputStream.write("{}".toByteArray())
        }
        return conn.use { it.readResponse() }
    }

    private fun HttpURLConnection.readResponse(): String {
        return try {
            when (responseCode) {
                200 -> inputStream.bufferedReader().readText()
                408 -> throw VehicleAsleepException("Vehicle is asleep (408)")
                else -> {
                    val error = errorStream?.bufferedReader()?.readText() ?: ""
                    if (error.contains("vehicle unavailable", ignoreCase = true) ||
                        error.contains("asleep", ignoreCase = true)
                    ) {
                        throw VehicleAsleepException("Vehicle asleep: $error")
                    }
                    throw IOException("HTTP $responseCode: $error")
                }
            }
        } finally {
            disconnect()
        }
    }

    class VehicleAsleepException(message: String) : Exception(message)
}
