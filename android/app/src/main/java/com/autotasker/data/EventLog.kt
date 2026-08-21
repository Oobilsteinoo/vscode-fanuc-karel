package com.autotasker.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Journal circulaire (200 lignes) pour comprendre ce qui s'est déclenché — et surtout ce qui ne s'est pas déclenché. */
object EventLog {

    private const val KEY = "log"
    private const val MAX = 200

    @Serializable
    data class Entry(val time: Long, val text: String) {
        fun formatted(): String =
            SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE).format(Date(time)) + "  " + text
    }

    private val serializer = ListSerializer(Entry.serializer())
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val raw = Store.getString(KEY, "[]")
        _entries.value = runCatching { Store.json.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    @Synchronized
    fun add(text: String) {
        load()
        val list = (listOf(Entry(System.currentTimeMillis(), text)) + _entries.value).take(MAX)
        _entries.value = list
        Store.putString(KEY, Store.json.encodeToString(serializer, list))
    }

    @Synchronized
    fun clear() {
        loaded = true
        _entries.value = emptyList()
        Store.putString(KEY, "[]")
    }
}
