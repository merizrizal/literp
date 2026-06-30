package com.literp.verticle

import com.literp.common.ErrorCodes
import com.literp.repository.LocationRepository
import com.literp.repository.OrderProcessRepository
import com.literp.repository.ProductRepository
import com.literp.service.order.impl.OrderProcessServiceImpl
import com.literp.test.HttpResult
import com.literp.test.HttpTestSupport
import com.literp.test.TestDatabase
import com.literp.verticle.handler.OrderProcessHandler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.HttpException
import io.vertx.rxjava3.core.http.HttpServer
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import io.vertx.rxjava3.openapi.contract.OpenAPIContract
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderProcessHttpIntegrationTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var http: HttpTestSupport
    private lateinit var orderRepository: OrderProcessRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var locationRepository: LocationRepository

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)

        orderRepository = OrderProcessRepository(pool)
        productRepository = ProductRepository(pool)
        locationRepository = LocationRepository(pool)

        server = rxVertx.createHttpServer()
            .requestHandler(createRouter())
            .rxListen(0, "127.0.0.1")
            .blockingGet()
        baseUrl = "http://127.0.0.1:${server.actualPort()}/api/v1"
        http = HttpTestSupport(baseUrl)
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.rxClose().blockingAwait()
        }
        if (::pool.isInitialized) {
            pool.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun orderLifecycleHappyPathCoversFulfillmentAndStockEndpoints() {
        val seed = createCatalogSeed("FULFILL")
        var orderId: String? = null

        try {
            val draft = createDraftOrder(seed, "happy path")
            orderId = draft.getString("salesOrderId")
            assertEquals("DRAFT", draft.getString("status"))

            val line = addLine(orderId, seed.productId, 2, 15)
            assertEquals(orderId, line.getString("salesOrderId"))
            assertEquals("PENDING", line.getString("status"))
            assertDecimal("30", line.getValue("lineTotal"))

            val initialCurrentStock = expect("GET", "/stock/current?productId=${seed.productId}&locationId=${seed.locationId}", 200)
                .json!!.getJsonObject("data")
            assertStock(initialCurrentStock, seed.productId, seed.locationId, "CURRENT", "10")

            val confirmed = expect(
                "POST",
                "/orders/$orderId/confirm",
                200,
                headers = mapOf("Idempotency-Key" to "CONFIRM-${seed.suffix}")
            ).json!!.getJsonObject("data")
            assertEquals("CONFIRMED", confirmed.getString("status"))
            assertEquals(1, confirmed.getInteger("reservedLineCount"))

            val availableAfterConfirm = expect("GET", "/stock/available?productId=${seed.productId}&locationId=${seed.locationId}", 200)
                .json!!.getJsonObject("data")
            assertStock(availableAfterConfirm, seed.productId, seed.locationId, "AVAILABLE", "8")

            val paymentBody = JsonObject()
                .put("paymentMethod", "CARD")
                .put("amount", 30)
                .put("transactionRef", "TXN-${seed.suffix}")
            val paymentHeaders = mapOf("Idempotency-Key" to "PAY-${seed.suffix}")
            val payment = expect("POST", "/orders/$orderId/payments", 201, paymentBody, paymentHeaders)
                .json!!.getJsonObject("data")
            val repeatedPayment = expect("POST", "/orders/$orderId/payments", 201, paymentBody, paymentHeaders)
                .json!!.getJsonObject("data")
            assertEquals(
                payment.getJsonObject("payment").getString("paymentId"),
                repeatedPayment.getJsonObject("payment").getString("paymentId")
            )
            assertDecimal("30", payment.getValue("totalCaptured"))

            val fulfilled = expect(
                "POST",
                "/orders/$orderId/fulfill",
                200,
                JsonObject().put("createdBy", "http-test").put("notes", "ship now"),
                mapOf("Idempotency-Key" to "FULFILL-${seed.suffix}")
            ).json!!.getJsonObject("data")
            assertEquals("FULFILLED", fulfilled.getString("status"))
            assertEquals(1, fulfilled.getInteger("fulfilledLineCount"))

            val order = expect("GET", "/orders/$orderId", 200).json!!.getJsonObject("data")
            assertEquals("FULFILLED", order.getString("status"))
            assertEquals(1, order.getJsonArray("lines").size())
            assertEquals(1, order.getJsonArray("payments").size())
            assertEquals(1, order.getJsonArray("reservations").size())
            assertEquals("FULFILLED", order.getJsonArray("lines").getJsonObject(0).getString("status"))
            assertEquals("FULFILLED", order.getJsonArray("reservations").getJsonObject(0).getString("status"))

            val finalCurrentStock = expect("GET", "/stock/current?productId=${seed.productId}&locationId=${seed.locationId}", 200)
                .json!!.getJsonObject("data")
            val finalAvailableStock = expect("GET", "/stock/available?productId=${seed.productId}&locationId=${seed.locationId}", 200)
                .json!!.getJsonObject("data")
            assertStock(finalCurrentStock, seed.productId, seed.locationId, "CURRENT", "8")
            assertStock(finalAvailableStock, seed.productId, seed.locationId, "AVAILABLE", "8")
        } finally {
            cleanup(seed, orderId)
        }
    }

    @Test
    fun orderCancellationHappyPathCancelsConfirmedOrderWithoutPayment() {
        val seed = createCatalogSeed("CANCEL")
        var orderId: String? = null

        try {
            orderId = createDraftOrder(seed, "cancel path").getString("salesOrderId")
            addLine(orderId, seed.productId, 1, 12)
            expect(
                "POST",
                "/orders/$orderId/confirm",
                200,
                headers = mapOf("Idempotency-Key" to "CONFIRM-${seed.suffix}")
            )

            val cancelled = expect(
                "POST",
                "/orders/$orderId/cancel",
                200,
                JsonObject().put("reason", "customer changed mind"),
                mapOf("Idempotency-Key" to "CANCEL-${seed.suffix}")
            ).json!!.getJsonObject("data")
            assertEquals("CANCELLED", cancelled.getString("status"))

            val order = expect("GET", "/orders/$orderId", 200).json!!.getJsonObject("data")
            assertEquals("CANCELLED", order.getString("status"))
            assertEquals("CANCELLED", order.getJsonArray("lines").getJsonObject(0).getString("status"))
            assertEquals("CANCELLED", order.getJsonArray("reservations").getJsonObject(0).getString("status"))
        } finally {
            cleanup(seed, orderId)
        }
    }

    @Test
    fun orderLifecycleGuardrailsReturnHttpErrors() {
        val seed = createCatalogSeed("GUARD")
        var orderId: String? = null

        try {
            orderId = createDraftOrder(seed, "guard path").getString("salesOrderId")
            addLine(orderId, seed.productId, 2, 15)

            expect("POST", "/orders/$orderId/confirm", 400)
            expect(
                "POST",
                "/orders/$orderId/confirm",
                200,
                headers = mapOf("Idempotency-Key" to "CONFIRM-${seed.suffix}")
            )

            expect(
                "POST",
                "/orders/$orderId/lines",
                409,
                JsonObject()
                    .put("productId", seed.productId)
                    .put("quantityOrdered", 1)
                    .put("unitPrice", 10)
            )
            expect(
                "POST",
                "/orders/$orderId/fulfill",
                409,
                JsonObject().put("createdBy", "http-test"),
                mapOf("Idempotency-Key" to "FULFILL-LOW-${seed.suffix}")
            )
            expect(
                "POST",
                "/orders/$orderId/payments",
                400,
                JsonObject()
                    .put("paymentMethod", "CARD")
                    .put("amount", 0)
                    .put("transactionRef", "TXN-ZERO-${seed.suffix}"),
                mapOf("Idempotency-Key" to "PAY-ZERO-${seed.suffix}")
            )
            expect(
                "POST",
                "/orders/$orderId/payments",
                201,
                JsonObject()
                    .put("paymentMethod", "CARD")
                    .put("amount", 10)
                    .put("transactionRef", "TXN-PARTIAL-${seed.suffix}"),
                mapOf("Idempotency-Key" to "PAY-PARTIAL-${seed.suffix}")
            )
            expect(
                "POST",
                "/orders/$orderId/cancel",
                409,
                JsonObject().put("reason", "cannot cancel paid order"),
                mapOf("Idempotency-Key" to "CANCEL-${seed.suffix}")
            )
        } finally {
            cleanup(seed, orderId)
        }
    }

    private fun createRouter(): Router {
        val orderHandler = OrderProcessHandler(OrderProcessServiceImpl(orderRepository))
        val orderContract = OpenAPIContract.rxFrom(rxVertx, "api_collections/open_api_spec/order-process.yaml").blockingGet()
        val orderRouterBuilder = RouterBuilder.create(rxVertx, orderContract)

        orderRouterBuilder.getRoute("listSalesOrders").addHandler(orderHandler::listSalesOrders)
        orderRouterBuilder.getRoute("createSalesOrderDraft").addHandler(orderHandler::createSalesOrderDraft)
        orderRouterBuilder.getRoute("getSalesOrder").addHandler(orderHandler::getSalesOrder)
        orderRouterBuilder.getRoute("getCurrentStock").addHandler(orderHandler::getCurrentStock)
        orderRouterBuilder.getRoute("getAvailableStock").addHandler(orderHandler::getAvailableStock)
        orderRouterBuilder.getRoute("addSalesOrderLine").addHandler(orderHandler::addSalesOrderLine)
        orderRouterBuilder.getRoute("confirmSalesOrder").addHandler(orderHandler::confirmSalesOrder)
        orderRouterBuilder.getRoute("capturePayment").addHandler(orderHandler::capturePayment)
        orderRouterBuilder.getRoute("fulfillSalesOrder").addHandler(orderHandler::fulfillSalesOrder)
        orderRouterBuilder.getRoute("cancelSalesOrder").addHandler(orderHandler::cancelSalesOrder)

        return Router.router(rxVertx).apply {
            route().failureHandler { context ->
                val failure = context.failure()
                val statusCode = when {
                    failure is HttpException -> failure.statusCode
                    context.statusCode() > 0 -> context.statusCode()
                    else -> 500
                }
                context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Content-Type", "application/json")
                    .end(
                        JsonObject()
                            .put("error", failure?.message ?: "Bad request")
                            .put("errorCode", ErrorCodes.fromStatus(statusCode))
                            .put("status", statusCode)
                            .put("errorId", UUID.randomUUID().toString())
                            .encode()
                    )
            }
            route("/api/v1/*").subRouter(orderRouterBuilder.createRouter())
        }
    }

    private fun createCatalogSeed(prefix: String): CatalogSeed {
        val suffix = suffix()
        val location = locationRepository.createLocation(
            "OH-$prefix-$suffix",
            "Order HTTP $prefix Location $suffix",
            "WAREHOUSE",
            true,
            JsonObject().put("testRun", suffix)
        ).blockingGet()
        val sku = "OH-$prefix-$suffix"
        val product = productRepository.createProduct(
            sku,
            "Order HTTP $prefix Product $suffix",
            "STOCK",
            TestDatabase.SEED_UOM_UNIT,
            true,
            JsonObject().put("testRun", suffix)
        ).blockingGet()
        val seed = CatalogSeed(
            suffix = suffix,
            locationId = location.getString("locationId"),
            productId = product.getString("productId"),
            sku = sku,
            stockReference = "OH-STOCK-$prefix-$suffix"
        )
        insertInventoryMovement(seed.productId, seed.sku, seed.locationId, BigDecimal("10"), seed.stockReference)
        return seed
    }

    private fun createDraftOrder(seed: CatalogSeed, notes: String): JsonObject {
        return expect(
            "POST",
            "/orders",
            201,
            JsonObject()
                .put("salesChannel", "POS")
                .put("locationId", seed.locationId)
                .put("currency", "USD")
                .put("notes", notes)
        ).json!!.getJsonObject("data")
    }

    private fun addLine(orderId: String, productId: String, quantity: Number, unitPrice: Number): JsonObject {
        return expect(
            "POST",
            "/orders/$orderId/lines",
            201,
            JsonObject()
                .put("productId", productId)
                .put("quantityOrdered", quantity)
                .put("unitPrice", unitPrice)
        ).json!!.getJsonObject("data")
    }

    private fun insertInventoryMovement(productId: String, sku: String, locationId: String, quantity: BigDecimal, referenceId: String) {
        pool.preparedQuery(
            """
            INSERT INTO inventory_movement (movement_id, product_id, sku, movement_type, from_location_id, to_location_id, quantity, reference_type, reference_id, notes, created_by, created_at)
            VALUES ($1, $2, $3, 'IN', NULL, $4, $5, 'ADJUSTMENT', $6, 'order HTTP lifecycle seed', 'test', NOW())
            """.trimIndent()
        ).rxExecute(
            Tuple.tuple()
                .addString(UUID.randomUUID().toString())
                .addString(productId)
                .addString(sku)
                .addString(locationId)
                .addValue(quantity)
                .addString(referenceId)
        ).blockingGet()
    }

    private fun cleanup(seed: CatalogSeed, orderId: String?) {
        if (orderId != null) {
            runCatching { cleanupOrderGraph(orderId) }
        }
        runCatching { cleanupInventoryMovements(seed.stockReference) }
        runCatching { deleteProduct(seed.productId) }
        runCatching { deleteLocation(seed.locationId) }
    }

    private fun cleanupOrderGraph(orderId: String) {
        execSql("DELETE FROM payment WHERE sales_order_id = '$orderId';")
        cleanupInventoryMovements(orderId)
        execSql("DELETE FROM sales_order WHERE sales_order_id = '$orderId';")
    }

    private fun cleanupInventoryMovements(referenceId: String) {
        pool.preparedQuery("DELETE FROM inventory_movement WHERE reference_id = $1")
            .rxExecute(Tuple.of(referenceId))
            .blockingGet()
    }

    private fun deleteProduct(productId: String) {
        productRepository.deleteProduct(productId).blockingGet()
    }

    private fun deleteLocation(locationId: String) {
        locationRepository.deleteLocation(locationId).blockingGet()
    }

    private fun execSql(sql: String) {
        pool.preparedQuery(sql).rxExecute().blockingGet()
    }

    private fun expect(
        method: String,
        path: String,
        status: Int,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResult = http.expect(method, path, status, body, headers)

    private fun assertStock(stock: JsonObject, productId: String, locationId: String, quantityType: String, expectedQuantity: String) {
        assertEquals(productId, stock.getString("productId"))
        assertEquals(locationId, stock.getString("locationId"))
        assertEquals(quantityType, stock.getString("quantityType"))
        assertDecimal(expectedQuantity, stock.getValue("quantity"))
    }

    private fun assertDecimal(expected: String, actual: Any?) {
        assertNotNull(actual, "Expected decimal value")
        val actualDecimal = actual.toString().toBigDecimal()
        assertTrue(
            actualDecimal.compareTo(expected.toBigDecimal()) == 0,
            "Expected $expected but was $actualDecimal"
        )
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

    private data class CatalogSeed(
        val suffix: String,
        val locationId: String,
        val productId: String,
        val sku: String,
        val stockReference: String
    )
}
