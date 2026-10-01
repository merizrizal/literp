package com.literp.contract

import com.literp.service.pos.PosOperationsService
import com.literp.test.HttpTestSupport
import com.literp.test.HttpTestSupport.Companion.assertErrorEnvelope
import com.literp.verticle.handler.POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY
import com.literp.verticle.handler.PosOperationsHandler
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
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
    fun remainingPosWritePlaceholdersReturnNotImplementedWithoutSuccessData() {
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
    fun receiptLookupHandlersReturnSuccessEnvelopesAndValidateRequests() {
        val receipt = http.request("GET", "/api/v1/pos/receipts/by-number/RCPT-CONTRACT-0001")
        assertEquals(200, receipt.status)
        assertEquals(
            "RCPT-CONTRACT-0001",
            requireNotNull(receipt.json).getJsonObject("data").getString("receiptNumber")
        )

        val orderId = "01234567-89ab-cdef-0123-456789abcdef"
        val receipts = http.request("GET", "/api/v1/pos/orders/$orderId/receipts?page=2&size=3")
        assertEquals(200, receipts.status)
        HttpTestSupport.assertListEnvelope(requireNotNull(receipts.json))
        val pagination = requireNotNull(receipts.json).getJsonObject("pagination")
        assertEquals(2, pagination.getInteger("page"))
        assertEquals(3, pagination.getInteger("size"))

        val invalidOrderId = http.request("GET", "/api/v1/pos/orders/not-a-uuid/receipts")
        assertEquals(400, invalidOrderId.status)
        assertEquals(400, requireNotNull(invalidOrderId.json).getInteger("status"))

        val tooLongReceiptNumber = http.request("GET", "/api/v1/pos/receipts/by-number/${"R".repeat(51)}")
        assertEquals(400, tooLongReceiptNumber.status)
    }

    @Test
    fun receiptLookupFailuresDoNotExposeDatabaseDetails() {
        placeholderReadService.receiptLookupFailure = IllegalStateException("sensitive SQL failure")
        try {
            val result = http.request("GET", "/api/v1/pos/receipts/by-number/RCPT-CONTRACT-0001")
            assertEquals(500, result.status)
            val error = requireNotNull(result.json).getString("error")
            assertEquals("Failed to get POS receipt", error)
            assertFalse(error.contains("sensitive SQL failure"))
        } finally {
            placeholderReadService.receiptLookupFailure = null
        }
    }

    @Test
    fun everyPosOperationHasOneDocumentedBrunoRequest() {
        val collectionDir = Path.of("api_collections/Literp")
        val requests = brunoRequests()
        val expectedFiles = requests.map { it.fileName }.sorted()
        val actualFiles = Files.newDirectoryStream(collectionDir, "Pos-*.bru").use { files ->
            files.map { it.fileName.toString() }.sorted()
        }

        assertEquals(expectedFiles, actualFiles, "POS Bruno request inventory must match the contract")
        assertEquals(12, actualFiles.size, "Every POS operation must have one Bruno request")

        val placeholderOperationIds = placeholderRequests().map { it.operationId }.toSet()
        val openApiPaths = requireNotNull(
            JsonObject(Files.readString(Path.of("api_collections/open_api_spec/pos-operations.json"))).getJsonObject("paths")
        )

        requests.forEach { request ->
            val document = Files.readString(collectionDir.resolve(request.fileName))
            val pathItem = requireNotNull(openApiPaths.getJsonObject(request.contractPath.removePrefix("/api/v1")))
            val operation = requireNotNull(pathItem.getJsonObject(request.method.lowercase()))
            val responses = requireNotNull(operation.getJsonObject("responses"))
            assertEquals(
                request.operationId in placeholderOperationIds,
                responses.containsKey("501"),
                "OpenAPI 501 response must match availability for ${request.operationId}"
            )
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
            } else {
                assertFalse(
                    document.contains("501 NOT_IMPLEMENTED"),
                    "${request.fileName} must not describe an implemented operation as a placeholder"
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
        var receiptLookupFailure: Throwable? = null

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

        override fun getPosReceiptByNumber(
            receiptNumber: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> {
            val failure = receiptLookupFailure
            return if (failure == null) {
                Future.succeededFuture(
                    JsonObject().put("receiptId", "contract-receipt").put("receiptNumber", receiptNumber)
                )
            } else {
                Future.failedFuture(failure)
            }
        }

        override fun listPosReceiptsBySalesOrder(
            salesOrderId: String,
            page: Int,
            size: Int,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.succeededFuture(
            JsonObject()
                .put("data", JsonArray())
                .put(
                    "pagination",
                    JsonObject()
                        .put("page", page)
                        .put("size", size)
                        .put("totalElements", 0)
                        .put("totalPages", 0)
                )
        )

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

        override fun openPosShift(
            terminalId: String,
            openingBalance: String,
            currency: String,
            idempotencyKey: String,
            actorSubject: String,
            organizationId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Shift opening is not exercised by this placeholder test")

        override fun getCurrentPosShift(
            terminalId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Current shift lookup is not exercised by this placeholder test")

        override fun closePosShift(
            shiftId: String,
            closingBalance: String,
            idempotencyKey: String,
            actorSubject: String,
            organizationId: String,
            authorizedLocationIds: JsonArray
        ): Future<io.vertx.core.json.JsonObject> = Future.failedFuture("Shift closing is not exercised by this placeholder test")
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
        get("/api/v1/pos/receipts/by-number/:receiptNumber")
            .handler { context ->
                context.put(POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY, setOf("contract-location"))
                context.next()
            }
            .handler(handler::getPosReceiptByNumber)
        get("/api/v1/pos/orders/:salesOrderId/receipts")
            .handler { context ->
                context.put(POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY, setOf("contract-location"))
                context.next()
            }
            .handler(handler::listPosReceiptsBySalesOrder)
        post("/api/v1/pos/orders/:salesOrderId/receipts").handler(handler::generatePosReceipt)
        post("/api/v1/pos/receipts/:receiptId/refunds").handler(handler::createPosReceiptRefund)
    }

    private fun placeholderRequests(): List<PlaceholderRequest> = listOf(
        PlaceholderRequest("generatePosReceipt", "POST", "/api/v1/pos/orders/order-1/receipts"),
        PlaceholderRequest("createPosReceiptRefund", "POST", "/api/v1/pos/receipts/receipt-1/refunds")
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
        BrunoRequest("Pos-List-Receipts-By-Order.bru", "listPosReceiptsBySalesOrder", "GET", "/api/v1/pos/orders/{{salesOrderId}}/receipts", "/api/v1/pos/orders/{salesOrderId}/receipts"),
        BrunoRequest("Pos-Generate-Receipt.bru", "generatePosReceipt", "POST", "/api/v1/pos/orders/{{salesOrderId}}/receipts", "/api/v1/pos/orders/{salesOrderId}/receipts"),
        BrunoRequest("Pos-Create-Receipt-Refund.bru", "createPosReceiptRefund", "POST", "/api/v1/pos/receipts/{{receiptId}}/refunds", "/api/v1/pos/receipts/{receiptId}/refunds")
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
