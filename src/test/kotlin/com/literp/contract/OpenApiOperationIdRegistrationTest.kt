package com.literp.contract

import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path

class OpenApiOperationIdRegistrationTest {
    @Test
    fun openApiOperationIdsMatchHttpServerRouteRegistrations() {
        val openApiOperationIds = openApiOperationIds()
        val registeredOperationIds = registeredOperationIds()

        assertNoDuplicates("OpenAPI operationId", openApiOperationIds)
        assertNoDuplicates("HttpServerVerticle getRoute", registeredOperationIds)

        val openApiSet = openApiOperationIds.toSet()
        val registeredSet = registeredOperationIds.toSet()
        val missingRegistrations = openApiSet - registeredSet
        val registrationsWithoutContract = registeredSet - openApiSet

        assertEquals(
            openApiSet,
            registeredSet,
            "OpenAPI operationIds must match registered handler routes. " +
                "openApiCount=${openApiSet.size}, registeredCount=${registeredSet.size}, " +
                "missingRegistrations=${missingRegistrations.sorted()}, " +
                "registrationsWithoutContract=${registrationsWithoutContract.sorted()}"
        )
    }

    private fun openApiOperationIds(): List<String> {
        return OPEN_API_FILES.flatMap { path ->
            path.readLines().mapNotNull { line -> OPERATION_ID_REGEX.find(line)?.groupValues?.get(1) }
        }
    }

    private fun registeredOperationIds(): List<String> {
        return HTTP_SERVER_VERTICLE.readLines()
            .mapNotNull { line -> GET_ROUTE_REGEX.find(line)?.groupValues?.get(1) }
    }

    private fun assertNoDuplicates(label: String, values: List<String>) {
        val duplicates = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertTrue(duplicates.isEmpty(), "$label values must be unique, duplicates=$duplicates")
    }

    private companion object {
        val OPEN_API_FILES = listOf(
            Path.of("api_collections/open_api_spec/product-catalog.yaml"),
            Path.of("api_collections/open_api_spec/locations.yaml"),
            Path.of("api_collections/open_api_spec/order-process.yaml")
        )
        val HTTP_SERVER_VERTICLE = Path.of("src/main/kotlin/com/literp/verticle/HttpServerVerticle.kt")
        val OPERATION_ID_REGEX = Regex("""^\s*operationId:\s*([A-Za-z0-9_]+)\s*$""")
        val GET_ROUTE_REGEX = Regex("""getRoute\("([^"]+)"\)""")
    }
}
