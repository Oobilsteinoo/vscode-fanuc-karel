package com.autotasker.engine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
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

            ActionType.GO_HOME -> goHome(context)

            ActionType.OPEN_URL -> {
                if (action.url.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }

            ActionType.MEDIA_PLAY -> mediaPlay(context)
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

    private fun goHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun mediaPlay(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }
}
