package com.autotasker.data

import java.util.UUID

/** Règles pré-remplies proposées au premier lancement : il ne reste qu'à choisir les applis. */
object Defaults {

    fun newId(): String = UUID.randomUUID().toString()

    fun initialConfig(): Config = Config(
        rules = listOf(carRoutine(), wakeOnCar(), wakePeriodic().copy(enabled = false)),
        carDevices = emptyList(),
        firstRunDone = false
    )

    /** Tâche n°1 : charge sans fil + Bluetooth voiture -> séquence de lancements. */
    fun carRoutine(): Rule = Rule(
        id = newId(),
        name = "Routine voiture",
        enabled = true,
        trigger = Trigger(type = TriggerType.CAR_MODE_START),
        conditions = Conditions(),
        actions = emptyList(),
        cooldownMinutes = 2
    )

    /** Tâche n°2 : réveiller les applis « fantômes » quand on prend la voiture (= on quitte la maison). */
    fun wakeOnCar(): Rule = Rule(
        id = newId(),
        name = "Réveil des applis (départ en voiture)",
        enabled = true,
        trigger = Trigger(type = TriggerType.BT_CONNECTED),
        conditions = Conditions(),
        actions = emptyList(),
        cooldownMinutes = 5
    )

    /** Tâche n°2 bis : filet de sécurité périodique, écran éteint uniquement. */
    fun wakePeriodic(): Rule = Rule(
        id = newId(),
        name = "Réveil périodique des applis",
        enabled = true,
        trigger = Trigger(type = TriggerType.PERIODIC, periodMinutes = 30),
        conditions = Conditions(requireScreenOff = true),
        actions = emptyList(),
        cooldownMinutes = 25
    )

    fun blank(): Rule = Rule(id = newId(), name = "Nouvelle règle")
}
