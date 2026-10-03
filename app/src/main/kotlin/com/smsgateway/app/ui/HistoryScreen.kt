package com.smsgateway.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsgateway.app.data.SmsEntity
import com.smsgateway.app.data.SmsStatus
import java.text.DateFormat
import java.util.Date

private fun statusColor(status: String): Color = when (status) {
    SmsStatus.DELIVERED -> Color(0xFF2E7D32)
    SmsStatus.SENT -> Color(0xFF1565C0)
    SmsStatus.PENDING -> Color(0xFFEF6C00)
    SmsStatus.FAILED -> Color(0xFFC62828)
    else -> Color(0xFF616161)
}

private fun formatTime(ts: Long?): String =
    if (ts == null) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ts))

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rows by vm.history.collectAsState()
    val filter by vm.historyFilter.collectAsState()
    var selected by remember { mutableStateOf<SmsEntity?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = filter == null, onClick = { vm.historyFilter.value = null }, label = { Text("All") })
            SmsStatus.ALL.forEach { s ->
                FilterChip(selected = filter == s, onClick = { vm.historyFilter.value = s }, label = { Text(s) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmPurge = true }) { Text("Purge old") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all") }
        }
        vm.historyNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (rows.isEmpty()) {
            Text("No messages yet.", modifier = Modifier.padding(top = 16.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { selected = r }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.phoneNumber, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Badge(r.status, statusColor(r.status))
                            }
                            Text(r.message, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(formatTime(r.timestamp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    selected?.let { r ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("SMS #${r.id}") },
            text = {
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("To: ${r.phoneNumber}")
                        Text("Status: ${r.status}")
                        Text("Created: ${formatTime(r.timestamp)}")
                        Text("Delivered: ${formatTime(r.deliveryTimestamp)}")
                        Text("Request ID: ${r.requestId ?: "—"}")
                        Text("SIM subscription: ${r.simSubscriptionId?.toString() ?: "default"}")
                        r.errorMessage?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                        Text("Message:\n${r.message}")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Purge old messages?") },
            text = { Text("Deletes messages older than the retention period set in Settings.") },
            confirmButton = { TextButton(onClick = { vm.purgeOld(); confirmPurge = false }) { Text("Purge") } },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all history?") },
            text = { Text("This removes every stored message, including OTP texts.") },
            confirmButton = { TextButton(onClick = { vm.purgeAll(); confirmClear = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}
