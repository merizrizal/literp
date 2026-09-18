package com.literp.contract

import com.literp.service.pos.PosOperationsService
import com.literp.test.HttpTestSupport
import com.literp.test.HttpTestSupport.Companion.assertErrorEnvelope
import com.literp.verticle.handler.PosOperationsHandler
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.rxjava3.core.http.HttpServer
import io.vertx.rxjava3.ext.web.Router
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PosOperationsContractTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var server: HttpServer
    private lateinit var http: HttpTestSupport

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        server = rxVertx.createHttpServer()
            .requestHandler(createRouter(PosOperationsHandler(placeholderReadService)))
            .rxListen(0, "127.0.0.1")
            .blockingGet()
        http = HttpTestSupport("http://127.0.0.1:${server.actualPort()}")
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun remainingPosPlaceholdersReturnNotImplementedWithoutSuccessData() {
        placeholderRequests().forEach { request ->
            val requestId = "request-${request.operationId}"
            val result = http.request(
                method = request.method,
                path = request.path,
                headers = mapOf("X-Request-ID" to requestId)
            )

            assertEquals(501, result.status, "Unexpected status for ${request.operationId}")
            assertEquals(requestId, result.header("X-Request-ID"))
            val json = requireNotNull(result.json)
            assertErrorEnvelope(json, 501, "NOT_IMPLEMENTED")
            assertFalse(json.containsKey("data"), "Placeholder reported successful data for ${request.operationId}")
        }
    }

    @Test
    fun everyPosOperationHasOneDocumentedBrunoPlaceholder() {
        val collectionDir = Path.of("api_collections/Literp")
        val requests = brunoRequests()
        val expectedFiles = requests.map { it.fileName }.sorted()
        val actualFiles = Files.newDirectoryStream(collectionDir, "Pos-*.bru").use { files ->
            files.map { it.fileName.toString() }.sorted()
        }

        assertEquals(expectedFiles, actualFiles, "POS Bruno request inventory must match the contract")
        assertEquals(10, actualFiles.size, "Every POS operation must have one Bruno request")

        val placeholderOperationIds = placeholderRequests().map { it.operationId }.toSet()
        requests.forEach { request ->
            val document = Files.readString(collectionDir.resolve(request.fileName))
            val operationIdCount = Regex(
                """(?m)^\s*operationId:\s*${Regex.escape(request.operationId)}\s*$"""
            ).findAll(document).count()

            assertEquals(1, operationIdCount, "Expected one operationId for ${request.fileName}")
            assertEquals(
                1,
                Regex("""(?m)^\s*auth: inherit\s*$""").findAll(document).count(),
                "${request.fileName} must inherit bearer authentication"
            )
            assertTrue(
                document.contains("${request.method.lowercase()} {"),
                "${request.fileName} must declare the ${request.method} method"
            )
            assertTrue(
                document.contains("url: http://{{host}}:{{port}}${request.brunoPath}"),
                "${request.fileName} must target ${request.brunoPath}"
            )
            assertTrue(
                document.contains("request: ${request.method} ${request.contractPath}"),
                "${request.fileName} must document its method and contract path"
            )
            if (request.operationId in placeholderOperationIds) {
                assertTrue(
                    document.contains("501 NOT_IMPLEMENTED"),
                    "${request.fileName} must document placeholder availability"
                )
            }
            assertFalse(document.contains("Authorization:"), "${request.fileName} must not contain credentials")
            assertFalse(document.contains("accessToken"), "${request.fileName} must not contain token variables")
            assertFalse(
                document.contains("script:post-response"),
                "${request.fileName} must not treat a placeholder as successful data"
            )
        }
    }

    private val placeholderReadService = object : PosOperationsService {
        override fun listPosTerminals(
            page: Int,
            size: Int,
            sort: String,
            locationId: String?,
            isActive: Boolean?,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Terminal reads are not exercised by this placeholder test")

        override fun getPosTerminal(
            terminalId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Terminal reads are not exercised by this placeholder test")

        override fun createPosTerminal(
            locationId: String,
            terminalCode: String,
            deviceName: String,
            idempotencyKey: String,
            actorSubject: String,
            organizationId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Terminal creation is not exercised by this placeholder test")

        override fun updatePosTerminal(
            terminalId: String,
            terminalCode: String,
            deviceName: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Terminal update is not exercised by this placeholder test")

        override fun deactivatePosTerminal(
            terminalId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Terminal deactivation is not exercised by this placeholder test")
    }

    private fun createRouter(handler: PosOperationsHandler): Router = Router.router(rxVertx).apply {
        get("/api/v1/pos/terminals").handler(handler::listPosTerminals)
        post("/api/v1/pos/terminals").handler(handler::createPosTerminal)
        get("/api/v1/pos/terminals/:terminalId").handler(handler::getPosTerminal)
        patch("/api/v1/pos/terminals/:terminalId").handler(handler::updatePosTerminal)
        post("/api/v1/pos/terminals/:terminalId/deactivate").handler(handler::deactivatePosTerminal)
        post("/api/v1/pos/terminals/:terminalId/shifts").handler(handler::openPosShift)
        get("/api/v1/pos/terminals/:terminalId/current-shift").handler(handler::getCurrentPosShift)
        post("/api/v1/pos/shifts/:shiftId/close").handler(handler::closePosShift)
        get("/api/v1/pos/receipts/by-number/:receiptNumber").handler(handler::getPosReceiptByNumber)
        get("/api/v1/pos/orders/:salesOrderId/receipts").handler(handler::listPosReceiptsBySalesOrder)
    }

    private fun placeholderRequests(): List<PlaceholderRequest> = listOf(
        PlaceholderRequest("openPosShift", "POST", "/api/v1/pos/terminals/terminal-1/shifts"),
        PlaceholderRequest("getCurrentPosShift", "GET", "/api/v1/pos/terminals/terminal-1/current-shift"),
        PlaceholderRequest("closePosShift", "POST", "/api/v1/pos/shifts/shift-1/close"),
        PlaceholderRequest("getPosReceiptByNumber", "GET", "/api/v1/pos/receipts/by-number/receipt-1"),
        PlaceholderRequest("listPosReceiptsBySalesOrder", "GET", "/api/v1/pos/orders/order-1/receipts")
    )

    private fun brunoRequests(): List<BrunoRequest> = listOf(
        BrunoRequest("Pos-List-Terminals.bru", "listPosTerminals", "GET", "/api/v1/pos/terminals", "/api/v1/pos/terminals"),
        BrunoRequest("Pos-Create-Terminal.bru", "createPosTerminal", "POST", "/api/v1/pos/terminals", "/api/v1/pos/terminals"),
        BrunoRequest("Pos-Get-Terminal.bru", "getPosTerminal", "GET", "/api/v1/pos/terminals/{{terminalId}}", "/api/v1/pos/terminals/{terminalId}"),
        BrunoRequest("Pos-Update-Terminal.bru", "updatePosTerminal", "PATCH", "/api/v1/pos/terminals/{{terminalId}}", "/api/v1/pos/terminals/{terminalId}"),
        BrunoRequest("Pos-Deactivate-Terminal.bru", "deactivatePosTerminal", "POST", "/api/v1/pos/terminals/{{terminalId}}/deactivate", "/api/v1/pos/terminals/{terminalId}/deactivate"),
        BrunoRequest("Pos-Open-Shift.bru", "openPosShift", "POST", "/api/v1/pos/terminals/{{terminalId}}/shifts", "/api/v1/pos/terminals/{terminalId}/shifts"),
        BrunoRequest("Pos-Current-Shift.bru", "getCurrentPosShift", "GET", "/api/v1/pos/terminals/{{terminalId}}/current-shift", "/api/v1/pos/terminals/{terminalId}/current-shift"),
        BrunoRequest("Pos-Close-Shift.bru", "closePosShift", "POST", "/api/v1/pos/shifts/{{shiftId}}/close", "/api/v1/pos/shifts/{shiftId}/close"),
        BrunoRequest("Pos-Get-Receipt-By-Number.bru", "getPosReceiptByNumber", "GET", "/api/v1/pos/receipts/by-number/{{receiptNumber}}", "/api/v1/pos/receipts/by-number/{receiptNumber}"),
        BrunoRequest("Pos-List-Receipts-By-Order.bru", "listPosReceiptsBySalesOrder", "GET", "/api/v1/pos/orders/{{salesOrderId}}/receipts", "/api/v1/pos/orders/{salesOrderId}/receipts")
    )

    private data class BrunoRequest(
        val fileName: String,
        val operationId: String,
        val method: String,
        val brunoPath: String,
        val contractPath: String
    )

    private data class PlaceholderRequest(
        val operationId: String,
        val method: String,
        val path: String
    )
}
