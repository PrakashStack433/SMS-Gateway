package com.smsgateway.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SmsStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
    const val UNKNOWN = "UNKNOWN"
    val ALL = listOf(PENDING, SENT, DELIVERED, FAILED, UNKNOWN)
}

@Entity(
    tableName = "sms",
    indices = [Index("request_id"), Index("timestamp"), Index("status")]
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "phone_number") val phoneNumber: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "request_id") val requestId: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "delivery_timestamp") val deliveryTimestamp: Long?,
    @ColumnInfo(name = "sim_subscription_id") val simSubscriptionId: Int?
)
