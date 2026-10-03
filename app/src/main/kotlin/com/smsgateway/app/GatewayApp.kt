package com.smsgateway.app

import android.app.Application
import android.content.Context
import com.smsgateway.app.data.AppDatabase
import com.smsgateway.app.server.RateLimiter
import com.smsgateway.app.service.NotificationHelper
import com.smsgateway.app.sms.SmsSender

class GatewayApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var smsSender: SmsSender
        private set
    val rateLimiter = RateLimiter()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.apiKey() // generates the key on first launch
        db = AppDatabase.build(this)
        smsSender = SmsSender(this, db.smsDao())
        NotificationHelper.createChannels(this)
    }
}

val Context.gatewayApp: GatewayApp
    get() = applicationContext as GatewayApp
