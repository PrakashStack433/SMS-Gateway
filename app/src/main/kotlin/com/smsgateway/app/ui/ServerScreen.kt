package com.smsgateway.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ServerScreen(vm: MainViewModel, onRequestPermissions: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status by vm.serverStatus.collectAsState()
    var showKey by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SMS Gateway", style = MaterialTheme.typography.headlineSmall)

        if (vm.missingPermissions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        vm.missingPermissions.joinToString(", ") { it.substringAfterLast('.') },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "If Android greys the SMS permissions out (sideloaded APK on Android 13+), open " +
                            "App info → ⋮ → Allow restricted settings, then grant them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onRequestPermissions) { Text("Grant permissions") }
                }
            }
        }

        // ---- master switch + status
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Server", style = MaterialTheme.typography.titleMedium)
                        when {
                            status.running -> Badge("RUNNING on port ${status.port}", Color(0xFF2E7D32))
                            status.error != null -> Badge("ERROR", Color(0xFFC62828))
                            vm.serverEnabled -> Badge("STARTING…", Color(0xFFEF6C00))
                            else -> Badge("STOPPED", Color(0xFF616161))
                        }
                    }
                    Switch(
                        checked = vm.serverEnabled,
                        onCheckedChange = { vm.setServerEnabled(context, it) }
                    )
                }
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.portText,
                        onValueChange = vm::onPortChange,
                        label = { Text("Port (1024–65535)") },
                        singleLine = true,
                        isError = vm.portError != null,
                        supportingText = { vm.portError?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = { vm.applyPort(context) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("Apply") }
                }
            }
        }

        // ---- API key
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API key", style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(
                        if (showKey) vm.apiKey else "•".repeat(32),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.apiKey)) }) { Text("Copy") }
                    OutlinedButton(onClick = { confirmRegenerate = true }) { Text("Regenerate") }
                }
            }
        }

        // ---- addresses
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device address", style = MaterialTheme.typography.titleMedium)
                if (vm.localIps.isEmpty()) {
                    Text("No network address found. Connect to Wi-Fi or mobile data.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Local IP: " + vm.localIps.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
                Text("Base URL", style = MaterialTheme.typography.labelLarge)
                val urls = vm.baseUrls()
                if (urls.isEmpty()) {
                    Text("—")
                } else {
                    SelectionContainer {
                        Column {
                            urls.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(urls.first())) }) { Text("Copy base URL") }
                }
            }
        }

        // ---- test panel
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test send", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Calls this device's own API (POST /api/send). This sends a real SMS.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = vm.testPhone,
                    onValueChange = { vm.testPhone = it },
                    label = { Text("Phone (E.164, e.g. +919876543210)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.testMessage,
                    onValueChange = { vm.testMessage = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.sendTest() },
                    enabled = status.running && !vm.testBusy
                ) { Text(if (vm.testBusy) "Sending…" else "Send test SMS") }
                vm.testResult?.let {
                    SelectionContainer {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate API key?") },
            text = { Text("The current key stops working immediately. Update your backend with the new key.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.regenerateApiKey()
                    confirmRegenerate = false
                }) { Text("Regenerate") }
            },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
