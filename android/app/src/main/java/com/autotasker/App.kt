package com.autotasker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.work.Scheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        EventLog.load()
        createChannels()
        runCatching { Scheduler.sync(this) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "Routine en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification silencieuse affichée pendant l'exécution d'une séquence."
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val CH_RUNNING = "routine_running"
    }
}
