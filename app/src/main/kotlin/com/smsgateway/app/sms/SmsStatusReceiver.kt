package com.smsgateway.app.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.smsgateway.app.data.SmsStatus
import com.smsgateway.app.gatewayApp
import com.smsgateway.app.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives the SENT / DELIVERED PendingIntents fired by the telephony stack. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(SmsSender.EXTRA_SMS_ID, -1L)
        if (id < 0) return
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
        val action = intent.action
        val code = resultCode
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        val errorCode = intent.getIntExtra("errorCode", -1)

        val app = context.gatewayApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.db.smsDao()
                when (action) {
                    SmsSender.ACTION_SENT -> {
                        if (code != Activity.RESULT_OK) {
                            val reason = describeSendError(code, errorCode)
                            if (dao.markFailedIfActive(id, reason) > 0) postEvent(context, id, SmsStatus.FAILED)
                        } else if (part == parts - 1) {
                            // Last part accepted by the radio => whole message is SENT.
                            if (dao.markSentIfPending(id) > 0) postEvent(context, id, SmsStatus.SENT)
                        }
                    }
                    SmsSender.ACTION_DELIVERED -> {
                        when (deliveryOutcome(code, pdu, format)) {
                            true -> if (part == parts - 1 &&
                                dao.markDelivered(id, System.currentTimeMillis()) > 0
                            ) postEvent(context, id, SmsStatus.DELIVERED)
                            false -> if (dao.markFailedIfActive(id, "Delivery failed (carrier report)") > 0) {
                                postEvent(context, id, SmsStatus.FAILED)
                            }
                            null -> Unit // still pending at the SMSC
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** true = delivered, false = permanently failed, null = not final yet. */
    private fun deliveryOutcome(code: Int, pdu: ByteArray?, format: String?): Boolean? {
        if (pdu != null) {
            try {
                val status = SmsMessage.createFromPdu(pdu, format)?.status ?: return code == Activity.RESULT_OK
                return when {
                    status == 0 -> true
                    status >= 64 -> false
                    else -> null
                }
            } catch (_: Exception) {
                // fall through to result code
            }
        }
        return code == Activity.RESULT_OK
    }

    private fun describeSendError(code: Int, errorCode: Int): String {
        val base = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off (airplane mode?)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular service"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android SMS sending limit exceeded"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Short code not allowed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Short code never allowed"
            else -> "Send failed (result code $code)"
        }
        return if (errorCode >= 0) "$base (error $errorCode)" else base
    }

    private fun postEvent(context: Context, id: Long, status: String) {
        val app = context.gatewayApp
        if (!app.prefs.notificationEnabled) return
        val phone = app.db.smsDao().byId(id)?.phoneNumber ?: return
        NotificationHelper.notifyEvent(context, id, status, phone)
    }
}
