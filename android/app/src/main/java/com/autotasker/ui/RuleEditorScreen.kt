package com.autotasker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autotasker.data.Action
import com.autotasker.data.ActionType
import com.autotasker.data.Rule
import com.autotasker.data.Store
import com.autotasker.data.TriggerType
import com.autotasker.work.Scheduler
import com.autotasker.work.WatchManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(ruleId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val cfg by Store.config.collectAsState()
    val rule = cfg.rules.firstOrNull { it.id == ruleId }

    if (rule == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var showTriggerPicker by remember { mutableStateOf(false) }
    var showBtTargetPicker by remember { mutableStateOf(false) }
    var showPeriodPicker by remember { mutableStateOf(false) }
    var showCooldownPicker by remember { mutableStateOf(false) }
    var showAddAction by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var appPickerFor by remember { mutableStateOf<ActionType?>(null) }
    var showStartHourPicker by remember { mutableStateOf(false) }
    var showEndHourPicker by remember { mutableStateOf(false) }
    var delayPickerIndex by remember { mutableStateOf(-1) }

    fun mutate(block: (Rule) -> Rule) = Store.updateRule(rule.id, block)

    fun finish() {
        Scheduler.sync(context)
        WatchManager.sync(context)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifier la règle") },
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = "Nom") {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { name -> mutate { it.copy(name = name) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                SwitchRow("Règle active", rule.enabled) { enabled ->
                    mutate { it.copy(enabled = enabled) }
                }
            }

            SectionCard(
                title = "Déclencheur",
                subtitle = "Tous ces événements sont diffusés par Android : rien ne tourne en tâche de fond entre deux."
            ) {
                ValueRow("Événement", rule.trigger.type.label) { showTriggerPicker = true }

                if (rule.trigger.type == TriggerType.BT_CONNECTED ||
                    rule.trigger.type == TriggerType.BT_DISCONNECTED
                ) {
                    val target = when {
                        rule.trigger.btAddress.isBlank() -> "Appareils « voiture »"
                        else -> cfg.carDevices.firstOrNull {
                            it.address.equals(rule.trigger.btAddress, true)
                        }?.name ?: rule.trigger.btAddress
                    }
                    ValueRow("Appareil ciblé", target) { showBtTargetPicker = true }
                }

                if (rule.trigger.type == TriggerType.PERIODIC) {
                    ValueRow("Période", "${rule.trigger.periodMinutes} min") { showPeriodPicker = true }
                    Text(
                        "Android n'autorise pas moins de 15 minutes, et regroupe ces réveils avec " +
                            "ceux des autres applications pour économiser la batterie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ValueRow(
                    "Délai anti-répétition",
                    if (rule.cooldownMinutes > 0) "${rule.cooldownMinutes} min" else "aucun"
                ) { showCooldownPicker = true }
            }

            SectionCard(
                title = "Conditions",
                subtitle = "Filtres vérifiés au moment du déclenchement. Toutes doivent être vraies."
            ) {
                val c = rule.conditions

                SwitchRow("Charge sans fil en cours", c.requireWirelessCharging) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireWirelessCharging = v,
                                requireNotCharging = if (v) false else it.conditions.requireNotCharging
                            )
                        )
                    }
                }
                SwitchRow("En charge (filaire ou sans fil)", c.requireAnyCharging) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireAnyCharging = v,
                                requireNotCharging = if (v) false else it.conditions.requireNotCharging
                            )
                        )
                    }
                }
                SwitchRow(
                    "Pas en charge",
                    c.requireNotCharging,
                    "Le téléphone a quitté le chargeur : typique d'une sortie de voiture."
                ) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireNotCharging = v,
                                requireWirelessCharging = if (v) false else it.conditions.requireWirelessCharging,
                                requireAnyCharging = if (v) false else it.conditions.requireAnyCharging
                            )
                        )
                    }
                }

                HorizontalDivider()

                SwitchRow("Bluetooth voiture connecté", c.requireCarBluetooth) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireCarBluetooth = v,
                                requireNoCarBluetooth = if (v) false else it.conditions.requireNoCarBluetooth
                            )
                        )
                    }
                }
                SwitchRow(
                    "Bluetooth voiture déconnecté",
                    c.requireNoCarBluetooth,
                    "Garantit que la règle ne s'exécute qu'une fois vraiment sorti du véhicule."
                ) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireNoCarBluetooth = v,
                                requireCarBluetooth = if (v) false else it.conditions.requireCarBluetooth
                            )
                        )
                    }
                }

                HorizontalDivider()

                SwitchRow(
                    "Écran éteint uniquement",
                    c.requireScreenOff,
                    "Idéal pour les réveils d'applis : l'ouverture reste invisible."
                ) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireScreenOff = v,
                                requireScreenOn = if (v) false else it.conditions.requireScreenOn
                            )
                        )
                    }
                }
                SwitchRow("Écran allumé uniquement", c.requireScreenOn) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireScreenOn = v,
                                requireScreenOff = if (v) false else it.conditions.requireScreenOff
                            )
                        )
                    }
                }
                SwitchRow("Wi-Fi connecté", c.requireWifi) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireWifi = v,
                                requireNoWifi = if (v) false else it.conditions.requireNoWifi
                            )
                        )
                    }
                }
                SwitchRow(
                    "Hors Wi-Fi",
                    c.requireNoWifi,
                    "Approximation utile de « je ne suis pas à la maison »."
                ) { v ->
                    mutate {
                        it.copy(
                            conditions = it.conditions.copy(
                                requireNoWifi = v,
                                requireWifi = if (v) false else it.conditions.requireWifi
                            )
                        )
                    }
                }

                HorizontalDivider()

                ValueRow("Plage horaire — début", hourLabel(c.startHour)) { showStartHourPicker = true }
                ValueRow("Plage horaire — fin", hourLabel(c.endHour)) { showEndHourPicker = true }
                Text(
                    "Les deux bornes doivent être définies pour que la contrainte s'applique. " +
                        "Le passage de minuit est géré (22h → 6h fonctionne).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(
                title = "Actions",
                subtitle = "Exécutées dans l'ordre, de haut en bas."
            ) {
                if (rule.actions.isEmpty()) {
                    Text(
                        "Aucune action.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                rule.actions.forEachIndexed { index, action ->
                    ActionRow(
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < rule.actions.size - 1,
                        onMove = { offset ->
                            mutate { r ->
                                val list = r.actions.toMutableList()
                                val target = index + offset
                                if (target in list.indices) {
                                    val item = list.removeAt(index)
                                    list.add(target, item)
                                }
                                r.copy(actions = list)
                            }
                        },
                        onDelete = {
                            mutate { r ->
                                r.copy(actions = r.actions.filterIndexed { i, _ -> i != index })
                            }
                        },
                        onEditDuration = { delayPickerIndex = index }
                    )
                    HorizontalDivider()
                }
                OutlinedButton(onClick = { showAddAction = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Ajouter une action")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTriggerPicker) {
        ChoiceDialog(
            title = "Déclencheur",
            options = TriggerType.entries.map { it to it.label },
            selected = rule.trigger.type,
            onPick = { type ->
                mutate { it.copy(trigger = it.trigger.copy(type = type)) }
            },
            onDismiss = { showTriggerPicker = false }
        )
    }

    if (showBtTargetPicker) {
        val options = buildList {
            add("" to "Appareils « voiture » (n'importe lequel)")
            cfg.carDevices.forEach { add(it.address to it.name) }
        }
        ChoiceDialog(
            title = "Appareil ciblé",
            options = options,
            selected = rule.trigger.btAddress,
            onPick = { address ->
                mutate { it.copy(trigger = it.trigger.copy(btAddress = address)) }
            },
            onDismiss = { showBtTargetPicker = false }
        )
    }

    if (showPeriodPicker) {
        ChoiceDialog(
            title = "Période",
            options = listOf(15, 30, 60, 120, 180, 360).map { it to "$it minutes" },
            selected = rule.trigger.periodMinutes,
            onPick = { minutes ->
                mutate { it.copy(trigger = it.trigger.copy(periodMinutes = minutes)) }
                Scheduler.sync(context)
            },
            onDismiss = { showPeriodPicker = false }
        )
    }

    if (showCooldownPicker) {
        ChoiceDialog(
            title = "Délai minimal entre deux exécutions",
            options = listOf(0, 1, 2, 5, 10, 15, 30, 60, 120).map {
                it to if (it == 0) "Aucun" else "$it minutes"
            },
            selected = rule.cooldownMinutes,
            onPick = { minutes -> mutate { it.copy(cooldownMinutes = minutes) } },
            onDismiss = { showCooldownPicker = false }
        )
    }

    if (showAddAction) {
        ChoiceDialog(
            title = "Type d'action",
            options = ActionType.entries.map { it to it.label },
            selected = null,
            onPick = { type ->
                when (type) {
                    ActionType.LAUNCH_APP, ActionType.WAKE_APP, ActionType.CLOSE_APP ->
                        appPickerFor = type
                    ActionType.DELAY -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.DELAY, delayMs = 3000))
                    }
                    ActionType.GO_HOME -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.GO_HOME))
                    }
                    ActionType.MEDIA_PLAY -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.MEDIA_PLAY))
                    }
                    ActionType.MEDIA_PAUSE -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.MEDIA_PAUSE))
                    }
                    ActionType.OPEN_TETHER_SETTINGS -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.OPEN_TETHER_SETTINGS))
                    }
                    ActionType.TETHERING_ON -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.TETHERING_ON))
                    }
                    ActionType.TETHERING_OFF -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.TETHERING_OFF))
                    }
                    ActionType.OPEN_URL -> mutate {
                        it.copy(actions = it.actions + Action(type = ActionType.OPEN_URL, url = "https://"))
                    }
                }
            },
            onDismiss = { showAddAction = false }
        )
    }

    val pickerType = appPickerFor
    if (pickerType != null) {
        AppPickerDialog(
            onPick = { app ->
                mutate {
                    it.copy(
                        actions = it.actions + Action(
                            type = pickerType,
                            packageName = app.packageName,
                            label = app.label,
                            delayMs = if (pickerType == ActionType.WAKE_APP) 4000 else 2000
                        )
                    )
                }
            },
            onDismiss = { appPickerFor = null }
        )
    }

    if (delayPickerIndex >= 0) {
        val index = delayPickerIndex
        val current = rule.actions.getOrNull(index)
        if (current == null) {
            delayPickerIndex = -1
        } else {
            NumberDialog(
                title = if (current.type == ActionType.DELAY) "Durée d'attente" else "Temps d'affichage",
                initial = current.delayMs / 1000,
                suffix = "s",
                min = 1,
                max = 300,
                onConfirm = { seconds ->
                    mutate { r ->
                        r.copy(
                            actions = r.actions.mapIndexed { i, a ->
                                if (i == index) a.copy(delayMs = seconds * 1000) else a
                            }
                        )
                    }
                },
                onDismiss = { delayPickerIndex = -1 }
            )
        }
    }

    if (showStartHourPicker) {
        ChoiceDialog(
            title = "Heure de début",
            options = hourOptions(),
            selected = rule.conditions.startHour,
            onPick = { hour -> mutate { it.copy(conditions = it.conditions.copy(startHour = hour)) } },
            onDismiss = { showStartHourPicker = false }
        )
    }

    if (showEndHourPicker) {
        ChoiceDialog(
            title = "Heure de fin",
            options = hourOptions(),
            selected = rule.conditions.endHour,
            onPick = { hour -> mutate { it.copy(conditions = it.conditions.copy(endHour = hour)) } },
            onDismiss = { showEndHourPicker = false }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Supprimer « ${rule.name} » ?") },
            confirmButton = {
                TextButton(onClick = {
                    Store.update { cfg2 -> cfg2.copy(rules = cfg2.rules.filterNot { it.id == rule.id }) }
                    showDeleteConfirm = false
                    finish()
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Annuler") }
            }
        )
    }
}

private fun hourLabel(hour: Int): String = if (hour < 0) "aucune" else "${hour}h"

private fun hourOptions(): List<Pair<Int, String>> =
    listOf(-1 to "Aucune") + (0..23).map { it to "${it}h" }

@Composable
private fun ActionRow(
    action: Action,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onEditDuration: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(action.describe(), style = MaterialTheme.typography.bodyLarge)
            if (action.packageName.isNotBlank()) {
                Text(
                    action.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (action.type == ActionType.DELAY || action.type == ActionType.WAKE_APP) {
            TextButton(onClick = onEditDuration) { Text("${action.delayMs / 1000}s") }
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Monter")
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Descendre")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Supprimer")
        }
    }
}
