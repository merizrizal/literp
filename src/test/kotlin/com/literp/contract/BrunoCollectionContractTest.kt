package com.literp.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrunoCollectionContractTest {
    @Test
    fun existingOpenApiOperationsHaveOneInheritedAuthBrunoRequest() {
        val operations = openApiOperations()
        val expectedByRoute = operations.associateBy { it.route }
        val requestsByRoute = brunoRequests().groupBy { it.route }

        assertTrue(operations.isNotEmpty(), "Expected OpenAPI operations from the published bundles")
        assertEquals(operations.size, expectedByRoute.size, "OpenAPI operation routes must be unique")
        assertEquals(
            expectedByRoute.keys,
            requestsByRoute.keys,
            "Each published product, location, and order operation must have one Bruno request"
        )

        operations.forEach { operation ->
            val requests = requireNotNull(requestsByRoute[operation.route]) {
                "Missing Bruno request for ${operation.operationId}"
            }
            assertEquals(1, requests.size, "Expected one Bruno request for ${operation.operationId}")

            val request = requests.single()
            assertEquals(
                1,
                AUTH_INHERIT_REGEX.findAll(request.document).count(),
                "${request.fileName} must inherit bearer authentication"
            )
            assertFalse(request.document.contains("Authorization:"), "${request.fileName} must not contain credentials")
            assertFalse(request.document.contains("accessToken"), "${request.fileName} must not contain token variables")
        }
    }

    private fun openApiOperations(): List<OpenApiOperation> = OPEN_API_FILES.flatMap { specPath ->
        var currentPath: String? = null
        var currentMethod: String? = null
        val operations = mutableListOf<OpenApiOperation>()

        specPath.readLines().forEach { line ->
            when {
                OPEN_API_PATH_REGEX.matches(line) -> {
                    currentPath = requireNotNull(OPEN_API_PATH_REGEX.matchEntire(line)).groupValues[1]
                    currentMethod = null
                }
                OPEN_API_METHOD_REGEX.matches(line) -> {
                    currentMethod = requireNotNull(OPEN_API_METHOD_REGEX.matchEntire(line)).groupValues[1].uppercase()
                }
                OPEN_API_OPERATION_ID_REGEX.matches(line) -> {
                    val operationId = requireNotNull(OPEN_API_OPERATION_ID_REGEX.matchEntire(line)).groupValues[1]
                    operations += OpenApiOperation(
                        operationId = operationId,
                        route = ContractRoute(
                            method = requireNotNull(currentMethod) { "Missing method for $operationId in $specPath" },
                            path = normalizePath(requireNotNull(currentPath) { "Missing path for $operationId in $specPath" })
                        )
                    )
                }
            }
        }

        operations
    }

    private fun brunoRequests(): List<BrunoRequest> =
        Files.newDirectoryStream(COLLECTION_DIRECTORY, "*.bru").use { files ->
            files.mapNotNull(::parseBrunoRequest)
                .filter { request -> isCoveredPath(request.route.path) }
        }

    private fun parseBrunoRequest(path: Path): BrunoRequest? {
        val document = Files.readString(path)
        val method = BRUNO_METHOD_REGEX.find(document)?.groupValues?.get(1)?.uppercase() ?: return null
        val rawPath = BRUNO_URL_REGEX.find(document)?.groupValues?.get(1) ?: return null

        return BrunoRequest(
            fileName = path.fileName.toString(),
            document = document,
            route = ContractRoute(method, normalizePath(rawPath))
        )
    }

    private fun normalizePath(path: String): String =
        path.substringBefore('?').replace(PATH_PARAMETER_REGEX, "{parameter}")

    private fun isCoveredPath(path: String): Boolean =
        COVERED_PATH_PREFIXES.any { prefix -> path == prefix || path.startsWith("$prefix/") }

    private data class OpenApiOperation(
        val operationId: String,
        val route: ContractRoute
    )

    private data class BrunoRequest(
        val fileName: String,
        val document: String,
        val route: ContractRoute
    )

    private data class ContractRoute(
        val method: String,
        val path: String
    )

    private companion object {
        val OPEN_API_FILES = listOf(
            Path.of("api_collections/open_api_spec/product-catalog.yaml"),
            Path.of("api_collections/open_api_spec/locations.yaml"),
            Path.of("api_collections/open_api_spec/order-process.yaml")
        )
        val COLLECTION_DIRECTORY = Path.of("api_collections/Literp")
        val COVERED_PATH_PREFIXES = setOf("/uom", "/products", "/locations", "/orders", "/stock")
        val OPEN_API_PATH_REGEX = Regex("""^  (/[^:]+):$""")
        val OPEN_API_METHOD_REGEX = Regex("""^    (get|post|put|patch|delete):$""")
        val OPEN_API_OPERATION_ID_REGEX = Regex("""^      operationId:\s*([A-Za-z0-9_]+)\s*$""")
        val BRUNO_METHOD_REGEX = Regex("""(?m)^(get|post|put|patch|delete) \{$""")
        val BRUNO_URL_REGEX = Regex("""(?m)^\s*url:\s*http://\{\{host\}\}:\{\{port\}\}/api/v1([^\s]+)\s*$""")
        val PATH_PARAMETER_REGEX = Regex("""\{\{?[^}]+}}?""")
        val AUTH_INHERIT_REGEX = Regex("""(?m)^\s*auth: inherit\s*$""")
    }
}
