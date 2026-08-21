package com.autotasker.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.autotasker.data.Store
import com.autotasker.data.TriggerType
import java.util.concurrent.TimeUnit

/**
 * Planification du seul travail récurrent de l'appli.
 *
 * Il n'existe que si au moins une règle « périodique » est activée, et n'est jamais plus
 * fréquent que la plus petite période demandée (minimum imposé par Android : 15 min).
 * WorkManager regroupe ces réveils avec ceux des autres applis et respecte le Doze.
 */
object Scheduler {

    private const val WORK_PERIODIC = "autotasker_periodic"

    fun sync(context: Context) {
        Store.init(context)
        val wm = WorkManager.getInstance(context.applicationContext)
        val periodicRules = Store.config.value.rules.filter {
            it.enabled && it.actions.isNotEmpty() && it.trigger.type == TriggerType.PERIODIC
        }

        if (periodicRules.isEmpty()) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            return
        }

        val minutes = periodicRules.minOf { it.trigger.periodMinutes }.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<PeriodicWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
