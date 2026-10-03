package com.smsgateway.app.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.GatewayApp
import com.smsgateway.app.data.SmsEntity
import com.smsgateway.app.data.SmsStatus
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
    val headers: Map<String, String> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap()
) : Exception(message)

private class HttpStatus(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $text"
}

private class SendReq(
    val phone: String,
    val message: String,
    val requestId: String?,
    val subscriptionId: Int?
)

/** Embedded REST API. Bound to 0.0.0.0 so other machines on the network can reach it. */
class GatewayServer(private val app: GatewayApp, port: Int) : NanoHTTPD("0.0.0.0", port) {

    private val gson: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val sendLock = Any()
    private val prefs get() = app.prefs
    private val dao get() = app.db.smsDao()

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: ApiException) {
            errorResponse(e)
        } catch (e: Throwable) {
            // Never log request bodies or headers (they contain the API key / OTPs).
            Log.e(TAG, "Unhandled error while serving request", e)
            errorResponse(ApiException(500, "INTERNAL_ERROR", "Internal server error"))
        }
        return withCors(response)
    }

    // ---------------------------------------------------------------- routing

    private fun route(s: IHTTPSession): Response {
        if (s.method == Method.OPTIONS) {
            return newFixedLengthResponse(HttpStatus(204, "No Content"), "text/plain", "")
        }
        val path = if (s.uri.length > 1) s.uri.trimEnd('/') else s.uri
        return when (path) {
            "/api/info" -> {
                requireMethod(s, Method.GET)
                json(200, info())
            }
            "/api/send" -> {
                requireMethod(s, Method.POST)
                authenticate(s)
                handleSend(s)
            }
            "/api/send-bulk" -> {
                requireMethod(s, Method.POST)
                authenticate(s)
                handleBulk(s)
            }
            "/api/status" -> {
                requireMethod(s, Method.GET)
                authenticate(s)
                handleStatus(s)
            }
            "/api/history" -> {
                requireMethod(s, Method.GET)
                authenticate(s)
                handleHistory(s)
            }
            "/api/config" -> {
                requireMethod(s, Method.GET)
                authenticate(s)
                json(200, config())
            }
            else -> throw ApiException(404, "NOT_FOUND", "Unknown endpoint")
        }
    }

    private fun requireMethod(s: IHTTPSession, m: Method) {
        if (s.method != m) {
            throw ApiException(
                405, "INVALID_REQUEST", "Method ${s.method} not allowed; use ${m.name}",
                headers = mapOf("Allow" to "${m.name}, OPTIONS")
            )
        }
    }

    private fun authenticate(s: IHTTPSession) {
        val h = s.headers // NanoHTTPD lower-cases header names
        val supplied = h["x-api-key"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: h["authorization"]?.trim()?.let {
                if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null
            }
        val expected = prefs.apiKey()
        val ok = supplied != null &&
            MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
        if (!ok) throw ApiException(401, "AUTHENTICATION_FAILED", "Missing or invalid API key")
    }

    // --------------------------------------------------------------- handlers

    private fun info(): Map<String, Any?> = linkedMapOf(
        "app_name" to "SMS Gateway",
        "version" to BuildConfig.VERSION_NAME,
        "status" to "running",
        "endpoints" to listOf(
            "GET /api/info",
            "POST /api/send",
            "POST /api/send-bulk",
            "GET /api/status",
            "GET /api/history",
            "GET /api/config"
        ),
        "server_time" to isoNow()
    )

    private fun config(): Map<String, Any?> = linkedMapOf(
        "server_port" to prefs.port,
        "server_enabled" to prefs.serverEnabled,
        "rate_limit_enabled" to prefs.rateLimitEnabled,
        "rate_limit_per_minute" to prefs.rateLimitPerMinute,
        "rate_limit_per_hour" to prefs.rateLimitPerHour,
        "notification_enabled" to prefs.notificationEnabled
    )

    private fun handleSend(s: IHTTPSession): Response {
        val req = parseSendItem(readJsonObject(s), "")

        // Idempotency: a retry with the same request_id must not send a second OTP.
        req.requestId?.let { rid ->
            dao.activeByRequestId(rid)?.let {
                return json(200, sendBody(it, "Duplicate request_id; returning existing record"))
            }
        }
        checkRate(1)

        val (rec, duplicate) = dispatch(req)
        if (rec.status == SmsStatus.FAILED && !duplicate) {
            val extra = linkedMapOf<String, Any?>("sms_id" to rec.id, "status" to rec.status)
            rec.requestId?.let { extra["request_id"] = it }
            throw ApiException(500, "SMS_SEND_FAILED", rec.errorMessage ?: "Failed to send SMS", extra = extra)
        }
        return json(200, sendBody(rec, if (duplicate) "Duplicate request_id; returning existing record" else "SMS queued for sending"))
    }

    private fun handleBulk(s: IHTTPSession): Response {
        val body = readJsonObject(s)
        val el = body.get("messages")
        if (el == null || !el.isJsonArray || el.asJsonArray.size() == 0 || el.asJsonArray.size() > MAX_BULK) {
            throw invalid("'messages' must be an array of 1-$MAX_BULK objects")
        }
        val items = el.asJsonArray

        val parsed = ArrayList<Pair<SendReq?, ApiException?>>(items.size())
        for (i in 0 until items.size()) {
            val item = items[i]
            try {
                if (!item.isJsonObject) throw invalid("messages[$i] must be an object")
                parsed.add(parseSendItem(item.asJsonObject, "messages[$i].") to null)
            } catch (e: ApiException) {
                parsed.add(null to e)
            }
        }
        checkRate(parsed.count { it.first != null })

        val results = ArrayList<Map<String, Any?>>(parsed.size)
        var ok = 0
        var failed = 0
        parsed.forEachIndexed { i, (req, err) ->
            val r = linkedMapOf<String, Any?>("index" to i)
            if (req == null) {
                failed++
                r["success"] = false
                r["error"] = err?.message
                r["error_code"] = err?.code
            } else {
                val (rec, duplicate) = dispatch(req)
                rec.requestId?.let { r["request_id"] = it }
                r["sms_id"] = rec.id
                r["status"] = rec.status
                if (rec.status == SmsStatus.FAILED && !duplicate) {
                    failed++
                    r["success"] = false
                    r["error"] = rec.errorMessage ?: "Failed to send SMS"
                    r["error_code"] = "SMS_SEND_FAILED"
                } else {
                    ok++
                    r["success"] = true
                    if (duplicate) r["duplicate"] = true
                }
            }
            results.add(r)
        }
        val total = parsed.size
        return json(
            200,
            linkedMapOf<String, Any?>(
                "success" to (failed == 0),
                "message" to "Processed $total messages: $ok queued, $failed failed",
                "results" to results,
                "total_count" to total,
                "success_count" to ok,
                "failed_count" to failed,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun handleStatus(s: IHTTPSession): Response {
        val sid = param(s, "sms_id")
        val rid = param(s, "request_id")
        val rec = when {
            sid != null -> dao.byId(sid.toLongOrNull() ?: throw invalid("sms_id must be an integer"))
            rid != null -> dao.byRequestId(rid)
            else -> throw invalid("Provide request_id or sms_id")
        } ?: throw ApiException(404, "NOT_FOUND", "SMS not found")
        return json(200, linkedMapOf("success" to true, "data" to record(rec)))
    }

    private fun handleHistory(s: IHTTPSession): Response {
        val limit = intParam(s, "limit", 50, 1, 500)
        val offset = intParam(s, "offset", 0, 0, Int.MAX_VALUE)
        val status = param(s, "status")?.uppercase(Locale.ROOT)
        if (status != null && status !in SmsStatus.ALL) {
            throw invalid("status must be one of ${SmsStatus.ALL.joinToString(", ")}")
        }
        // '+' in a query string decodes to a space; tolerate unencoded "+91..." from clients.
        val phone = param(s, "phone_number")?.let { if (it[0].isDigit()) "+$it" else it }

        val rows = dao.history(status, phone, limit, offset)
        return json(
            200,
            linkedMapOf<String, Any?>(
                "success" to true,
                "data" to rows.map { record(it) },
                "total_count" to dao.count(status, phone),
                "limit" to limit,
                "offset" to offset
            )
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Dedupes by request_id and sends, serialized so concurrent retries cannot double-send. */
    private fun dispatch(req: SendReq): Pair<SmsEntity, Boolean> = synchronized(sendLock) {
        req.requestId?.let { rid ->
            dao.activeByRequestId(rid)?.let { return@synchronized it to true }
        }
        app.smsSender.send(req.phone, req.message, req.requestId, req.subscriptionId) to false
    }

    private fun checkRate(n: Int) {
        if (!prefs.rateLimitEnabled || n <= 0) return
        val retry = app.rateLimiter.tryAcquire(prefs.apiKey(), n, prefs.rateLimitPerMinute, prefs.rateLimitPerHour)
        if (retry > 0) {
            throw ApiException(
                429, "RATE_LIMIT_EXCEEDED", "Rate limit exceeded. Retry in ${retry}s",
                headers = mapOf("Retry-After" to retry.toString())
            )
        }
    }

    private fun readJsonObject(s: IHTTPSession): JsonObject {
        val len = s.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY_BYTES) throw ApiException(413, "INVALID_REQUEST", "Request body too large")

        val files = HashMap<String, String>()
        try {
            s.parseBody(files)
        } catch (e: IOException) {
            throw invalid("Could not read request body")
        } catch (e: ResponseException) {
            throw invalid(e.message ?: "Malformed request")
        }
        val raw = files["postData"]
            ?: throw ApiException(400, "INVALID_JSON", "Body must be a JSON object (Content-Type: application/json)")

        val element: JsonElement = try {
            val reader = JsonReader(StringReader(raw)) // strict by default
            val el = gson.getAdapter(JsonElement::class.java).read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing data")
            el
        } catch (e: Exception) {
            throw ApiException(400, "INVALID_JSON", "Request body is not valid JSON")
        }
        if (!element.isJsonObject) throw ApiException(400, "INVALID_JSON", "Body must be a JSON object")
        return element.asJsonObject
    }

    private fun parseSendItem(o: JsonObject, where: String): SendReq {
        val phone = stringField(o, "phone_number", true, where)!!
        if (!E164.matches(phone)) {
            throw invalid("${where}phone_number must be E.164, e.g. +919876543210")
        }
        val message = stringField(o, "message", true, where)!!
        if (message.isBlank()) throw invalid("${where}message must not be empty")
        if (message.length > MAX_MESSAGE_LENGTH) {
            throw invalid("${where}message exceeds $MAX_MESSAGE_LENGTH characters")
        }
        val requestId = stringField(o, "request_id", false, where)
        if (requestId != null && (requestId.isBlank() || requestId.length > 128)) {
            throw invalid("${where}request_id must be 1-128 characters")
        }
        var subId: Int? = null
        for (key in listOf("simSubscriptionId", "sim_subscription_id")) {
            val e = o.get(key)
            if (e == null || e.isJsonNull) continue
            subId = (if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asString.toIntOrNull() else null)
                ?.takeIf { it >= 0 }
                ?: throw invalid("$where$key must be a non-negative integer")
        }
        return SendReq(phone, message, requestId, subId)
    }

    private fun stringField(o: JsonObject, name: String, required: Boolean, where: String): String? {
        val e = o.get(name)
        if (e == null || e.isJsonNull) {
            if (required) throw invalid("$where$name is required")
            return null
        }
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) throw invalid("$where$name must be a string")
        return e.asString
    }

    private fun param(s: IHTTPSession, name: String): String? =
        s.parameters[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun intParam(s: IHTTPSession, name: String, def: Int, min: Int, max: Int): Int {
        val raw = param(s, name) ?: return def
        val v = raw.toIntOrNull() ?: throw invalid("$name must be an integer")
        if (v < min || v > max) throw invalid("$name must be between $min and ${if (max == Int.MAX_VALUE) "2147483647" else max.toString()}")
        return v
    }

    private fun record(r: SmsEntity): Map<String, Any?> = linkedMapOf(
        "sms_id" to r.id,
        "phone_number" to r.phoneNumber,
        "message" to r.message,
        "status" to r.status,
        "request_id" to r.requestId,
        "error_message" to r.errorMessage,
        "timestamp" to r.timestamp,
        "delivery_timestamp" to r.deliveryTimestamp,
        "sim_subscription_id" to r.simSubscriptionId
    )

    private fun sendBody(rec: SmsEntity, message: String): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("success" to true, "message" to message, "sms_id" to rec.id)
        rec.requestId?.let { m["request_id"] = it }
        m["status"] = rec.status
        m["timestamp"] = rec.timestamp
        return m
    }

    private fun invalid(msg: String) = ApiException(400, "INVALID_REQUEST", msg)

    private fun json(status: Int, body: Any): Response =
        newFixedLengthResponse(HttpStatus(status, reason(status)), "application/json; charset=utf-8", gson.toJson(body))

    private fun errorResponse(e: ApiException): Response {
        val body = linkedMapOf<String, Any?>(
            "success" to false,
            "error" to e.message,
            "error_code" to e.code,
            "timestamp" to System.currentTimeMillis()
        )
        body.putAll(e.extra)
        val r = json(e.httpStatus, body)
        e.headers.forEach { (k, v) -> r.addHeader(k, v) }
        return r
    }

    private fun withCors(r: Response): Response {
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-API-Key, Authorization")
        r.addHeader("Access-Control-Max-Age", "86400")
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        else -> "Internal Server Error"
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private companion object {
        const val TAG = "GatewayServer"
        const val MAX_BULK = 100
        const val MAX_MESSAGE_LENGTH = 1600
        const val MAX_BODY_BYTES = 1_000_000L
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}
