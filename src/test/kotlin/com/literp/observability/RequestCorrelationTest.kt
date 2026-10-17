package com.literp.observability

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class RequestCorrelationTest {
    @Test
    fun replacesMissingOversizedAndUnsafeIdsWithoutEchoingThem() {
        listOf(null, "", "x".repeat(129), "bad\nlog", "bad\tlog", " spaces ", "非ASCII").forEach { value ->
            val generated = RequestCorrelation.normalize(value)
            assertEquals(generated, UUID.fromString(generated).toString())
        }
        listOf("a".repeat(128), "trace-1.a_b:c").forEach { assertEquals(it, RequestCorrelation.normalize(it)) }
    }
}
