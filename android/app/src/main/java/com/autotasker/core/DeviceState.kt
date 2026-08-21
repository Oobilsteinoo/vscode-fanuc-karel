package com.autotasker.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Lectures d'état ponctuelles. Tout est obtenu sans écouteur persistant :
 * la batterie via l'intent collant ACTION_BATTERY_CHANGED, le reste via les system services.
 * Coût : quelques microsecondes, zéro consommation de fond.
 */
object DeviceState {

    fun plugged(context: Context): Int {
        val intent = try {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (t: Throwable) {
            null
        } ?: return 0
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    }

    fun isWirelessCharging(context: Context): Boolean =
        plugged(context) == BatteryManager.BATTERY_PLUGGED_WIRELESS

    fun isCharging(context: Context): Boolean = plugged(context) != 0

    fun chargingLabel(context: Context): String = when (plugged(context)) {
        0 -> "sur batterie"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "charge sans fil"
        BatteryManager.BATTERY_PLUGGED_AC -> "charge secteur"
        BatteryManager.BATTERY_PLUGGED_USB -> "charge USB"
        else -> "en charge"
    }

    fun isScreenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * SSID courant, ou chaîne vide s'il n'est pas lisible.
     * Depuis Android 8.1 il faut la localisation pour lire le SSID : l'appli ne la demande pas,
     * donc ce champ reste souvent vide et les règles Wi-Fi se rabattent sur « connecté / pas connecté ».
     */
    @Suppress("DEPRECATION")
    fun wifiSsid(context: Context): String = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = wm?.connectionInfo?.ssid.orEmpty().trim('"')
        if (ssid.isBlank() || ssid.contains("unknown", ignoreCase = true) || ssid == "02:00:00:00:00:00") "" else ssid
    } catch (t: Throwable) {
        ""
    }

    fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
