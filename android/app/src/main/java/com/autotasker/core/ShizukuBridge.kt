package com.autotasker.core

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.delay
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Plomberie commune aux actions qui exigent des privilèges hors de portée d'une application.
 *
 * Shizuku expose les services système sous l'identité du shell (uid 2000), qui détient des
 * permissions `signature|privileged` comme `TETHER_PRIVILEGED` ou `FORCE_STOP_PACKAGES`.
 * L'appli ne gagne aucun privilège par elle-même : elle emprunte celui du shell, le temps
 * d'un appel, et seulement si l'utilisateur a lancé Shizuku et autorisé AutoTasker.
 */
object ShizukuBridge {

    const val REQUEST_CODE = 4242

    enum class State { ABSENT, NON_AUTORISE, PRET }

    @Volatile
    private var exemptionsRequested = false

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun granted(): Boolean = try {
        running() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /**
     * Le processus est souvent démarré par une diffusion : Shizuku peut n'avoir pas encore
     * transmis son binder au moment de l'appel, d'où la courte attente.
     */
    suspend fun awaitReady(timeoutMs: Long = 3000L): State {
        if (!running()) {
            var waited = 0L
            while (waited < timeoutMs && !running()) {
                delay(250)
                waited += 250
            }
        }
        return when {
            !running() -> State.ABSENT
            !granted() -> State.NON_AUTORISE
            else -> State.PRET
        }
    }

    /** Lève les restrictions d'accès aux API non-SDK, indispensables à la réflexion ci-après. */
    fun ensureHiddenApiAccess() {
        if (exemptionsRequested) return
        exemptionsRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    /** Binder d'un service système, enveloppé pour être appelé sous l'identité du shell. */
    fun systemService(name: String): IBinder {
        val raw = SystemServiceHelper.getSystemService(name)
            ?: error("service système « $name » introuvable")
        return ShizukuBinderWrapper(raw)
    }

    /** Interface AIDL interne d'un service système, atteinte par réflexion. */
    fun asInterface(stubClassName: String, binder: IBinder): Any {
        val stub = Class.forName(stubClassName)
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
            ?: error("$stubClassName.asInterface a renvoyé null")
    }

    /** Identifiant de l'utilisateur courant (0 pour l'utilisateur principal). */
    fun currentUserId(): Int = try {
        ensureHiddenApiAccess()
        Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as Int
    } catch (t: Throwable) {
        0
    }
}
