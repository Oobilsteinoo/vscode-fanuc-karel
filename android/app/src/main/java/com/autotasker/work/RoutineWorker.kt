package com.autotasker.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.autotasker.App
import com.autotasker.MainActivity
import com.autotasker.R
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.engine.ActionRunner

/**
 * Filet de sécurité : exécute la même séquence quand Android refuse le démarrage
 * d'un service de premier plan depuis l'arrière-plan (Android 12+).
 */
class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        EventLog.load()
        val ids = inputData.getStringArray(KEY_IDS).orEmpty()
        for (id in ids) {
            val rule = Store.rule(id) ?: continue
            ActionRunner.run(applicationContext, rule)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, App.CH_RUNNING)
            .setContentTitle("AutoTasker")
            .setContentText("Routine en cours…")
            .setSmallIcon(R.drawable.ic_stat_routine)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1002
        private const val KEY_IDS = "ids"

        fun enqueue(context: Context, ruleIds: List<String>) {
            val data = Data.Builder().putStringArray(KEY_IDS, ruleIds.toTypedArray()).build()
            val request = OneTimeWorkRequestBuilder<RoutineWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
