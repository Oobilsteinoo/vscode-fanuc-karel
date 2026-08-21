package com.autotasker.engine

import android.content.Context
import com.autotasker.core.BtDevices
import com.autotasker.core.DeviceState
import com.autotasker.data.Conditions
import com.autotasker.data.Config
import com.autotasker.data.EventLog
import com.autotasker.data.Rule
import com.autotasker.data.Store
import com.autotasker.data.TriggerType
import com.autotasker.service.RoutineService
import kotlinx.serialization.Serializable
import java.util.Calendar

enum class EventKind { POWER, BLUETOOTH, BOOT, PERIODIC, MANUAL }

data class SystemEvent(
    val kind: EventKind,
    val btAddress: String = "",
    val btName: String = "",
    val btConnected: Boolean = false
)

/**
 * Moteur de règles.
 *
 * Principe : chaque événement de l'OS provoque **une** photo de l'état de l'appareil.
 * Les déclencheurs sont déduits de la différence avec la photo précédente (fronts montants /
 * descendants). Aucun timer, aucun service résident, aucune boucle de sondage.
 */
object Engine {

    private const val KEY_SNAPSHOT = "snapshot"
    private const val KEY_LAST_RUN = "lastrun_"

    @Serializable
    data class Snapshot(
        val wireless: Boolean = false,
        val charging: Boolean = false,
        val carBt: Boolean = false,
        val wifi: Boolean = false,
        val ssid: String = ""
    ) {
        val carMode: Boolean get() = wireless && carBt
    }

    fun snapshot(context: Context, cfg: Config): Snapshot = Snapshot(
        wireless = DeviceState.isWirelessCharging(context),
        charging = DeviceState.isCharging(context),
        carBt = BtDevices.anyCarConnected(cfg.carDevices),
        wifi = DeviceState.isWifiConnected(context),
        ssid = DeviceState.wifiSsid(context)
    )

    private fun loadSnapshot(): Snapshot? {
        val raw = Store.getString(KEY_SNAPSHOT, "")
        if (raw.isBlank()) return null
        return runCatching { Store.json.decodeFromString(Snapshot.serializer(), raw) }.getOrNull()
    }

    /** Oublie l'état connu (après un redémarrage, on ne sait plus rien). */
    fun resetSnapshot() {
        Store.putString(KEY_SNAPSHOT, "")
    }

    private fun saveSnapshot(s: Snapshot) {
        Store.putString(KEY_SNAPSHOT, Store.json.encodeToString(Snapshot.serializer(), s))
    }

    /** Point d'entrée unique appelé par les receivers et les workers. */
    fun handle(context: Context, event: SystemEvent) {
        val ctx = context.applicationContext
        Store.init(ctx)
        EventLog.load()
        val cfg = Store.config.value

        val previous = loadSnapshot()
        val current = snapshot(ctx, cfg)
        saveSnapshot(current)

        val fired = firedTriggers(previous, current, event)
        if (fired.isEmpty()) return

        val matching = cfg.rules.filter { rule ->
            rule.enabled &&
                rule.actions.isNotEmpty() &&
                matchesTrigger(rule, fired, event, cfg, current) &&
                conditionsMet(ctx, rule.conditions, current) &&
                cooldownElapsed(rule)
        }

        if (matching.isEmpty()) {
            // Trace utile pour comprendre pourquoi « ça n'a pas marché ».
            val blocked = cfg.rules.filter { it.enabled && matchesTrigger(it, fired, event, cfg, current) }
            if (blocked.isNotEmpty()) {
                val names = blocked.joinToString(", ") { rule ->
                    val why = when {
                        rule.actions.isEmpty() -> "aucune action"
                        !conditionsMet(ctx, rule.conditions, current) -> "conditions non remplies"
                        !cooldownElapsed(rule) -> "délai anti-répétition"
                        else -> "?"
                    }
                    "${rule.name} ($why)"
                }
                EventLog.add("Déclencheur ${fired.joinToString { it.label }} ignoré : $names")
            }
            return
        }

        val now = System.currentTimeMillis()
        matching.forEach { Store.putLong(KEY_LAST_RUN + it.id, now) }
        EventLog.add("Déclencheur : ${fired.joinToString { it.label }} → ${matching.joinToString { it.name }}")
        RoutineService.start(ctx, matching.map { it.id })
    }

    /** Lance une règle sans tenir compte du déclencheur ni des conditions (bouton « Lancer »). */
    fun runNow(context: Context, ruleId: String) {
        val ctx = context.applicationContext
        Store.init(ctx)
        EventLog.load()
        Store.putLong(KEY_LAST_RUN + ruleId, System.currentTimeMillis())
        RoutineService.start(ctx, listOf(ruleId))
    }

    private fun firedTriggers(
        previous: Snapshot?,
        current: Snapshot,
        event: SystemEvent
    ): Set<TriggerType> {
        val fired = mutableSetOf<TriggerType>()

        if (previous != null) {
            if (current.carMode && !previous.carMode) fired += TriggerType.CAR_MODE_START
            if (!current.carMode && previous.carMode) fired += TriggerType.CAR_MODE_END
            if (current.wireless && !previous.wireless) fired += TriggerType.WIRELESS_CHARGING_START
            if (!current.wireless && previous.wireless) fired += TriggerType.WIRELESS_CHARGING_END
            if (current.charging && !previous.charging) fired += TriggerType.CHARGING_START
            if (!current.charging && previous.charging) fired += TriggerType.CHARGING_END
            if (current.wifi && !previous.wifi) fired += TriggerType.WIFI_JOINED
            if (!current.wifi && previous.wifi) fired += TriggerType.WIFI_LOST
        } else if (current.carMode) {
            fired += TriggerType.CAR_MODE_START
        }

        when (event.kind) {
            EventKind.BOOT -> fired += TriggerType.BOOT
            EventKind.PERIODIC -> fired += TriggerType.PERIODIC
            EventKind.BLUETOOTH ->
                fired += if (event.btConnected) TriggerType.BT_CONNECTED else TriggerType.BT_DISCONNECTED
            else -> Unit
        }
        return fired
    }

    private fun matchesTrigger(
        rule: Rule,
        fired: Set<TriggerType>,
        event: SystemEvent,
        cfg: Config,
        snap: Snapshot
    ): Boolean {
        val type = rule.trigger.type
        if (type == TriggerType.MANUAL) return false
        if (!fired.contains(type)) return false

        return when (type) {
            TriggerType.BT_CONNECTED, TriggerType.BT_DISCONNECTED -> {
                val target = rule.trigger.btAddress
                when {
                    target.isNotBlank() -> target.equals(event.btAddress, ignoreCase = true)
                    cfg.carDevices.isEmpty() -> true
                    else -> cfg.carDevices.any { it.address.equals(event.btAddress, ignoreCase = true) }
                }
            }

            TriggerType.WIFI_JOINED, TriggerType.WIFI_LOST -> {
                val target = rule.trigger.ssid
                // Le SSID n'est lisible que si la localisation est accordée ; sinon on ne filtre pas.
                target.isBlank() || snap.ssid.isBlank() || target.equals(snap.ssid, ignoreCase = true)
            }

            else -> true
        }
    }

    private fun conditionsMet(context: Context, c: Conditions, snap: Snapshot): Boolean {
        if (c.isEmpty) return true
        if (c.requireWirelessCharging && !snap.wireless) return false
        if (c.requireAnyCharging && !snap.charging) return false
        if (c.requireNotCharging && snap.charging) return false
        if (c.requireCarBluetooth && !snap.carBt) return false
        if (c.requireNoCarBluetooth && snap.carBt) return false
        if (c.requireWifi && !snap.wifi) return false
        if (c.requireNoWifi && snap.wifi) return false
        if (c.requireScreenOff && DeviceState.isScreenOn(context)) return false
        if (c.requireScreenOn && !DeviceState.isScreenOn(context)) return false
        if (c.startHour >= 0 && c.endHour >= 0 && c.startHour != c.endHour) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val inRange = if (c.startHour < c.endHour) {
                hour >= c.startHour && hour < c.endHour
            } else {
                hour >= c.startHour || hour < c.endHour
            }
            if (!inRange) return false
        }
        return true
    }

    private fun cooldownElapsed(rule: Rule): Boolean {
        if (rule.cooldownMinutes <= 0) return true
        val last = Store.getLong(KEY_LAST_RUN + rule.id, 0L)
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= rule.cooldownMinutes * 60_000L
    }

    fun lastRun(ruleId: String): Long = Store.getLong(KEY_LAST_RUN + ruleId, 0L)
}
