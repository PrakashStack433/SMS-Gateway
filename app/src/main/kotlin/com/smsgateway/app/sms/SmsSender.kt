package com.smsgateway.app.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.smsgateway.app.data.SmsDao
import com.smsgateway.app.data.SmsEntity
import com.smsgateway.app.data.SmsStatus

class SmsSender(private val context: Context, private val dao: SmsDao) {

    /**
     * Persists a PENDING row, then hands the message to the radio.
     * Returns the stored record; if SmsManager rejected the message synchronously
     * the record is already FAILED with an error message.
     * Must be called off the main thread (it touches the database).
     */
    fun send(phone: String, message: String, requestId: String?, subscriptionId: Int?): SmsEntity {
        val id = dao.insert(
            SmsEntity(
                phoneNumber = phone,
                message = message,
                timestamp = System.currentTimeMillis(),
                status = SmsStatus.PENDING,
                requestId = requestId,
                errorMessage = null,
                deliveryTimestamp = null,
                simSubscriptionId = subscriptionId
            )
        )
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("SEND_SMS permission not granted on the device")
            }
            val sm = smsManager(subscriptionId)
            val parts = sm.divideMessage(message) // auto-split long messages
            val n = parts.size
            val sent = ArrayList<PendingIntent>(n)
            val delivered = ArrayList<PendingIntent>(n)
            for (i in 0 until n) {
                sent.add(statusIntent(ACTION_SENT, id, i, n, "s"))
                delivered.add(statusIntent(ACTION_DELIVERED, id, i, n, "d"))
            }
            if (n == 1) {
                sm.sendTextMessage(phone, null, parts[0], sent[0], delivered[0])
            } else {
                sm.sendMultipartTextMessage(phone, null, parts, sent, delivered)
            }
        } catch (e: Exception) {
            dao.markFailed(id, e.message ?: e.javaClass.simpleName)
        }
        return dao.byId(id)!!
    }

    private fun statusIntent(action: String, id: Long, part: Int, parts: Int, kind: String): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java)
            .setAction(action)
            // Unique data URI => distinct PendingIntent per message/part/kind (extras don't count).
            .setData(Uri.parse("smsgw://msg/$id/$part/$kind"))
            .putExtra(EXTRA_SMS_ID, id)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, parts)
        // Mutable so the system can add the delivery-report "pdu" extra (explicit intent => allowed on API 34+).
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(subscriptionId: Int?): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            return if (subscriptionId != null) base.createForSubscriptionId(subscriptionId) else base
        }
        // Default SMS subscription (the first / default SIM on dual-SIM phones).
        return if (subscriptionId != null) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getDefault()
        }
    }

    companion object {
        const val ACTION_SENT = "com.smsgateway.app.SMS_SENT"
        const val ACTION_DELIVERED = "com.smsgateway.app.SMS_DELIVERED"
        const val EXTRA_SMS_ID = "sms_id"
        const val EXTRA_PART = "part"
        const val EXTRA_PARTS = "parts"
    }
}
