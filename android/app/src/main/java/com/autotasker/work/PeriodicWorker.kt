package com.autotasker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autotasker.core.BtDevices
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.engine.Engine
import com.autotasker.engine.EventKind
import com.autotasker.engine.SystemEvent

/** Réveil périodique : resynchronise l'état Bluetooth puis évalue les règles. */
class PeriodicWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        EventLog.load()
        runCatching { BtDevices.refreshFromProfiles(applicationContext) }
        Engine.handle(applicationContext, SystemEvent(EventKind.PERIODIC))
        return Result.success()
    }
}
