package com.smsgateway.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.smsgateway.app.gatewayApp
import com.smsgateway.app.server.GatewayServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service that owns the embedded HTTP server. */
class GatewayService : Service() {

    private var server: GatewayServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var maintenance: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us after the process died:
        // only come back if the user still wants the server on.
        if (intent == null && !gatewayApp.prefs.serverEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.ID_SERVICE,
                NotificationHelper.serviceNotification(this, "Starting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            fail("Could not start foreground service: ${e.message}")
            return START_NOT_STICKY
        }
        if (!startServer()) return START_NOT_STICKY

        if (maintenance?.isActive != true) {
            maintenance = scope.launch {
                while (isActive) {
                    runMaintenance()
                    delay(10 * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun startServer(): Boolean {
        stopServer()
        val app = gatewayApp
        val port = app.prefs.port
        return try {
            val s = GatewayServer(app, port)
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = s
            ServerState.status.value = ServerStatus(running = true, port = port, error = null)
            val ip = NetUtil.localIpv4Addresses().firstOrNull()
            NotificationHelper.updateService(
                this,
                if (ip != null) "Listening on http://$ip:$port" else "Listening on port $port"
            )
            true
        } catch (e: Exception) {
            fail("Cannot listen on port $port: ${e.message}")
            false
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun fail(message: String) {
        ServerState.status.value = ServerStatus(running = false, port = 0, error = message)
        gatewayApp.prefs.serverEnabled = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runMaintenance() {
        try {
            val app = gatewayApp
            val dao = app.db.smsDao()
            val now = System.currentTimeMillis()
            dao.markStalePendingUnknown(now - 10 * 60_000L)
            if (app.prefs.autoCleanup) {
                dao.deleteOlderThan(now - app.prefs.retentionDays * 86_400_000L)
            }
        } catch (_: Exception) {
            // best effort; retried on the next cycle
        }
    }

    /**
     * Android 15+ caps dataSync foreground services at ~6 h per 24 h and calls this when the
     * budget runs out; the service must stop promptly. The user can restart it by opening the app
     * (serverEnabled stays true, MainActivity restarts the service on start).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        NotificationHelper.notifyTimeout(this)
        stopServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        ServerState.status.value = ServerState.status.value.copy(running = false, port = 0)
        super.onDestroy()
    }
}
