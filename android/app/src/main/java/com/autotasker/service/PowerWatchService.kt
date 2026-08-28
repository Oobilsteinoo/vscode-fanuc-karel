package com.autotasker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autotasker.App
import com.autotasker.MainActivity
import com.autotasker.R
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.engine.Engine
import com.autotasker.engine.EventKind
import com.autotasker.engine.SystemEvent

/**
 * Surveillance des branchements/débranchements du chargeur.
 *
 * Pourquoi un service résident alors que tout le reste de l'appli s'en passe :
 * `ACTION_POWER_CONNECTED` et `ACTION_POWER_DISCONNECTED` **ne font pas partie** des
 * diffusions implicites qu'Android 8+ autorise encore dans le manifeste
 * (https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions).
 * Un receiver déclaré dans le manifeste pour ces deux actions n'est jamais appelé.
 *
 * La seule façon fiable de les recevoir est de les enregistrer à l'exécution, ce qui suppose
 * un processus vivant. Le coût réel reste faible : le service ne fait rien, ne tient aucun
 * wakelock et ne réveille jamais le processeur — il maintient seulement deux filtres
 * d'intention. Contrairement à l'écoute de `ACTION_BATTERY_CHANGED` (émise à chaque pourcent),
 * ces deux diffusions ne surviennent que quelques fois par jour.
 *
 * Le service n'est démarré que si au moins une règle activée utilise un déclencheur lié à la
 * charge — voir [com.autotasker.work.WatchManager]. Les déclencheurs Bluetooth, eux,
 * continuent de fonctionner sans lui.
 */
class PowerWatchService : Service() {

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Engine.handle(applicationContext, SystemEvent(EventKind.POWER))
        }
    }

    private var registered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        EventLog.load()
        startForegroundCompat()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        EventLog.add("Surveillance de la charge : démarrée")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            runCatching { unregisterReceiver(powerReceiver) }
            registered = false
            EventLog.add("Surveillance de la charge : arrêtée")
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CH_WATCH)
            .setContentTitle("AutoTasker")
            .setContentText("Surveillance du chargeur active")
            .setSmallIcon(R.drawable.ic_stat_routine)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1003
    }
}
