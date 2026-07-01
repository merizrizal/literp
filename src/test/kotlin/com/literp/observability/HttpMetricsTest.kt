package com.literp.observability

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpMetricsTest {
    @Test
    fun snapshotTracksRequestsErrorsAndDatabaseFailures() {
        val metrics = HttpMetrics()

        metrics.recordRequest(200, 100)
        metrics.recordRequest(503, 250)
        metrics.recordDatabaseFailure()

        val snapshot = metrics.snapshot()
        assertEquals(2L, snapshot.getLong("requestCount"))
        assertEquals(1L, snapshot.getLong("errorCount"))
        assertEquals(1L, snapshot.getLong("databaseFailureCount"))
        assertEquals(350L, snapshot.getLong("totalLatencyNanos"))
        assertEquals(175L, snapshot.getLong("averageLatencyNanos"))
    }
}
