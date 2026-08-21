package com.autotasker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autotasker.core.BtDevices
import com.autotasker.core.DeviceState
import com.autotasker.data.BtDevice
import com.autotasker.data.Config
import com.autotasker.data.Defaults
import com.autotasker.data.Rule
import com.autotasker.data.Store
import com.autotasker.data.TriggerType
import com.autotasker.engine.Engine
import com.autotasker.work.Scheduler
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onEdit: (String) -> Unit, onLogs: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val cfg by Store.config.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showBtPicker by remember { mutableStateOf(false) }

    // Rafraîchissement de l'état affiché — uniquement tant que l'écran est ouvert.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            tick++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AutoTasker") },
                actions = {
                    IconButton(onClick = onLogs) { Icon(Icons.Default.Info, contentDescription = "Journal") }
                    IconButton(onClick = onSetup) { Icon(Icons.Default.Settings, contentDescription = "Réglages") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Règle") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(cfg, tick, onSetup) }
            item { CarDevicesCard(cfg) { showBtPicker = true } }
            items(cfg.rules, key = { it.id }) { rule ->
                RuleCard(rule, cfg) { onEdit(rule.id) }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onCreate = { rule ->
                Store.update { it.copy(rules = it.rules + rule) }
                Scheduler.sync(context)
                onEdit(rule.id)
            }
        )
    }

    if (showBtPicker) {
        BluetoothPickerDialog(cfg) { showBtPicker = false }
    }
}

@Composable
private fun StatusCard(cfg: Config, tick: Int, onSetup: () -> Unit) {
    val context = LocalContext.current
    val charging = remember(tick) { DeviceState.chargingLabel(context) }
    val wireless = remember(tick) { DeviceState.isWirelessCharging(context) }
    val carBt = remember(tick, cfg) { BtDevices.anyCarConnected(cfg.carDevices) }
    val wifi = remember(tick) { DeviceState.isWifiConnected(context) }
    val overlay = remember(tick) { DeviceState.canDrawOverlays(context) }
    val battery = remember(tick) { DeviceState.isIgnoringBatteryOptimizations(context) }

    SectionCard(title = "État actuel") {
        StatusRow("Alimentation", charging, if (wireless) true else null)
        StatusRow("Bluetooth voiture", if (carBt) "connecté" else "non connecté", carBt)
        StatusRow("Wi-Fi", if (wifi) "connecté" else "non connecté", null)
        StatusRow(
            "Mode voiture",
            if (wireless && carBt) "ACTIF" else "inactif",
            wireless && carBt
        )
        if (!overlay || !battery) {
            HorizontalDivider()
            Text(
                "Autorisations incomplètes : sans « Affichage par-dessus les autres applis » et " +
                    "sans exemption d'optimisation de la batterie, Android bloquera le lancement " +
                    "automatique des applications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onSetup) { Text("Corriger") }
        }
    }
}

@Composable
private fun CarDevicesCard(cfg: Config, onPick: () -> Unit) {
    SectionCard(
        title = "Appareils Bluetooth « voiture »",
        subtitle = "Utilisés par le déclencheur « charge sans fil + Bluetooth voiture »."
    ) {
        if (cfg.carDevices.isEmpty()) {
            Text(
                "Aucun appareil sélectionné.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            cfg.carDevices.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${device.name}  ·  ${device.address}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (BtDevices.isConnected(device.address)) {
                        Text("connecté", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        OutlinedButton(onClick = onPick) { Text("Choisir les appareils") }
    }
}

@Composable
private fun RuleCard(rule: Rule, cfg: Config, onEdit: () -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rule.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { enabled ->
                        Store.updateRule(rule.id) { it.copy(enabled = enabled) }
                        Scheduler.sync(context)
                    }
                )
            }
            Text(
                triggerSummary(rule, cfg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rule.actions.isEmpty()) {
                Text(
                    "Aucune action — appuyez sur Modifier pour ajouter les applications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    rule.actions.joinToString("  →  ") { it.describe() },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) { Text("Modifier") }
                TextButton(
                    onClick = { Engine.runNow(context, rule.id) },
                    enabled = rule.actions.isNotEmpty()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Lancer")
                }
            }
        }
    }
}

fun triggerSummary(rule: Rule, cfg: Config): String {
    val trigger = rule.trigger
    val base = when (trigger.type) {
        TriggerType.BT_CONNECTED, TriggerType.BT_DISCONNECTED -> {
            val target = when {
                trigger.btAddress.isBlank() && cfg.carDevices.isEmpty() -> "n'importe quel appareil"
                trigger.btAddress.isBlank() -> "appareils voiture"
                else -> cfg.carDevices.firstOrNull { it.address.equals(trigger.btAddress, true) }?.name
                    ?: trigger.btAddress
            }
            "${trigger.type.label} · $target"
        }

        TriggerType.PERIODIC -> "Toutes les ${trigger.periodMinutes} min"
        else -> trigger.type.label
    }
    val extras = buildList {
        val c = rule.conditions
        if (c.requireWirelessCharging) add("charge sans fil")
        if (c.requireAnyCharging) add("en charge")
        if (c.requireNotCharging) add("pas en charge")
        if (c.requireCarBluetooth) add("BT voiture")
        if (c.requireNoCarBluetooth) add("BT voiture déconnecté")
        if (c.requireScreenOff) add("écran éteint")
        if (c.requireScreenOn) add("écran allumé")
        if (c.requireWifi) add("Wi-Fi")
        if (c.requireNoWifi) add("hors Wi-Fi")
        if (c.startHour >= 0 && c.endHour >= 0) add("${c.startHour}h–${c.endHour}h")
        if (rule.cooldownMinutes > 0) add("min. ${rule.cooldownMinutes} min entre 2 exécutions")
    }
    return if (extras.isEmpty()) base else "$base  ·  ${extras.joinToString(", ")}"
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onCreate: (Rule) -> Unit) {
    val templates = listOf(
        "car" to "Routine voiture (charge sans fil + Bluetooth)",
        "exit" to "Sortie de voiture (Bluetooth déconnecté)",
        "wake" to "Réveil d'applis au départ en voiture",
        "periodic" to "Réveil périodique d'applis",
        "blank" to "Règle vide"
    )
    ChoiceDialog(
        title = "Nouvelle règle",
        options = templates,
        selected = null,
        onPick = { key ->
            onCreate(
                when (key) {
                    "car" -> Defaults.carRoutine()
                    "exit" -> Defaults.carExit()
                    "wake" -> Defaults.wakeOnCar()
                    "periodic" -> Defaults.wakePeriodic()
                    else -> Defaults.blank()
                }
            )
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun BluetoothPickerDialog(cfg: Config, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paired = remember { BtDevices.paired(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Appareils appairés") },
        text = {
            if (paired.isEmpty()) {
                Text(
                    "Aucun appareil appairé lisible. Vérifiez que le Bluetooth est activé et que " +
                        "l'autorisation « Appareils à proximité » est accordée."
                )
            } else {
                Column(modifier = Modifier.heightIn(max = 420.dp)) {
                    LazyColumn {
                        items(paired, key = { it.address }) { device ->
                            val selected = cfg.carDevices.any { it.address.equals(device.address, true) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked -> toggleCarDevice(device, checked) }
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Terminé") } }
    )
}

private fun toggleCarDevice(device: BtDevice, checked: Boolean) {
    Store.update { cfg ->
        val without = cfg.carDevices.filterNot { it.address.equals(device.address, true) }
        cfg.copy(carDevices = if (checked) without + device else without)
    }
}
