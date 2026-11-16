package com.autotasker.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import com.autotasker.core.Apps
import com.autotasker.data.Action
import com.autotasker.data.ActionType
import com.autotasker.data.EventLog
import com.autotasker.data.Rule
import kotlinx.coroutines.delay

/** Exécute la liste d'actions d'une règle, dans l'ordre. */
object ActionRunner {

    /** Petite respiration entre deux actions : laisse le système terminer la transition d'activité. */
    private const val GAP_MS = 350L

    suspend fun run(context: Context, rule: Rule, onStep: (String) -> Unit = {}) {
        EventLog.add("▶ ${rule.name}")
        rule.actions.forEachIndexed { index, action ->
            onStep("${index + 1}/${rule.actions.size} · ${action.describe()}")
            runCatching { execute(context, action) }
                .onFailure { EventLog.add("⚠ ${action.describe()} : ${it.javaClass.simpleName} ${it.message.orEmpty()}") }
            delay(GAP_MS)
        }
        EventLog.add("✔ ${rule.name} terminée")
    }

    private suspend fun execute(context: Context, action: Action) {
        when (action.type) {
            ActionType.DELAY -> delay(action.delayMs.coerceIn(0L, 10 * 60_000L))

            ActionType.LAUNCH_APP -> launchApp(context, action.packageName)

            ActionType.WAKE_APP -> {
                launchApp(context, action.packageName)
                delay(action.delayMs.coerceIn(500L, 60_000L))
                goHome(context)
            }

            ActionType.CLOSE_APP -> closeApp(context, action.packageName)

            ActionType.GO_HOME -> goHome(context)

            ActionType.OPEN_URL -> {
                if (action.url.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }

            ActionType.MEDIA_PLAY -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

            ActionType.MEDIA_PAUSE -> mediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)

            ActionType.OPEN_TETHER_SETTINGS -> openTetherSettings(context)
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            EventLog.add("⚠ ${Apps.labelFor(context, packageName)} introuvable ou non lançable")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    /**
     * Demande à Android de tuer les processus d'arrière-plan d'une application.
     *
     * Attention : depuis Android 14, `killBackgroundProcesses` n'agit plus que sur l'appli
     * appelante — l'appel devient silencieusement sans effet sur les autres. Il n'existe aucune
     * API publique pour fermer une application tierce sans service d'accessibilité. Sur ces
     * versions, préférez « Retour à l'accueil » (qui met l'appli en arrière-plan) et
     * « Pause média » (qui coupe réellement la lecture).
     */
    private fun closeApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        am.killBackgroundProcesses(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            EventLog.add(
                "ℹ Fermeture de ${Apps.labelFor(context, packageName)} demandée, " +
                    "mais Android 14+ l'ignore pour les applis tierces"
            )
        }
    }

    private fun goHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Ouvre l'écran du partage de connexion.
     *
     * Il n'existe pas de constante publique pour cet écran : on tente l'activité d'AOSP, puis
     * l'action non documentée que la plupart des surcouches déclarent, et enfin les réglages
     * sans fil, qui sont eux garantis par le SDK. Le premier candidat qui démarre gagne.
     */
    private fun openTetherSettings(context: Context) {
        val candidates = listOf(
            "activité AOSP" to Intent().setClassName(
                "com.android.settings",
                "com.android.settings.TetherSettings"
            ),
            "action TETHER_SETTINGS" to Intent("android.settings.TETHER_SETTINGS"),
            "réglages sans fil" to Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for ((name, intent) in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (t: Throwable) {
                EventLog.add("Partage de connexion : $name indisponible, candidat suivant")
            }
        }
        EventLog.add("⚠ Aucun écran de partage de connexion trouvé sur cet appareil")
    }

    private fun mediaKey(context: Context, keyCode: Int) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
