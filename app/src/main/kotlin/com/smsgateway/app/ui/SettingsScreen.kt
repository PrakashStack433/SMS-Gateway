package com.smsgateway.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rate limiting", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Enable rate limiting", vm.rateLimitEnabled, vm::onRateLimitEnabled)
                Text(
                    "Counts SMS sends (each message in a bulk request counts once) per API key. " +
                        "Exceeding either limit returns HTTP 429.",
                    style = MaterialTheme.typography.bodySmall
                )
                NumberField("Max per minute", vm.perMinuteText, vm::onPerMinute)
                NumberField("Max per hour", vm.perHourText, vm::onPerHour)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("History cleanup", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Auto-delete old messages", vm.autoCleanup, vm::onAutoCleanup)
                NumberField("Retention (days)", vm.retentionText, vm::onRetention)
                Text(
                    "History stores message text (including OTP codes). Shorter retention is safer.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Notifications", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Notify on SMS status changes", vm.notificationEnabled, vm::onNotificationEnabled)
                Text(
                    "The persistent “SMS Gateway” notification is always shown while the server runs.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("External domain mode", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Show external URL instead of local IP", vm.externalDomainEnabled, vm::onExternalDomainEnabled)
                OutlinedTextField(
                    value = vm.externalDomain,
                    onValueChange = vm::onExternalDomain,
                    label = { Text("https://sms.example.com") },
                    singleLine = true,
                    enabled = vm.externalDomainEnabled,
                    isError = !vm.externalDomainValid(),
                    supportingText = { if (!vm.externalDomainValid()) Text("Must start with http:// or https://") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Display only. The tunnel / reverse proxy itself is set up outside the app (see README).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reliability", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Exempt this app from battery optimisation so Android doesn't kill the server.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery settings") }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    val invalid = value.toIntOrNull()?.let { it < 1 } ?: true
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = { if (invalid) Text("Enter a number ≥ 1") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
