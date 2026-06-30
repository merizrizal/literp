package com.literp.test

import io.vertx.core.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpTestSupport(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient.newHttpClient()
) {
    fun expect(
        method: String,
        path: String,
        status: Int,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val result = request(method, path, body, headers)
        assertEquals(status, result.status, "Unexpected status for $method $path with body ${result.rawBody}")
        if (status != 204) {
            assertNotNull(result.json, "Expected JSON body for $method $path")
        }
        val json = result.json
        if (status >= 400 && json != null) {
            assertErrorEnvelope(json, status)
        }
        if (status == 200 && json != null) {
            if (json.containsKey("pagination")) {
                assertListEnvelope(json)
            } else {
                assertDataEnvelope(json)
            }
        }
        return result
    }

    fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
        headers.forEach { (name, value) -> builder.header(name, value) }

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                builder.header("Content-Type", "application/json")
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.encode()))
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val rawBody = response.body()
        val json = rawBody.takeIf { it.isNotBlank() }?.let { JsonObject(it) }
        return HttpResult(response.statusCode(), rawBody, json)
    }

    companion object {
        fun assertDataEnvelope(json: JsonObject) {
            assertTrue(json.containsKey("data"), "Expected top-level data field")
        }

        fun assertListEnvelope(json: JsonObject) {
            assertNotNull(json.getJsonArray("data"), "Expected top-level data array")
            val pagination = json.getJsonObject("pagination")
            assertNotNull(pagination, "Expected top-level pagination object")
            assertTrue(pagination.containsKey("page"), "Expected pagination.page")
            assertTrue(pagination.containsKey("size"), "Expected pagination.size")
            assertTrue(pagination.containsKey("totalElements"), "Expected pagination.totalElements")
            assertTrue(pagination.containsKey("totalPages"), "Expected pagination.totalPages")
            assertFalse(pagination.containsKey("total"), "Unexpected pagination.total")
            assertFalse(pagination.containsKey("hasNext"), "Unexpected pagination.hasNext")
            assertFalse(pagination.containsKey("hasPrevious"), "Unexpected pagination.hasPrevious")
        }

        fun assertErrorEnvelope(json: JsonObject, status: Int, expectedErrorCode: String? = null) {
            assertTrue(!json.getString("error").isNullOrBlank(), "Expected error message")
            assertTrue(!json.getString("errorCode").isNullOrBlank(), "Expected errorCode")
            assertEquals(status, json.getInteger("status"), "Expected error status")
            assertTrue(!json.getString("errorId").isNullOrBlank(), "Expected errorId")
            if (expectedErrorCode != null) {
                assertEquals(expectedErrorCode, json.getString("errorCode"), "Expected errorCode")
            }
            assertFalse(json.containsKey("code"), "Unexpected legacy error code field")
            assertFalse(json.containsKey("message"), "Unexpected legacy error message field")
        }
    }
}

data class HttpResult(val status: Int, val rawBody: String, val json: JsonObject?)
