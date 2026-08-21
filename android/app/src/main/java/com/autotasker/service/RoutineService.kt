package com.autotasker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.autotasker.engine.ActionRunner
import com.autotasker.work.RoutineWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service de premier plan éphémère : il ne vit que le temps d'une séquence (quelques secondes),
 * puis s'arrête. C'est le seul moment où l'appli consomme quoi que ce soit.
 */
class RoutineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ArrayDeque<String>()
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notification("Préparation…"))
        Store.init(applicationContext)
        EventLog.load()

        val ids = intent?.getStringArrayListExtra(EXTRA_RULE_IDS).orEmpty()
        synchronized(queue) { queue.addAll(ids) }

        if (job?.isActive != true) {
            job = scope.launch { drain() }
        }
        return START_NOT_STICKY
    }

    private suspend fun drain() {
        while (true) {
            val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            val rule = Store.rule(id) ?: continue
            updateNotification(rule.name)
            ActionRunner.run(applicationContext, rule) { step -> updateNotification("${rule.name} · $step") }
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CH_RUNNING)
            .setContentTitle("AutoTasker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_routine)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, notification(text))
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val EXTRA_RULE_IDS = "rule_ids"

        /**
         * Démarre la séquence. Si Android refuse le service de premier plan (restriction de
         * démarrage en arrière-plan sur Android 12+), on bascule sur WorkManager en travail accéléré.
         */
        fun start(context: Context, ruleIds: List<String>) {
            if (ruleIds.isEmpty()) return
            val intent = Intent(context, RoutineService::class.java)
                .putStringArrayListExtra(EXTRA_RULE_IDS, ArrayList(ruleIds))
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                EventLog.add("Service refusé (${t.javaClass.simpleName}) → repli WorkManager")
                RoutineWorker.enqueue(context, ruleIds)
            }
        }
    }
}
