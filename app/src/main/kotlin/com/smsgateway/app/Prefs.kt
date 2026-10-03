package com.smsgateway.app

import android.content.Context
import java.security.SecureRandom

/** Thin wrapper over SharedPreferences. The API key is never logged. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE)

    /** 32 random bytes, hex encoded (64 chars). Created on first call. */
    @Synchronized
    fun apiKey(): String {
        sp.getString(K_API_KEY, null)?.let { return it }
        return regenerateApiKey()
    }

    @Synchronized
    fun regenerateApiKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = bytes.joinToString("") { "%02x".format(it) }
        sp.edit().putString(K_API_KEY, key).apply()
        return key
    }

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var serverEnabled: Boolean
        get() = sp.getBoolean("server_enabled", false)
        set(v) = sp.edit().putBoolean("server_enabled", v).apply()

    var rateLimitEnabled: Boolean
        get() = sp.getBoolean("rate_limit_enabled", true)
        set(v) = sp.edit().putBoolean("rate_limit_enabled", v).apply()

    var rateLimitPerMinute: Int
        get() = sp.getInt("rate_limit_per_minute", 10)
        set(v) = sp.edit().putInt("rate_limit_per_minute", v).apply()

    var rateLimitPerHour: Int
        get() = sp.getInt("rate_limit_per_hour", 100)
        set(v) = sp.edit().putInt("rate_limit_per_hour", v).apply()

    var autoCleanup: Boolean
        get() = sp.getBoolean("auto_cleanup", true)
        set(v) = sp.edit().putBoolean("auto_cleanup", v).apply()

    var retentionDays: Int
        get() = sp.getInt("retention_days", 30)
        set(v) = sp.edit().putInt("retention_days", v).apply()

    var notificationEnabled: Boolean
        get() = sp.getBoolean("notification_enabled", true)
        set(v) = sp.edit().putBoolean("notification_enabled", v).apply()

    var externalDomainEnabled: Boolean
        get() = sp.getBoolean("external_domain_enabled", false)
        set(v) = sp.edit().putBoolean("external_domain_enabled", v).apply()

    var externalDomain: String
        get() = sp.getString("external_domain", "") ?: ""
        set(v) = sp.edit().putString("external_domain", v).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        private const val K_API_KEY = "api_key"
    }
}
