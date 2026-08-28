package com.autotasker.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.autotasker.core.BtDevices
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.engine.Engine
import com.autotasker.engine.EventKind
import com.autotasker.engine.SystemEvent
import com.autotasker.work.Scheduler
import com.autotasker.work.WatchManager

/**
 * Unique point d'écoute de l'appli.
 *
 * Les quatre broadcasts utilisés (alimentation et ACL Bluetooth) font partie des rares
 * diffusions implicites encore autorisées dans le manifeste depuis Android 8 : le processus
 * n'est réveillé qu'au moment exact de l'événement, et rien ne tourne entre deux.
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        Store.init(ctx)
        EventLog.load()

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED ->
                Engine.handle(ctx, SystemEvent(EventKind.POWER))

            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                ) ?: return
                val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                val address = device.address?.uppercase() ?: return
                if (connected) BtDevices.markConnected(address) else BtDevices.markDisconnected(address)
                Engine.handle(
                    ctx,
                    SystemEvent(
                        kind = EventKind.BLUETOOTH,
                        btAddress = address,
                        btName = BtDevices.nameOf(device),
                        btConnected = connected
                    )
                )
            }

            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Après un redémarrage, on ne sait plus quels appareils sont connectés :
                // le worker périodique et les prochains ACL rétabliront l'état.
                BtDevices.clearConnected()
                Engine.resetSnapshot()
                Scheduler.sync(ctx)
                // Autorisé ici : BOOT_COMPLETED fait partie des exemptions permettant de
                // démarrer un service de premier plan depuis l'arrière-plan.
                WatchManager.sync(ctx)
                Engine.handle(ctx, SystemEvent(EventKind.BOOT))
            }
        }
    }
}
