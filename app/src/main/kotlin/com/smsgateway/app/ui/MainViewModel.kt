package com.smsgateway.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.smsgateway.app.GatewayApp
import com.smsgateway.app.data.SmsEntity
import com.smsgateway.app.service.GatewayService
import com.smsgateway.app.service.NetUtil
import com.smsgateway.app.service.ServerState
import com.smsgateway.app.service.ServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = application as GatewayApp
    private val prefs = gateway.prefs
    private val dao = gateway.db.smsDao()

    val serverStatus: StateFlow<ServerStatus> = ServerState.status

    // ------------------------------------------------------------ Server tab
    var serverEnabled by mutableStateOf(prefs.serverEnabled)
        private set
    var portText by mutableStateOf(prefs.port.toString())
        private set
    var portError by mutableStateOf<String?>(null)
        private set
    var apiKey by mutableStateOf(prefs.apiKey())
        private set
    var localIps by mutableStateOf<List<String>>(emptyList())
        private set
    var missingPermissions by mutableStateOf<List<String>>(emptyList())
        private set

    var testPhone by mutableStateOf("+91")
    var testMessage by mutableStateOf("Test message from SMS Gateway")
    var testBusy by mutableStateOf(false)
        private set
    var testResult by mutableStateOf<String?>(null)
        private set

    // ---------------------------------------------------------- Settings tab
    var rateLimitEnabled by mutableStateOf(prefs.rateLimitEnabled)
        private set
    var perMinuteText by mutableStateOf(prefs.rateLimitPerMinute.toString())
        private set
    var perHourText by mutableStateOf(prefs.rateLimitPerHour.toString())
        private set
    var autoCleanup by mutableStateOf(prefs.autoCleanup)
        private set
    var retentionText by mutableStateOf(prefs.retentionDays.toString())
        private set
    var notificationEnabled by mutableStateOf(prefs.notificationEnabled)
        private set
    var externalDomainEnabled by mutableStateOf(prefs.externalDomainEnabled)
        private set
    var externalDomain by mutableStateOf(prefs.externalDomain)
        private set

    // ----------------------------------------------------------- History tab
    val historyFilter = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<SmsEntity>> = historyFilter
        .flatMapLatest { dao.observe(it, 500) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var historyNote by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            serverStatus.collect { if (it.error != null) serverEnabled = false }
        }
    }

    // ------------------------------------------------------------- lifecycle
    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun refresh(context: Context) {
        missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        localIps = NetUtil.localIpv4Addresses()
        serverEnabled = prefs.serverEnabled
    }

    /** Called from Activity.onStart: bring the service back if it should be running (e.g. after the Android 15 timeout). */
    fun ensureServiceRunning(context: Context) {
        if (prefs.serverEnabled && !ServerState.status.value.running) startService(context)
    }

    private fun startService(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
    }

    // ------------------------------------------------------------ server tab
    private fun validPort(): Int? = portText.toIntOrNull()?.takeIf { it in 1024..65535 }

    fun onPortChange(text: String) {
        portText = text.filter { it.isDigit() }.take(5)
        portError = if (validPort() == null) "Enter a port between 1024 and 65535" else null
    }

    fun setServerEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            val port = validPort()
            if (port == null) {
                portError = "Enter a port between 1024 and 65535"
                return
            }
            prefs.port = port
            prefs.serverEnabled = true
            serverEnabled = true
            ServerState.status.value = ServerState.status.value.copy(error = null)
            startService(context)
        } else {
            prefs.serverEnabled = false
            serverEnabled = false
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }

    /** Saves the port; restarts the server if it is running so the new port takes effect. */
    fun applyPort(context: Context) {
        val port = validPort()
        if (port == null) {
            portError = "Enter a port between 1024 and 65535"
            return
        }
        prefs.port = port
        if (prefs.serverEnabled) startService(context) // onStartCommand restarts the listener
    }

    fun regenerateApiKey() {
        apiKey = prefs.regenerateApiKey()
    }

    fun baseUrls(): List<String> {
        if (externalDomainEnabled && externalDomain.isNotBlank() && externalDomainValid()) {
            return listOf(externalDomain.trimEnd('/') + "/api")
        }
        val port = validPort() ?: prefs.port
        return localIps.map { "http://$it:$port/api" }
    }

    /** Sends through this device's own HTTP API so the whole stack is exercised. */
    fun sendTest() {
        if (testBusy) return
        val phone = testPhone.trim()
        val message = testMessage
        val port = ServerState.status.value.port.takeIf { it > 0 } ?: prefs.port
        val key = prefs.apiKey()
        testBusy = true
        testResult = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val body = JsonObject().apply {
                    addProperty("phone_number", phone)
                    addProperty("message", message)
                }.toString()
                val conn = URL("http://127.0.0.1:$port/api/send").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-API-Key", key)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    "HTTP $code\n" + prettyJson(text)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                "Request failed: ${e.message ?: e.javaClass.simpleName}\n(Is the server switched on?)"
            }
            testResult = result
            testBusy = false
        }
    }

    private fun prettyJson(text: String): String = try {
        GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(text))
    } catch (_: Exception) {
        text
    }

    // ---------------------------------------------------------- settings tab
    fun onRateLimitEnabled(v: Boolean) {
        rateLimitEnabled = v
        prefs.rateLimitEnabled = v
    }

    fun onPerMinute(t: String) {
        perMinuteText = t.filter { it.isDigit() }.take(6)
        perMinuteText.toIntOrNull()?.takeIf { it >= 1 }?.let { prefs.rateLimitPerMinute = it }
    }

    fun onPerHour(t: String) {
        perHourText = t.filter { it.isDigit() }.take(6)
        perHourText.toIntOrNull()?.takeIf { it >= 1 }?.let { prefs.rateLimitPerHour = it }
    }

    fun onAutoCleanup(v: Boolean) {
        autoCleanup = v
        prefs.autoCleanup = v
    }

    fun onRetention(t: String) {
        retentionText = t.filter { it.isDigit() }.take(4)
        retentionText.toIntOrNull()?.takeIf { it >= 1 }?.let { prefs.retentionDays = it }
    }

    fun onNotificationEnabled(v: Boolean) {
        notificationEnabled = v
        prefs.notificationEnabled = v
    }

    fun onExternalDomainEnabled(v: Boolean) {
        externalDomainEnabled = v
        prefs.externalDomainEnabled = v
    }

    fun externalDomainValid(): Boolean =
        externalDomain.isBlank() || Regex("^https?://[^\\s/]+(/\\S*)?$").matches(externalDomain)

    fun onExternalDomain(t: String) {
        externalDomain = t.trim()
        if (externalDomainValid()) prefs.externalDomain = externalDomain
    }

    // ------------------------------------------------------------ history tab
    fun purgeOld() {
        viewModelScope.launch(Dispatchers.IO) {
            val days = prefs.retentionDays
            val n = dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L)
            historyNote = "Deleted $n message(s) older than $days day(s)"
        }
    }

    fun purgeAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = dao.deleteAll()
            historyNote = "Deleted $n message(s)"
        }
    }

    fun clearHistoryNote() {
        historyNote = null
    }
}
