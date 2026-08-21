package com.autotasker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.autotasker.core.BtDevices
import com.autotasker.data.EventLog
import com.autotasker.data.Store
import com.autotasker.ui.AutoTaskerTheme
import com.autotasker.ui.HomeScreen
import com.autotasker.ui.LogScreen
import com.autotasker.ui.RuleEditorScreen
import com.autotasker.ui.SetupScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Store.init(this)
        EventLog.load()
        setContent {
            AutoTaskerTheme {
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Resynchronise l'état Bluetooth quand l'utilisateur ouvre l'appli.
        lifecycleScope.launch {
            runCatching { BtDevices.refreshFromProfiles(applicationContext) }
        }
    }
}

@Composable
private fun AppRoot() {
    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    when (route) {
        ROUTE_EDITOR -> RuleEditorScreen(ruleId = editingId, onBack = { route = ROUTE_HOME })
        ROUTE_LOGS -> LogScreen(onBack = { route = ROUTE_HOME })
        ROUTE_SETUP -> SetupScreen(onBack = { route = ROUTE_HOME })
        else -> HomeScreen(
            onEdit = { id ->
                editingId = id
                route = ROUTE_EDITOR
            },
            onLogs = { route = ROUTE_LOGS },
            onSetup = { route = ROUTE_SETUP }
        )
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_EDITOR = "editor"
private const val ROUTE_LOGS = "logs"
private const val ROUTE_SETUP = "setup"
