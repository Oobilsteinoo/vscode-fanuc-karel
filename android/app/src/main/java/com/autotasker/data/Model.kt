package com.autotasker.data

import kotlinx.serialization.Serializable

/** Ce qui déclenche l'évaluation d'une règle. Tous ces événements viennent de l'OS : aucun sondage. */
enum class TriggerType {
    /** Charge sans fil ACTIVE **et** appareil Bluetooth « voiture » connecté (front montant). */
    CAR_MODE_START,

    /** L'une des deux conditions ci-dessus retombe. */
    CAR_MODE_END,

    /** Un appareil Bluetooth vient de se connecter. */
    BT_CONNECTED,

    /** Un appareil Bluetooth vient de se déconnecter. */
    BT_DISCONNECTED,

    /** Début de charge sans fil (quel que soit le Bluetooth). */
    WIRELESS_CHARGING_START,

    /** Fin de charge sans fil. */
    WIRELESS_CHARGING_END,

    /** Début de charge (filaire ou sans fil). */
    CHARGING_START,

    /** Fin de charge. */
    CHARGING_END,

    /** Le Wi-Fi vient d'être perdu (typiquement : on quitte la maison). */
    WIFI_LOST,

    /** Le Wi-Fi vient d'être rejoint (typiquement : on rentre à la maison). */
    WIFI_JOINED,

    /** Répétition périodique (WorkManager, minimum 15 min, respecte le Doze). */
    PERIODIC,

    /** Au démarrage du téléphone / après mise à jour de l'appli. */
    BOOT,

    /** Uniquement manuel (bouton « Lancer »). */
    MANUAL;

    val label: String
        get() = when (this) {
            CAR_MODE_START -> "Charge sans fil + Bluetooth voiture"
            CAR_MODE_END -> "Fin charge sans fil / Bluetooth voiture"
            BT_CONNECTED -> "Bluetooth connecté"
            BT_DISCONNECTED -> "Bluetooth déconnecté"
            WIRELESS_CHARGING_START -> "Début de charge sans fil"
            WIRELESS_CHARGING_END -> "Fin de charge sans fil"
            CHARGING_START -> "Début de charge"
            CHARGING_END -> "Fin de charge"
            WIFI_LOST -> "Wi-Fi perdu (sortie de la maison)"
            WIFI_JOINED -> "Wi-Fi rejoint (retour à la maison)"
            PERIODIC -> "Périodique"
            BOOT -> "Démarrage du téléphone"
            MANUAL -> "Manuel uniquement"
        }
}

@Serializable
data class Trigger(
    val type: TriggerType = TriggerType.MANUAL,
    /** Adresse MAC ciblée pour BT_CONNECTED / BT_DISCONNECTED. Vide = n'importe quel appareil « voiture ». */
    val btAddress: String = "",
    /** Période en minutes pour PERIODIC. Minimum imposé par Android : 15. */
    val periodMinutes: Int = 30,
    /** Filtre SSID pour WIFI_LOST / WIFI_JOINED. Vide = n'importe quel Wi-Fi. */
    val ssid: String = ""
)

/** Filtres supplémentaires évalués au moment du déclenchement (tous doivent être vrais). */
@Serializable
data class Conditions(
    val requireWirelessCharging: Boolean = false,
    val requireAnyCharging: Boolean = false,
    val requireNotCharging: Boolean = false,
    val requireCarBluetooth: Boolean = false,
    val requireNoCarBluetooth: Boolean = false,
    val requireScreenOff: Boolean = false,
    val requireScreenOn: Boolean = false,
    val requireWifi: Boolean = false,
    val requireNoWifi: Boolean = false,
    /** Plage horaire (heures pleines, 0-23). -1 = pas de contrainte. Gère le passage de minuit. */
    val startHour: Int = -1,
    val endHour: Int = -1
) {
    val isEmpty: Boolean
        get() = !requireWirelessCharging && !requireAnyCharging && !requireNotCharging &&
            !requireCarBluetooth && !requireNoCarBluetooth &&
            !requireScreenOff && !requireScreenOn && !requireWifi && !requireNoWifi &&
            startHour < 0 && endHour < 0
}

enum class ActionType {
    /** Ouvre l'application et la laisse au premier plan. */
    LAUNCH_APP,

    /** « Réveille » l'application : l'ouvre, attend, puis revient à l'écran d'accueil. */
    WAKE_APP,

    /** Attente. */
    DELAY,

    /** Retour à l'écran d'accueil. */
    GO_HOME,

    /** Ouvre une URL (ou un lien d'appli type `dscam://`). */
    OPEN_URL,

    /**
     * Ferme une application : demande à Android de tuer ses processus d'arrière-plan.
     * Voir les limites dans [Action.describe] et le README — sans effet sur Android 14+.
     */
    CLOSE_APP,

    /** Envoie la touche média « Lecture » (reprend Spotify / Waze audio, etc.). */
    MEDIA_PLAY,

    /** Envoie la touche média « Pause » (coupe la musique en sortant de voiture). */
    MEDIA_PAUSE,

    /**
     * Ouvre l'écran système du partage de connexion.
     *
     * Ce n'est volontairement **pas** un interrupteur : activer le partage de connexion par
     * programme exige `TETHER_PRIVILEGED`, une permission `signature|privileged` hors de portée
     * d'une appli installée manuellement. L'action amène au bon écran, le basculement reste
     * un geste manuel.
     */
    OPEN_TETHER_SETTINGS;

    val label: String
        get() = when (this) {
            LAUNCH_APP -> "Lancer une application"
            WAKE_APP -> "Réveiller une application"
            CLOSE_APP -> "Fermer une application"
            DELAY -> "Attendre"
            GO_HOME -> "Retour à l'accueil"
            OPEN_URL -> "Ouvrir un lien"
            MEDIA_PLAY -> "Lecture média"
            MEDIA_PAUSE -> "Pause média"
            OPEN_TETHER_SETTINGS -> "Ouvrir le partage de connexion"
        }

    /** L'action porte sur une application à choisir. */
    val needsApp: Boolean
        get() = this == LAUNCH_APP || this == WAKE_APP || this == CLOSE_APP
}

@Serializable
data class Action(
    val type: ActionType = ActionType.DELAY,
    val packageName: String = "",
    val label: String = "",
    /** Attente pour DELAY, durée d'affichage pour WAKE_APP. */
    val delayMs: Long = 2000,
    val url: String = ""
) {
    fun describe(): String = when (type) {
        ActionType.LAUNCH_APP -> "Lancer ${label.ifBlank { packageName }}"
        ActionType.WAKE_APP -> "Réveiller ${label.ifBlank { packageName }} (${delayMs / 1000}s)"
        ActionType.CLOSE_APP -> "Fermer ${label.ifBlank { packageName }}"
        ActionType.DELAY -> "Attendre ${delayMs / 1000.0} s"
        ActionType.GO_HOME -> "Retour à l'accueil"
        ActionType.OPEN_URL -> "Ouvrir $url"
        ActionType.MEDIA_PLAY -> "Lecture média"
        ActionType.MEDIA_PAUSE -> "Pause média"
        ActionType.OPEN_TETHER_SETTINGS -> "Ouvrir les réglages du partage de connexion"
    }
}

@Serializable
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger(),
    val conditions: Conditions = Conditions(),
    val actions: List<Action> = emptyList(),
    /** Délai minimal entre deux exécutions, en minutes. 0 = pas de limite. */
    val cooldownMinutes: Int = 0
)

@Serializable
data class BtDevice(val address: String, val name: String)

@Serializable
data class Config(
    val rules: List<Rule> = emptyList(),
    /** Appareils Bluetooth considérés comme « la voiture ». */
    val carDevices: List<BtDevice> = emptyList(),
    /**
     * Autorise le service de surveillance du chargeur. Il ne démarre que si une règle activée
     * utilise un déclencheur lié à la charge — les configurations purement Bluetooth n'en ont
     * pas besoin.
     */
    val powerWatchEnabled: Boolean = true,
    /** Journalise aussi les réveils sans effet. Utile pour diagnostiquer, bruyant à l'usage. */
    val verboseLog: Boolean = true,
    val firstRunDone: Boolean = false
)
