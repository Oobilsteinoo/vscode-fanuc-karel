package com.autotasker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.autotasker.core.BtDevices
import com.autotasker.core.DeviceState
import com.autotasker.core.Tethering
import com.autotasker.data.Store
import com.autotasker.work.WatchManager

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("BatteryLife")
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh++ }

    val cfg by Store.config.collectAsState()
    val watchNeeded = remember(refresh, cfg) { WatchManager.isNeeded(context) }
    val shizukuRunning = remember(refresh) { Tethering.shizukuRunning() }
    val shizukuGranted = remember(refresh) { Tethering.shizukuGranted() }
    val overlay = remember(refresh) { DeviceState.canDrawOverlays(context) }
    val batteryFree = remember(refresh) { DeviceState.isIgnoringBatteryOptimizations(context) }
    val btOk = remember(refresh) { BtDevices.hasPermission(context) }
    val notifOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages & autorisations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
            PermissionCard(
                title = "Affichage par-dessus les autres applis",
                granted = overlay,
                required = true,
                explanation = "Depuis Android 10, une appli en arrière-plan n'a pas le droit " +
                    "d'ouvrir une autre application. Cette autorisation est la seule exemption " +
                    "officielle : sans elle, les routines se déclencheront mais rien ne s'ouvrira.",
                action = "Autoriser"
            ) {
                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            }

            PermissionCard(
                title = "Ignorer l'optimisation de la batterie",
                granted = batteryFree,
                required = true,
                explanation = "Empêche Android de mettre l'appli en veille profonde et de retarder " +
                    "ses réveils. L'appli ne consomme rien tant qu'aucun événement ne survient : " +
                    "cette exemption ne coûte pas de batterie en elle-même.",
                action = "Autoriser"
            ) {
                settingsLauncher.launch(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            }

            PermissionCard(
                title = "Appareils à proximité (Bluetooth)",
                granted = btOk,
                required = true,
                explanation = "Nécessaire pour lire le nom des appareils appairés et savoir " +
                    "lequel est connecté.",
                action = "Autoriser"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }

            PermissionCard(
                title = "Notifications",
                granted = notifOk,
                required = false,
                explanation = "Sert uniquement à afficher la notification silencieuse pendant " +
                    "l'exécution d'une séquence (imposée par Android pour un service de premier plan).",
                action = "Autoriser"
            ) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            SectionCard(
                title = "Partage de connexion (Shizuku)",
                subtitle = when {
                    shizukuGranted -> "Prêt"
                    shizukuRunning -> "Shizuku lancé, autorisation manquante"
                    else -> "Shizuku n'est pas lancé"
                }
            ) {
                StatusRow(
                    "Shizuku",
                    when {
                        shizukuGranted -> "autorisé"
                        shizukuRunning -> "lancé, non autorisé"
                        else -> "non lancé"
                    },
                    shizukuGranted
                )
                Text(
                    "Aucune API publique ne permet de basculer le partage de connexion : elle " +
                        "exige une permission réservée au système. Shizuku permet d'exécuter " +
                        "l'appel sous l'identité du shell, qui la détient — c'est le même service " +
                        "que celui piloté par le bouton des réglages.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!shizukuRunning) {
                    Text(
                        "Installez Shizuku puis lancez-le. Sans root, il doit être relancé après " +
                            "chaque redémarrage, sauf si vous activez son démarrage automatique " +
                            "par débogage sans fil.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = {
                        settingsLauncher.launch(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                        )
                    }) { Text("Installer Shizuku") }
                } else if (!shizukuGranted) {
                    Button(onClick = {
                        Tethering.requestPermission()
                        refresh++
                    }) { Text("Autoriser AutoTasker dans Shizuku") }
                }
                Text(
                    "Les vérifications d'éligibilité de votre opérateur continuent de " +
                        "s'appliquer, comme lorsque vous appuyez sur le bouton vous-même.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(
                title = "Surveillance du chargeur",
                subtitle = if (watchNeeded) "Active" else "Inutile avec vos règles actuelles"
            ) {
                Text(
                    "Android n'autorise pas une application à être réveillée par le branchement " +
                        "du chargeur via une simple déclaration : ces diffusions ne font pas partie " +
                        "des exceptions du manifeste depuis Android 8. Le seul moyen de les recevoir " +
                        "est de garder un service à l'écoute, d'où la notification permanente.",
                    style = MaterialTheme.typography.bodyMedium
                )
                SwitchRow(
                    "Autoriser la surveillance du chargeur",
                    cfg.powerWatchEnabled,
                    "Le service ne démarre que si une règle utilise un déclencheur lié à la charge. " +
                        "Les déclencheurs Bluetooth fonctionnent sans lui."
                ) { value ->
                    Store.update { it.copy(powerWatchEnabled = value) }
                    WatchManager.sync(context)
                    refresh++
                }
                Text(
                    "La notification peut être masquée sans désactiver la surveillance : appui long " +
                        "dessus → catégorie « Surveillance du chargeur » → désactiver.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(
                title = "Journal",
                subtitle = "Diagnostic"
            ) {
                SwitchRow(
                    "Journal détaillé",
                    cfg.verboseLog,
                    "Consigne aussi les réveils sans effet. Indispensable pour comprendre pourquoi " +
                        "une règle ne part pas, bruyant une fois tout réglé."
                ) { value ->
                    Store.update { it.copy(verboseLog = value) }
                    refresh++
                }
            }

            SectionCard(
                title = "Démarrage automatique (constructeurs)",
                subtitle = "Xiaomi, Huawei, Oppo, OnePlus, Samsung…"
            ) {
                Text(
                    "Ces marques ajoutent leur propre gestionnaire qui tue les applis en arrière-plan. " +
                        "Il faut y autoriser AutoTasker à démarrer automatiquement et le passer en " +
                        "« pas de restriction » / « non optimisé », sans quoi les déclencheurs " +
                        "Bluetooth et charge ne seront jamais reçus.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = {
                    settingsLauncher.launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName)
                        )
                    )
                }) { Text("Ouvrir la fiche de l'application") }
            }

            SectionCard(
                title = "Consommation",
                subtitle = "Ce que fait réellement l'appli quand vous ne l'utilisez pas"
            ) {
                Text(
                    "• Aucun timer, aucune boucle de sondage.\n" +
                        "• Le processus est réveillé par les diffusions Bluetooth d'Android, " +
                        "et par le service de surveillance pour le chargeur.\n" +
                        "• Chaque réveil dure quelques millisecondes (lecture de l'état, comparaison, " +
                        "puis extinction).\n" +
                        "• Le seul travail récurrent est la règle « périodique », si vous l'activez : " +
                        "elle passe par WorkManager, qui regroupe les réveils et respecte le mode Doze.\n" +
                        "• Un service de premier plan n'existe que pendant l'exécution d'une séquence.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    required: Boolean,
    explanation: String,
    action: String,
    onClick: () -> Unit
) {
    SectionCard(title = title) {
        StatusRow(
            if (required) "Obligatoire" else "Recommandée",
            if (granted) "accordée" else "manquante",
            granted
        )
        Text(explanation, style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            Button(onClick = onClick) { Text(action) }
        }
    }
}
