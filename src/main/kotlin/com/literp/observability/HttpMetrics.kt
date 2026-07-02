package com.literp.observability

import io.vertx.core.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class HttpMetrics {
    private val requestCount = AtomicLong()
    private val errorCount = AtomicLong()
    private val databaseFailureCount = AtomicLong()
    private val totalLatencyNanos = AtomicLong()

    fun recordRequest(statusCode: Int, durationNanos: Long) {
        requestCount.incrementAndGet()
        totalLatencyNanos.addAndGet(durationNanos.coerceAtLeast(0L))
        if (statusCode >= 400) {
            errorCount.incrementAndGet()
        }
    }

    fun recordDatabaseFailure() {
        databaseFailureCount.incrementAndGet()
    }

    fun snapshot(): JsonObject {
        val requests = requestCount.get()
        val latency = totalLatencyNanos.get()
        return JsonObject()
            .put("requestCount", requests)
            .put("errorCount", errorCount.get())
            .put("databaseFailureCount", databaseFailureCount.get())
            .put("totalLatencyNanos", latency)
            .put("averageLatencyNanos", if (requests == 0L) 0L else latency / requests)
    }
}
