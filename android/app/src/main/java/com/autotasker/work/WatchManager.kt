package com.autotasker.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.data.TriggerType
import com.autotasker.service.PowerWatchService

/**
 * Décide si le service de surveillance du chargeur doit tourner.
 *
 * Il ne démarre que lorsqu'au moins une règle activée en a réellement besoin : une
 * configuration purement Bluetooth ne fait tourner aucun service.
 */
object WatchManager {

    private val CHARGING_TRIGGERS = setOf(
        TriggerType.CAR_MODE_START,
        TriggerType.CAR_MODE_END,
        TriggerType.WIRELESS_CHARGING_START,
        TriggerType.WIRELESS_CHARGING_END,
        TriggerType.CHARGING_START,
        TriggerType.CHARGING_END
    )

    fun isNeeded(context: Context): Boolean {
        Store.init(context)
        val cfg = Store.config.value
        if (!cfg.powerWatchEnabled) return false
        return cfg.rules.any {
            it.enabled && it.actions.isNotEmpty() && CHARGING_TRIGGERS.contains(it.trigger.type)
        }
    }

    /** À n'appeler que depuis un contexte de premier plan ou au démarrage du téléphone. */
    fun sync(context: Context) {
        val ctx = context.applicationContext
        val intent = Intent(ctx, PowerWatchService::class.java)
        if (isNeeded(ctx)) {
            try {
                ContextCompat.startForegroundService(ctx, intent)
            } catch (t: Throwable) {
                EventLog.add("Surveillance de la charge refusée par Android (${t.javaClass.simpleName})")
            }
        } else {
            runCatching { ctx.stopService(intent) }
        }
    }
}
