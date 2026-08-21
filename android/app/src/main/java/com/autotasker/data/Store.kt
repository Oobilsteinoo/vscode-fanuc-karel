package com.autotasker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Stockage unique de l'appli : un blob JSON dans les SharedPreferences.
 * Volontairement synchrone et minuscule — il est lu depuis des BroadcastReceivers
 * où l'on ne dispose que de quelques millisecondes.
 */
object Store {

    private const val PREFS = "autotasker"
    private const val KEY_CONFIG = "config"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(KEY_CONFIG, null)
        if (raw == null) {
            val initial = Defaults.initialConfig()
            _config.value = initial
            persist(initial)
        } else {
            _config.value = runCatching { json.decodeFromString(Config.serializer(), raw) }
                .getOrElse { Defaults.initialConfig() }
        }
    }

    @Synchronized
    fun update(transform: (Config) -> Config) {
        val next = transform(_config.value)
        _config.value = next
        persist(next)
    }

    fun updateRule(id: String, transform: (Rule) -> Rule) = update { cfg ->
        cfg.copy(rules = cfg.rules.map { if (it.id == id) transform(it) else it })
    }

    fun rule(id: String?): Rule? = _config.value.rules.firstOrNull { it.id == id }

    private fun persist(c: Config) {
        prefs?.edit()?.putString(KEY_CONFIG, json.encodeToString(Config.serializer(), c))?.apply()
    }

    // ---- petit état transitoire (instantanés, horodatages d'exécution, appareils BT connectés) ----

    fun getString(key: String, def: String = ""): String = prefs?.getString(key, def) ?: def
    fun putString(key: String, value: String) { prefs?.edit()?.putString(key, value)?.apply() }
    fun getLong(key: String, def: Long = 0L): Long = prefs?.getLong(key, def) ?: def
    fun putLong(key: String, value: Long) { prefs?.edit()?.putLong(key, value)?.apply() }
    fun getStringSet(key: String): Set<String> = prefs?.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    fun putStringSet(key: String, value: Set<String>) {
        prefs?.edit()?.putStringSet(key, value)?.apply()
    }
}
