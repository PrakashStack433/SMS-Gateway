package com.smsgateway.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsDao {
    @Insert
    fun insert(e: SmsEntity): Long

    @Query("SELECT * FROM sms WHERE id = :id")
    fun byId(id: Long): SmsEntity?

    /** Latest record for a request_id (used by /api/status). */
    @Query("SELECT * FROM sms WHERE request_id = :requestId ORDER BY id DESC LIMIT 1")
    fun byRequestId(requestId: String): SmsEntity?

    /** Latest non-failed record for a request_id (idempotency check; a FAILED send may be retried). */
    @Query("SELECT * FROM sms WHERE request_id = :requestId AND status != 'FAILED' ORDER BY id DESC LIMIT 1")
    fun activeByRequestId(requestId: String): SmsEntity?

    @Query(
        """SELECT * FROM sms
           WHERE (:status IS NULL OR status = :status)
             AND (:phone IS NULL OR phone_number = :phone)
           ORDER BY timestamp DESC, id DESC
           LIMIT :limit OFFSET :offset"""
    )
    fun history(status: String?, phone: String?, limit: Int, offset: Int): List<SmsEntity>

    @Query(
        """SELECT COUNT(*) FROM sms
           WHERE (:status IS NULL OR status = :status)
             AND (:phone IS NULL OR phone_number = :phone)"""
    )
    fun count(status: String?, phone: String?): Int

    @Query(
        """SELECT * FROM sms
           WHERE (:status IS NULL OR status = :status)
           ORDER BY timestamp DESC, id DESC
           LIMIT :limit"""
    )
    fun observe(status: String?, limit: Int): Flow<List<SmsEntity>>

    /** Immediate failure (exception while handing the message to SmsManager). */
    @Query("UPDATE sms SET status = 'FAILED', error_message = :error WHERE id = :id")
    fun markFailed(id: Long, error: String): Int

    /** Failure reported by the SENT broadcast; never overrides a final state. */
    @Query(
        """UPDATE sms SET status = 'FAILED', error_message = :error
           WHERE id = :id AND status IN ('PENDING', 'SENT', 'UNKNOWN')"""
    )
    fun markFailedIfActive(id: Long, error: String): Int

    @Query("UPDATE sms SET status = 'SENT' WHERE id = :id AND status = 'PENDING'")
    fun markSentIfPending(id: Long): Int

    @Query(
        """UPDATE sms SET status = 'DELIVERED', delivery_timestamp = :ts
           WHERE id = :id AND status IN ('PENDING', 'SENT', 'UNKNOWN')"""
    )
    fun markDelivered(id: Long, ts: Long): Int

    /** PENDING rows that never got a callback (e.g. process died) become UNKNOWN. */
    @Query("UPDATE sms SET status = 'UNKNOWN' WHERE status = 'PENDING' AND timestamp < :cutoff")
    fun markStalePendingUnknown(cutoff: Long): Int

    @Query("DELETE FROM sms WHERE timestamp < :cutoff")
    fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM sms")
    fun deleteAll(): Int
}
