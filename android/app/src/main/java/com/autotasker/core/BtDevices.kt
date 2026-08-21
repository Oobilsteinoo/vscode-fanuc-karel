package com.autotasker.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.autotasker.data.BtDevice
import com.autotasker.data.Store
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Suivi des appareils Bluetooth connectés.
 *
 * L'état est tenu à jour par les broadcasts ACL_CONNECTED / ACL_DISCONNECTED (gratuits, envoyés
 * par l'OS), et resynchronisé via les proxys de profil quand l'appli s'ouvre ou au réveil
 * périodique — jamais par sondage.
 */
object BtDevices {

    private const val KEY_CONNECTED = "bt_connected"

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun connectedAddresses(): Set<String> = Store.getStringSet(KEY_CONNECTED)

    fun markConnected(address: String) {
        Store.putStringSet(KEY_CONNECTED, connectedAddresses() + address.uppercase())
    }

    fun markDisconnected(address: String) {
        Store.putStringSet(KEY_CONNECTED, connectedAddresses() - address.uppercase())
    }

    fun clearConnected() {
        Store.putStringSet(KEY_CONNECTED, emptySet())
    }

    fun isConnected(address: String): Boolean = connectedAddresses().contains(address.uppercase())

    /** Un appareil de la liste « voiture » est-il connecté ? */
    fun anyCarConnected(carDevices: List<BtDevice>): Boolean {
        if (carDevices.isEmpty()) return false
        val connected = connectedAddresses()
        return carDevices.any { connected.contains(it.address.uppercase()) }
    }

    fun paired(context: Context): List<BtDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { BtDevice(it.address.uppercase(), nameOf(it)) }
                .sortedBy { it.name.lowercase() }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun nameOf(device: BluetoothDevice): String = try {
        device.name ?: device.address
    } catch (t: Throwable) {
        device.address
    }

    /** Resynchronise la liste des appareils connectés depuis les profils audio (mains-libres / A2DP). */
    suspend fun refreshFromProfiles(context: Context) {
        if (!hasPermission(context)) return
        val adapter = adapter(context) ?: return
        if (!adapter.isEnabled) return
        val devices = withTimeoutOrNull(5000L) {
            connectedFor(context, adapter, BluetoothProfile.HEADSET) +
                connectedFor(context, adapter, BluetoothProfile.A2DP)
        } ?: return
        Store.putStringSet(KEY_CONNECTED, devices.map { it.address.uppercase() }.toSet())
    }

    private suspend fun connectedFor(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int
    ): List<BluetoothDevice> = suspendCancellableCoroutine { cont ->
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val devices = try {
                    proxy.connectedDevices.orEmpty()
                } catch (t: Throwable) {
                    emptyList()
                }
                try {
                    adapter.closeProfileProxy(p, proxy)
                } catch (t: Throwable) {
                    // ignoré
                }
                if (cont.isActive) cont.resume(devices)
            }

            override fun onServiceDisconnected(p: Int) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
        val started = try {
            adapter.getProfileProxy(context.applicationContext, listener, profile)
        } catch (t: Throwable) {
            false
        }
        if (!started && cont.isActive) cont.resume(emptyList())
    }
}
