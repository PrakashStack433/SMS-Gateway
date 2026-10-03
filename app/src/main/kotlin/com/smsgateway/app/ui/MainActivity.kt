package com.smsgateway.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.refresh(this)
        }

    private var askedForPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GatewayTheme {
                MainScreen(vm, onRequestPermissions = { permissionLauncher.launch(vm.requiredPermissions().toTypedArray()) })
                LaunchedEffect(Unit) {
                    vm.refresh(this@MainActivity)
                    if (!askedForPermissions && vm.missingPermissions.isNotEmpty()) {
                        askedForPermissions = true
                        permissionLauncher.launch(vm.missingPermissions.toTypedArray())
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.ensureServiceRunning(this)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh(this)
    }
}

@Composable
private fun MainScreen(vm: MainViewModel, onRequestPermissions: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Server" to "◉", "History" to "☰", "Settings" to "⚙")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, glyph) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(glyph) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> ServerScreen(vm, onRequestPermissions, modifier)
            1 -> HistoryScreen(vm, modifier)
            else -> SettingsScreen(vm, modifier)
        }
    }
}
