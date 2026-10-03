package com.smsgateway.app.service

import kotlinx.coroutines.flow.MutableStateFlow

data class ServerStatus(val running: Boolean = false, val port: Int = 0, val error: String? = null)

/** Process-wide server state observed by the UI. */
object ServerState {
    val status = MutableStateFlow(ServerStatus())
}
