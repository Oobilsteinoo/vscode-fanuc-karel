package com.autotasker.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import com.autotasker.data.EventLog
import kotlinx.coroutines.delay
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Activation et désactivation du partage de connexion via Shizuku.
 *
 * Aucune API publique ne permet de basculer le partage de connexion :
 * `TetheringManager.startTethering()` exige `TETHER_PRIVILEGED`, de niveau
 * `signature|privileged`. Shizuku contourne la restriction en exécutant l'appel sous l'identité
 * du shell (uid 2000), qui détient cette permission — c'est le même service système que celui
 * que pilote le bouton des réglages.
 *
 * Conséquences directes :
 *  - Shizuku doit être installé **et lancé**. Sans root, il doit être relancé après chaque
 *    redémarrage, sauf si son option « démarrage au boot » (débogage sans fil) est active.
 *  - Les vérifications d'éligibilité opérateur continuent de s'appliquer, exactement comme
 *    lorsqu'on appuie sur le bouton.
 *
 * Tout passe par la réflexion : `ITetheringConnector` est une interface interne dont la
 * signature varie d'une version d'Android à l'autre. La méthode est donc retrouvée par son nom,
 * son nombre de paramètres et le type du premier, plutôt que liée à la compilation.
 */
object Tethering {

    /** `TetheringManager.TETHERING_WIFI`. */
    private const val TETHERING_WIFI = 0

    /** `WifiManager.WIFI_AP_STATE_ENABLED`, masqué de l'API publique. */
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * L'appel s'exécute sous l'uid du shell : le nom de paquet transmis doit correspondre à cet
     * uid, sans quoi le service rejette la requête.
     */
    private const val CALLER_PACKAGE = "com.android.shell"

    private const val LISTENER_INTERFACE = "android.net.IIntResultListener"

    @Volatile
    private var exemptionsRequested = false

    enum class Result {
        OK,
        SHIZUKU_ABSENT,
        SHIZUKU_NON_AUTORISE,
        ECHEC;

        val message: String
            get() = when (this) {
                OK -> "Partage de connexion basculé"
                SHIZUKU_ABSENT -> "Shizuku n'est pas lancé — le partage de connexion ne peut pas être basculé"
                SHIZUKU_NON_AUTORISE -> "Shizuku n'a pas autorisé AutoTasker — à accorder dans Réglages"
                ECHEC -> "Le système a refusé le basculement du partage de connexion"
            }
    }

    // ---- état de Shizuku ----

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = try {
        shizukuRunning() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
    }

    const val SHIZUKU_REQUEST_CODE = 4242

    // ---- action ----

    /**
     * Bascule le partage de connexion Wi-Fi.
     *
     * Le processus est souvent démarré par une diffusion : Shizuku peut n'avoir pas encore
     * transmis son binder au moment de l'appel, d'où la courte attente initiale.
     */
    suspend fun setEnabled(context: Context, enable: Boolean): Result {
        if (!awaitShizuku()) return Result.SHIZUKU_ABSENT
        if (!shizukuGranted()) return Result.SHIZUKU_NON_AUTORISE

        return try {
            ensureHiddenApiAccess()
            val code = if (enable) start() else stop()
            // 0 = succès ; null = requête transmise sans accusé de réception dans le délai imparti.
            if (code == null || code == 0) {
                Result.OK
            } else {
                EventLog.add("⚠ Partage de connexion : code de retour $code")
                Result.ECHEC
            }
        } catch (t: Throwable) {
            EventLog.add("⚠ Partage de connexion : ${t.javaClass.simpleName} ${t.message.orEmpty()}")
            Result.ECHEC
        }
    }

    /** `true`/`false` si l'état est lisible, `null` sinon. */
    fun isEnabled(context: Context): Boolean? = try {
        ensureHiddenApiAccess()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val state = WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as Int
        state == WIFI_AP_STATE_ENABLED
    } catch (t: Throwable) {
        null
    }

    // ---- interne ----

    private suspend fun awaitShizuku(timeoutMs: Long = 3000L): Boolean {
        if (shizukuRunning()) return true
        var waited = 0L
        while (waited < timeoutMs) {
            delay(250)
            waited += 250
            if (shizukuRunning()) return true
        }
        return false
    }

    private fun ensureHiddenApiAccess() {
        if (exemptionsRequested) return
        exemptionsRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
    }

    private fun connector(): Any {
        val raw = SystemServiceHelper.getSystemService("tethering")
            ?: error("service système « tethering » introuvable")
        val stub = Class.forName("android.net.ITetheringConnector\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, ShizukuBinderWrapper(raw))
            ?: error("ITetheringConnector.asInterface a renvoyé null")
    }

    private fun stop(): Int? =
        callConnector("stopTethering", TETHERING_WIFI) { it == Int::class.javaPrimitiveType }

    private fun start(): Int? {
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)
        val request = builderClass.getMethod("build").invoke(builder)
            ?: error("TetheringRequest.Builder.build() a renvoyé null")

        // Selon la version d'Android, startTethering attend le parcelable interne
        // (TetheringRequestParcel) ou directement l'objet TetheringRequest.
        val parcel = runCatching { requestClass.getMethod("getParcel").invoke(request) }.getOrNull()
        if (parcel != null) {
            val viaParcel = runCatching {
                callConnector("startTethering", parcel) {
                    it.name == "android.net.TetheringRequestParcel"
                }
            }
            if (viaParcel.isSuccess) return viaParcel.getOrNull()
        }
        return callConnector("startTethering", request) {
            it.name == "android.net.TetheringManager\$TetheringRequest"
        }
    }

    /**
     * Invoque `methodName(arg0, callerPkg, attributionTag, listener)` et attend le code de retour.
     * Renvoie `null` si aucun accusé n'arrive dans le délai.
     */
    private fun callConnector(
        methodName: String,
        arg0: Any?,
        firstParameterMatches: (Class<*>) -> Boolean
    ): Int? {
        val connector = connector()
        val method = connector.javaClass.methods.firstOrNull {
            it.name == methodName &&
                it.parameterTypes.size == 4 &&
                firstParameterMatches(it.parameterTypes[0])
        } ?: error("$methodName introuvable sur cette version d'Android")

        val latch = CountDownLatch(1)
        val result = intArrayOf(Int.MIN_VALUE)

        method.invoke(connector, arg0, CALLER_PACKAGE, null, resultListener(latch, result))

        return if (latch.await(5, TimeUnit.SECONDS)) result[0] else null
    }

    /**
     * `IIntResultListener` est une interface AIDL interne. On fournit un proxy dont le seul rôle
     * est de répondre à `asBinder()` ; la réponse réelle arrive par transaction binder.
     */
    private fun resultListener(latch: CountDownLatch, out: IntArray): Any {
        val iface = Class.forName(LISTENER_INTERFACE)
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.FIRST_CALL_TRANSACTION) {
                    data.enforceInterface(LISTENER_INTERFACE)
                    out[0] = data.readInt()
                    latch.countDown()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, _ ->
            if (method.name == "asBinder") binder else null
        }
    }
}
