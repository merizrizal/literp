package com.literp.verticle

import com.literp.common.ErrorCodes
import com.literp.repository.LocationRepository
import com.literp.repository.ProductRepository
import com.literp.test.HttpTestSupport
import com.literp.test.SecurityTestFixture
import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationHttpIntegrationTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var deploymentId: String
    private lateinit var pool: Pool
    private lateinit var locationRepository: LocationRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var http: HttpTestSupport
    private lateinit var securityFixture: SecurityTestFixture

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)
        locationRepository = LocationRepository(pool)
        productRepository = ProductRepository(pool)
        securityFixture = SecurityTestFixture(coreVertx)
        deploymentId = coreVertx.deployVerticle(HttpServerVerticle(rxVertx, securityFixture.securityHandler))
            .toCompletionStage()
            .toCompletableFuture()
            .get()
        http = HttpTestSupport("http://127.0.0.1:8010")
    }

    @AfterAll
    fun tearDown() {
        if (::deploymentId.isInitialized && ::coreVertx.isInitialized) {
            coreVertx.undeploy(deploymentId)
                .toCompletionStage()
                .toCompletableFuture()
                .get()
        }
        if (::securityFixture.isInitialized) {
            securityFixture.close()
        }
        if (::pool.isInitialized) {
            pool.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun publicExceptionsAreGetOnlyAndOperationalRoutesRequireEligibleIdentity() {
        val root = http.request("GET", "/")
        assertEquals(200, root.status)
        assertTrue(requireNotNull(root.json).getBoolean("success"))

        val live = http.request("GET", "/health/live")
        assertEquals(200, live.status)
        assertEquals("UP", requireNotNull(live.json).getString("status"))

        val postLive = http.request("POST", "/health/live")
        assertEquals(401, postLive.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(postLive.json), 401, ErrorCodes.UNAUTHENTICATED)

        val anonymousMetrics = http.request("GET", "/metrics", headers = mapOf("X-Request-ID" to "auth-request"))
        assertEquals(401, anonymousMetrics.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(anonymousMetrics.json), 401, ErrorCodes.UNAUTHENTICATED)
        assertEquals("Bearer", anonymousMetrics.header("WWW-Authenticate"))
        assertEquals("auth-request", anonymousMetrics.header("X-Request-ID"))

        val ineligibleMetrics = http.request(
            "GET",
            "/metrics",
            headers = securityFixture.authorization(
                capabilities = setOf("operations.read"),
                operator = false,
                principalKind = "human"
            )
        )
        assertEquals(403, ineligibleMetrics.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(ineligibleMetrics.json), 403, ErrorCodes.FORBIDDEN)

        val serviceMetrics = http.request(
            "GET",
            "/metrics",
            headers = securityFixture.authorization(
                capabilities = setOf("operations.read"),
                operator = false,
                principalKind = "service"
            )
        )
        assertEquals(200, serviceMetrics.status)
        assertNotNull(serviceMetrics.json?.getNumber("requestCount"))
    }

    @Test
    fun everyRegisteredBusinessOperationRejectsAnonymousRequests() {
        val routeRequests = listOf(
            Request("GET", "/api/v1/uom"),
            Request("POST", "/api/v1/uom"),
            Request("GET", "/api/v1/uom/${UUID.randomUUID()}"),
            Request("PUT", "/api/v1/uom/${UUID.randomUUID()}"),
            Request("DELETE", "/api/v1/uom/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/products"),
            Request("POST", "/api/v1/products"),
            Request("GET", "/api/v1/products/${UUID.randomUUID()}"),
            Request("PUT", "/api/v1/products/${UUID.randomUUID()}"),
            Request("DELETE", "/api/v1/products/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/products/${UUID.randomUUID()}/variants"),
            Request("POST", "/api/v1/products/${UUID.randomUUID()}/variants"),
            Request("GET", "/api/v1/products/${UUID.randomUUID()}/variants/${UUID.randomUUID()}"),
            Request("PUT", "/api/v1/products/${UUID.randomUUID()}/variants/${UUID.randomUUID()}"),
            Request("DELETE", "/api/v1/products/${UUID.randomUUID()}/variants/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/locations"),
            Request("POST", "/api/v1/locations"),
            Request("GET", "/api/v1/locations/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/locations/by-code/TEST-CODE"),
            Request("PUT", "/api/v1/locations/${UUID.randomUUID()}"),
            Request("DELETE", "/api/v1/locations/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/orders"),
            Request("POST", "/api/v1/orders"),
            Request("GET", "/api/v1/orders/${UUID.randomUUID()}"),
            Request("GET", "/api/v1/stock/current?productId=${UUID.randomUUID()}&locationId=${securityFixture.locationId}"),
            Request("GET", "/api/v1/stock/available?productId=${UUID.randomUUID()}&locationId=${securityFixture.locationId}"),
            Request("POST", "/api/v1/orders/${UUID.randomUUID()}/lines"),
            Request("POST", "/api/v1/orders/${UUID.randomUUID()}/confirm"),
            Request("POST", "/api/v1/orders/${UUID.randomUUID()}/payments"),
            Request("POST", "/api/v1/orders/${UUID.randomUUID()}/fulfill"),
            Request("POST", "/api/v1/orders/${UUID.randomUUID()}/cancel"),
            Request("GET", "/api/v1/pos/terminals"),
            Request("POST", "/api/v1/pos/terminals"),
            Request("GET", "/api/v1/pos/terminals/${UUID.randomUUID()}"),
            Request("PATCH", "/api/v1/pos/terminals/${UUID.randomUUID()}"),
            Request("POST", "/api/v1/pos/terminals/${UUID.randomUUID()}/deactivate"),
            Request("POST", "/api/v1/pos/terminals/${UUID.randomUUID()}/shifts"),
            Request("GET", "/api/v1/pos/terminals/${UUID.randomUUID()}/current-shift"),
            Request("POST", "/api/v1/pos/shifts/${UUID.randomUUID()}/close"),
            Request("GET", "/api/v1/pos/receipts/by-number/receipt-1"),
            Request("GET", "/api/v1/pos/orders/${UUID.randomUUID()}/receipts")
        )

        assertEquals(41, routeRequests.size)
        routeRequests.forEach { request ->
            val response = http.request(request.method, request.path)
            assertEquals(401, response.status, "Expected anonymous ${request.method} ${request.path} to be rejected")
            HttpTestSupport.assertErrorEnvelope(requireNotNull(response.json), 401, ErrorCodes.UNAUTHENTICATED)
            assertEquals("Bearer", response.header("WWW-Authenticate"))
        }
    }

    @Test
    fun authenticatedPosPlaceholdersEnforceCapabilitiesAndHumanEligibility() {
        val terminalId = UUID.randomUUID().toString()
        val shiftId = UUID.randomUUID().toString()
        val salesOrderId = UUID.randomUUID().toString()
        val requests = listOf(
            PosPlaceholderRequest(
                "GET",
                "/api/v1/pos/terminals",
                "pos.terminal.read"
            ),
            PosPlaceholderRequest(
                "POST",
                "/api/v1/pos/terminals",
                "pos.terminal.write",
                JsonObject()
                    .put("locationId", securityFixture.locationId)
                    .put("terminalCode", "TEST-01")
                    .put("deviceName", "Front counter"),
                mapOf("Idempotency-Key" to "pos-create-$terminalId")
            ),
            PosPlaceholderRequest(
                "GET",
                "/api/v1/pos/terminals/$terminalId",
                "pos.terminal.read"
            ),
            PosPlaceholderRequest(
                "PATCH",
                "/api/v1/pos/terminals/$terminalId",
                "pos.terminal.write",
                JsonObject().put("deviceName", "Front counter tablet")
            ),
            PosPlaceholderRequest(
                "POST",
                "/api/v1/pos/terminals/$terminalId/deactivate",
                "pos.terminal.write"
            ),
            PosPlaceholderRequest(
                "POST",
                "/api/v1/pos/terminals/$terminalId/shifts",
                "pos.shift.open",
                JsonObject().put("openingBalance", BigDecimal("250.00")).put("currency", "USD"),
                mapOf("Idempotency-Key" to "pos-open-$shiftId"),
                principalKind = "human"
            ),
            PosPlaceholderRequest(
                "GET",
                "/api/v1/pos/terminals/$terminalId/current-shift",
                "pos.shift.read"
            ),
            PosPlaceholderRequest(
                "POST",
                "/api/v1/pos/shifts/$shiftId/close",
                "pos.shift.close",
                JsonObject().put("closingBalance", BigDecimal("275.00")),
                mapOf("Idempotency-Key" to "pos-close-$shiftId"),
                principalKind = "human"
            ),
            PosPlaceholderRequest(
                "GET",
                "/api/v1/pos/receipts/by-number/receipt-1",
                "pos.receipt.read"
            ),
            PosPlaceholderRequest(
                "GET",
                "/api/v1/pos/orders/$salesOrderId/receipts",
                "pos.receipt.read"
            )
        )

        requests.forEachIndexed { index, request ->
            val requestId = "pos-placeholder-$index"
            val result = http.request(
                request.method,
                request.path,
                request.body,
                headers = securityFixture.authorization(
                    capabilities = setOf(request.capability),
                    operator = false,
                    principalKind = request.principalKind
                ) + request.headers + ("X-Request-ID" to requestId)
            )

            assertEquals(501, result.status, "Unexpected status for ${request.method} ${request.path}")
            HttpTestSupport.assertErrorEnvelope(requireNotNull(result.json), 501, "NOT_IMPLEMENTED")
            assertEquals(requestId, result.header("X-Request-ID"))
        }

        val serviceOpen = http.request(
            "POST",
            "/api/v1/pos/terminals/$terminalId/shifts",
            JsonObject().put("openingBalance", BigDecimal("250.00")).put("currency", "USD"),
            securityFixture.authorization(
                capabilities = setOf("pos.shift.open"),
                operator = false,
                principalKind = "service"
            ) + ("Idempotency-Key" to "pos-service-open-$shiftId")
        )
        assertEquals(403, serviceOpen.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(serviceOpen.json), 403, ErrorCodes.FORBIDDEN)

        val missingCapability = http.request(
            "GET",
            "/api/v1/pos/terminals",
            headers = securityFixture.authorization(
                capabilities = setOf("master-data.read"),
                operator = false,
                principalKind = "service"
            )
        )
        assertEquals(403, missingCapability.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(missingCapability.json), 403, ErrorCodes.FORBIDDEN)
    }

    @Test
    fun authenticatedUnknownPathsRemainNotFoundAndKnownOperationsApplyCapabilities() {
        val unknown = http.request(
            "GET",
            "/api/v1/does-not-exist",
            headers = securityFixture.authorization()
        )
        assertEquals(404, unknown.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(unknown.json), 404, ErrorCodes.RESOURCE_NOT_FOUND)

        val missingCapability = http.request(
            "GET",
            "/api/v1/uom/${UUID.randomUUID()}",
            headers = securityFixture.authorization(capabilities = setOf("master-data.write"))
        )
        assertEquals(403, missingCapability.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(missingCapability.json), 403, ErrorCodes.FORBIDDEN)

        val wrongOrganization = http.request(
            "GET",
            "/metrics",
            headers = securityFixture.authorization(organizationId = "other-org")
        )
        assertEquals(403, wrongOrganization.status)
        HttpTestSupport.assertErrorEnvelope(requireNotNull(wrongOrganization.json), 403, ErrorCodes.FORBIDDEN)
    }

    private data class Request(val method: String, val path: String)

    private data class PosPlaceholderRequest(
        val method: String,
        val path: String,
        val capability: String,
        val body: JsonObject? = null,
        val headers: Map<String, String> = emptyMap(),
        val principalKind: String = "service"
    )

    @Test
    fun productionOrderScopesReauthorizeReplaysAndIgnoreSpoofedFulfillmentActors() {
        val seed = createScopedOrderSeed()
        val orderIds = mutableListOf<String>()

        try {
            val writeAllowed = securityFixture.authorization(
                capabilities = setOf("order.write"),
                locationIds = setOf(seed.locationId)
            )
            val draftBody = JsonObject()
                .put("salesChannel", "POS")
                .put("locationId", seed.locationId)
                .put("currency", "USD")
                .put("notes", "authentication regression ${seed.suffix}")

            val beforeAllowedLocation = orderCount(seed.locationId)
            val noCapability = http.request(
                "POST",
                "/api/v1/orders",
                draftBody,
                securityFixture.authorization(capabilities = setOf("order.read"), locationIds = setOf(seed.locationId))
            )
            assertEquals(403, noCapability.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(noCapability.json), 403, ErrorCodes.FORBIDDEN)
            assertEquals(beforeAllowedLocation, orderCount(seed.locationId))

            val beforeForbiddenLocation = orderCount(seed.otherLocationId)
            val outOfScopeCreate = http.request(
                "POST",
                "/api/v1/orders",
                draftBody.copy().put("locationId", seed.otherLocationId),
                writeAllowed
            )
            assertEquals(403, outOfScopeCreate.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(outOfScopeCreate.json), 403, ErrorCodes.FORBIDDEN)
            assertEquals(beforeForbiddenLocation, orderCount(seed.otherLocationId))

            val orderId = http.expect("POST", "/api/v1/orders", 201, draftBody, writeAllowed)
                .json!!.getJsonObject("data").getString("salesOrderId")
            orderIds += orderId

            val otherOrderId = http.expect(
                "POST",
                "/api/v1/orders",
                201,
                draftBody.copy().put("locationId", seed.otherLocationId),
                securityFixture.authorization(
                    capabilities = setOf("order.write"),
                    locationIds = setOf(seed.locationId, seed.otherLocationId)
                )
            ).json!!.getJsonObject("data").getString("salesOrderId")
            orderIds += otherOrderId

            http.expect(
                "GET",
                "/api/v1/orders/$orderId",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("order.read"),
                    locationIds = setOf(seed.locationId)
                )
            )
            http.expect(
                "GET",
                "/api/v1/stock/current?productId=${seed.productId}&locationId=${seed.locationId}",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("inventory.read"),
                    locationIds = setOf(seed.locationId)
                )
            )
            http.expect(
                "GET",
                "/api/v1/stock/available?productId=${seed.productId}&locationId=${seed.locationId}",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("inventory.read"),
                    locationIds = setOf(seed.locationId)
                )
            )

            val scopedPage = http.expect(
                "GET",
                "/api/v1/orders?page=0&size=1&sort=orderNumber,asc",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("order.read"),
                    locationIds = setOf(seed.locationId)
                )
            ).json!!
            assertEquals(1, scopedPage.getJsonObject("pagination").getInteger("totalElements"))
            assertEquals(seed.locationId, scopedPage.getJsonArray("data").getJsonObject(0).getString("locationId"))

            val forbiddenFilter = http.request(
                "GET",
                "/api/v1/orders?locationId=${seed.otherLocationId}",
                headers = securityFixture.authorization(
                    capabilities = setOf("order.read"),
                    locationIds = setOf(seed.locationId)
                )
            )
            assertEquals(403, forbiddenFilter.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(forbiddenFilter.json), 403, ErrorCodes.FORBIDDEN)

            val outOfScopeGet = http.request(
                "GET",
                "/api/v1/orders/$orderId",
                headers = securityFixture.authorization(
                    capabilities = setOf("order.read"),
                    locationIds = setOf(seed.otherLocationId)
                )
            )
            assertEquals(404, outOfScopeGet.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(outOfScopeGet.json), 404, ErrorCodes.RESOURCE_NOT_FOUND)

            http.expect(
                "POST",
                "/api/v1/orders/$orderId/lines",
                201,
                JsonObject().put("productId", seed.productId).put("quantityOrdered", 2).put("unitPrice", 15),
                writeAllowed
            )
            http.expect(
                "POST",
                "/api/v1/orders/$orderId/confirm",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("order.confirm"),
                    locationIds = setOf(seed.locationId)
                ) + ("Idempotency-Key" to "AUTH-CONFIRM-${seed.suffix}")
            )
            http.expect(
                "POST",
                "/api/v1/orders/$orderId/payments",
                201,
                JsonObject().put("paymentMethod", "CARD").put("amount", 30).put("transactionRef", "AUTH-${seed.suffix}"),
                securityFixture.authorization(
                    capabilities = setOf("payment.capture"),
                    locationIds = setOf(seed.locationId)
                ) + ("Idempotency-Key" to "AUTH-PAY-${seed.suffix}")
            )

            http.expect(
                "POST",
                "/api/v1/orders/$otherOrderId/lines",
                201,
                JsonObject().put("productId", seed.productId).put("quantityOrdered", 1).put("unitPrice", 10),
                securityFixture.authorization(
                    capabilities = setOf("order.write"),
                    locationIds = setOf(seed.otherLocationId)
                )
            )
            http.expect(
                "POST",
                "/api/v1/orders/$otherOrderId/confirm",
                200,
                headers = securityFixture.authorization(
                    capabilities = setOf("order.confirm"),
                    locationIds = setOf(seed.otherLocationId)
                ) + ("Idempotency-Key" to "AUTH-OTHER-CONFIRM-${seed.suffix}")
            )
            http.expect(
                "POST",
                "/api/v1/orders/$otherOrderId/cancel",
                200,
                JsonObject().put("reason", "authorization regression cancellation"),
                securityFixture.authorization(
                    capabilities = setOf("order.cancel"),
                    locationIds = setOf(seed.otherLocationId)
                ) + ("Idempotency-Key" to "AUTH-CANCEL-${seed.suffix}")
            )

            val verifiedActor = "verified-${seed.suffix}"
            val fulfillmentKey = "AUTH-FULFILL-${seed.suffix}"
            val fulfillmentHeaders = securityFixture.authorization(
                capabilities = setOf("order.fulfill"),
                locationIds = setOf(seed.locationId),
                subject = verifiedActor
            ) + ("Idempotency-Key" to fulfillmentKey)
            val fulfillmentBody = JsonObject().put("createdBy", "spoofed-body-actor").put("notes", "ship it")
            val fulfilled = http.expect(
                "POST",
                "/api/v1/orders/$orderId/fulfill",
                200,
                fulfillmentBody,
                fulfillmentHeaders
            ).json!!.getJsonObject("data")
            assertEquals("FULFILLED", fulfilled.getString("status"))
            assertEquals(1, movementCount(orderId))
            assertEquals(verifiedActor, movementActor(orderId))
            assertEquals(1, fulfillIdempotencyCount(orderId))

            val replay = http.expect(
                "POST",
                "/api/v1/orders/$orderId/fulfill",
                200,
                fulfillmentBody,
                fulfillmentHeaders
            ).json!!.getJsonObject("data")
            assertEquals(fulfilled, replay)
            assertEquals(1, movementCount(orderId))

            val differentActorReplay = http.request(
                "POST",
                "/api/v1/orders/$orderId/fulfill",
                fulfillmentBody,
                securityFixture.authorization(
                    capabilities = setOf("order.fulfill"),
                    locationIds = setOf(seed.locationId),
                    subject = "different-${seed.suffix}"
                ) + ("Idempotency-Key" to fulfillmentKey)
            )
            assertEquals(409, differentActorReplay.status)
            assertEquals(1, movementCount(orderId))
            assertEquals(1, fulfillIdempotencyCount(orderId))

            val expiredReplay = http.request(
                "POST",
                "/api/v1/orders/$orderId/fulfill",
                fulfillmentBody,
                mapOf(
                    "Authorization" to "Bearer ${securityFixture.token(
                        capabilities = setOf("order.fulfill"),
                        locationIds = setOf(seed.locationId),
                        subject = verifiedActor,
                        expiresAt = Instant.now().epochSecond - 1
                    )}",
                    "Idempotency-Key" to fulfillmentKey
                )
            )
            assertEquals(401, expiredReplay.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(expiredReplay.json), 401, ErrorCodes.UNAUTHENTICATED)

            val scopeLostReplay = http.request(
                "POST",
                "/api/v1/orders/$orderId/fulfill",
                fulfillmentBody,
                securityFixture.authorization(
                    capabilities = setOf("order.fulfill"),
                    locationIds = setOf(seed.otherLocationId),
                    subject = verifiedActor
                ) + ("Idempotency-Key" to fulfillmentKey)
            )
            assertEquals(404, scopeLostReplay.status)
            HttpTestSupport.assertErrorEnvelope(requireNotNull(scopeLostReplay.json), 404, ErrorCodes.RESOURCE_NOT_FOUND)
            assertEquals(1, movementCount(orderId))
            assertEquals(1, fulfillIdempotencyCount(orderId))
        } finally {
            cleanupScopedOrderSeed(seed, orderIds)
        }
    }

    private fun createScopedOrderSeed(): ScopedOrderSeed {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val location = locationRepository.createLocation(
            "AUTH-$suffix",
            "Authentication Location $suffix",
            "WAREHOUSE",
            true,
            JsonObject().put("testRun", suffix)
        ).blockingGet()
        val otherLocation = locationRepository.createLocation(
            "AUTH-OTHER-$suffix",
            "Authentication Other Location $suffix",
            "WAREHOUSE",
            true,
            JsonObject().put("testRun", suffix)
        ).blockingGet()
        val sku = "AUTH-$suffix"
        val product = productRepository.createProduct(
            sku,
            "Authentication Product $suffix",
            "STOCK",
            TestDatabase.SEED_UOM_UNIT,
            true,
            JsonObject().put("testRun", suffix)
        ).blockingGet()
        val seed = ScopedOrderSeed(
            suffix,
            location.getString("locationId"),
            otherLocation.getString("locationId"),
            product.getString("productId"),
            sku,
            "AUTH-STOCK-$suffix"
        )
        insertInventoryMovement(seed, seed.locationId, BigDecimal("10"))
        insertInventoryMovement(seed, seed.otherLocationId, BigDecimal("10"))
        return seed
    }

    private fun insertInventoryMovement(seed: ScopedOrderSeed, locationId: String, quantity: BigDecimal) {
        pool.preparedQuery(
            """
            INSERT INTO inventory_movement (movement_id, product_id, sku, movement_type, from_location_id, to_location_id, quantity, reference_type, reference_id, notes, created_by, created_at)
            VALUES ($1, $2, $3, 'IN', NULL, $4, $5, 'ADJUSTMENT', $6, 'authentication scope seed', 'test', NOW())
            """.trimIndent()
        ).rxExecute(
            Tuple.tuple()
                .addString(UUID.randomUUID().toString())
                .addString(seed.productId)
                .addString(seed.sku)
                .addString(locationId)
                .addValue(quantity)
                .addString(seed.stockReference)
        ).blockingGet()
    }

    private fun cleanupScopedOrderSeed(seed: ScopedOrderSeed, orderIds: Collection<String>) {
        orderIds.forEach { orderId ->
            runCatching { pool.preparedQuery("DELETE FROM payment WHERE sales_order_id = $1").rxExecute(Tuple.of(orderId)).blockingGet() }
            runCatching { pool.preparedQuery("DELETE FROM inventory_movement WHERE reference_id = $1").rxExecute(Tuple.of(orderId)).blockingGet() }
            runCatching { pool.preparedQuery("DELETE FROM sales_order WHERE sales_order_id = $1").rxExecute(Tuple.of(orderId)).blockingGet() }
        }
        runCatching { pool.preparedQuery("DELETE FROM inventory_movement WHERE reference_id = $1").rxExecute(Tuple.of(seed.stockReference)).blockingGet() }
        runCatching { productRepository.deleteProduct(seed.productId).blockingGet() }
        runCatching { locationRepository.deleteLocation(seed.locationId).blockingGet() }
        runCatching { locationRepository.deleteLocation(seed.otherLocationId).blockingGet() }
    }

    private fun orderCount(locationId: String): Long = pool.preparedQuery(
        "SELECT COUNT(*) AS cnt FROM sales_order WHERE location_id = $1"
    ).rxExecute(Tuple.of(locationId)).blockingGet().first().getInteger("cnt").toLong()

    private fun movementCount(orderId: String): Long = pool.preparedQuery(
        "SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_id = $1 AND movement_type = 'OUT'"
    ).rxExecute(Tuple.of(orderId)).blockingGet().first().getInteger("cnt").toLong()

    private fun movementActor(orderId: String): String = pool.preparedQuery(
        "SELECT created_by FROM inventory_movement WHERE reference_id = $1 AND movement_type = 'OUT'"
    ).rxExecute(Tuple.of(orderId)).blockingGet().first().getString("created_by")

    private fun fulfillIdempotencyCount(orderId: String): Long = pool.preparedQuery(
        "SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'fulfillSalesOrder'"
    ).rxExecute(Tuple.of(orderId)).blockingGet().first().getInteger("cnt").toLong()

    private data class ScopedOrderSeed(
        val suffix: String,
        val locationId: String,
        val otherLocationId: String,
        val productId: String,
        val sku: String,
        val stockReference: String
    )
}
