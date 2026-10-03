package com.smsgateway.app.server

/** Sliding-window limiter (per-minute and per-hour) keyed by API key. */
class RateLimiter {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /**
     * Tries to reserve [n] units. Returns 0 when allowed (and records the hits),
     * otherwise the number of seconds the caller should wait before retrying.
     */
    @Synchronized
    fun tryAcquire(
        key: String,
        n: Int,
        perMinute: Int,
        perHour: Int,
        now: Long = System.currentTimeMillis()
    ): Long {
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= HOUR_MS) q.removeFirst()

        val retry = maxOf(
            wait(q, n, perMinute, MINUTE_MS, now),
            wait(q, n, perHour, HOUR_MS, now)
        )
        if (retry > 0) return retry
        repeat(n) { q.addLast(now) }
        return 0
    }

    private fun wait(q: ArrayDeque<Long>, n: Int, limit: Int, window: Long, now: Long): Long {
        val inWindow = q.filter { now - it < window }
        if (inWindow.size + n <= limit) return 0
        if (n > limit) return window / 1000 // can never fit in one window
        val idx = inWindow.size + n - limit - 1
        val frees = inWindow[idx] + window - now
        return maxOf(1L, (frees + 999) / 1000)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
    }
}
