package com.autotasker.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.autotasker.data.EventLog

/**
 * Fermeture d'une application tierce.
 *
 * Aucune API publique ne le permet : `killBackgroundProcesses` n'agit plus que sur l'appli
 * appelante depuis Android 14, et `forceStopPackage` exige `FORCE_STOP_PACKAGES`, de niveau
 * `signature|privileged`.
 *
 * Shizuku lève le blocage de la même façon que pour le partage de connexion, en appelant
 * `IActivityManager.forceStopPackage` sous l'identité du shell — l'équivalent exact de
 * `adb shell am force-stop`. Sans Shizuku, on retombe sur `killBackgroundProcesses`, qui
 * reste utile jusqu'à Android 13.
 */
object AppControl {

    enum class Result {
        OK,
        REPLI_PARTIEL,
        SHIZUKU_ABSENT,
        SHIZUKU_NON_AUTORISE,
        ECHEC;

        val message: String
            get() = when (this) {
                OK -> "fermée"
                REPLI_PARTIEL -> "processus d'arrière-plan signalés (sans effet sur Android 14+)"
                SHIZUKU_ABSENT -> "Shizuku n'est pas lancé"
                SHIZUKU_NON_AUTORISE -> "Shizuku n'a pas autorisé AutoTasker"
                ECHEC -> "refus du système"
            }
    }

    suspend fun forceStop(context: Context, packageName: String): Result {
        if (packageName.isBlank()) return Result.ECHEC

        when (ShizukuBridge.awaitReady()) {
            ShizukuBridge.State.PRET -> Unit
            ShizukuBridge.State.ABSENT -> return legacyFallback(context, packageName, Result.SHIZUKU_ABSENT)
            ShizukuBridge.State.NON_AUTORISE ->
                return legacyFallback(context, packageName, Result.SHIZUKU_NON_AUTORISE)
        }

        return try {
            ShizukuBridge.ensureHiddenApiAccess()
            val am = ShizukuBridge.asInterface(
                "android.app.IActivityManager\$Stub",
                ShizukuBridge.systemService("activity")
            )
            val method = am.javaClass.methods.firstOrNull {
                it.name == "forceStopPackage" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            } ?: error("forceStopPackage introuvable sur cette version d'Android")

            method.invoke(am, packageName, ShizukuBridge.currentUserId())
            Result.OK
        } catch (t: Throwable) {
            EventLog.add("⚠ Fermeture de $packageName : ${t.javaClass.simpleName} ${t.message.orEmpty()}")
            legacyFallback(context, packageName, Result.ECHEC)
        }
    }

    /**
     * Repli sans Shizuku. Efficace jusqu'à Android 13 ; au-delà, Android ignore silencieusement
     * l'appel pour les applications tierces, d'où le résultat distinct.
     */
    private fun legacyFallback(context: Context, packageName: String, reason: Result): Result {
        runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.killBackgroundProcesses(packageName)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            reason
        } else {
            Result.REPLI_PARTIEL
        }
    }
}
