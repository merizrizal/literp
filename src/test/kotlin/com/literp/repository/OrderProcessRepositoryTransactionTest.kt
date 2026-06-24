package com.literp.repository

import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.core.Vertx as RxVertx
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderProcessRepositoryTransactionTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
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
    }

    @AfterAll
    fun tearDown() {
        if (::pool.isInitialized) {
            pool.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun addLineRollsBackWhenOrderRecalcFails() {
        val suffix = suffix()
        val location = createLocation("ADD-$suffix")
        val product = createProduct("ADD-$suffix")
        val order = orderRepository.createSalesOrderDraft("POS", location.getString("locationId"), null, "USD", null).blockingGet()
        val orderId = order.getString("salesOrderId")
        installAddLineFailureTrigger(orderId, suffix)

        try {
            assertFailsWithMessage("forced add-line failure") {
                orderRepository.addSalesOrderLine(orderId, product.getString("productId"), null, 1.toBigDecimal(), 10.toBigDecimal()).blockingGet()
            }

            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_line WHERE sales_order_id = $1", orderId))
            assertTrue(queryDecimal("SELECT total_amount FROM sales_order WHERE sales_order_id = $1", orderId, "total_amount").compareTo(java.math.BigDecimal.ZERO) == 0)
        } finally {
            cleanupTrigger("trg_fail_add_line_$suffix", "fn_fail_add_line_$suffix")
            cleanupOrderGraph(orderId)
            deleteProduct(product.getString("productId"))
            deleteLocation(location.getString("locationId"))
        }
    }

    @Test
    fun confirmRollsBackReservationWritesOnFailure() {
        val seed = createSeedOrder("CONFIRM")
        installReservationFailureTrigger(seed.line2Id, seed.suffix)

        try {
            assertFailsWithMessage("forced confirm failure") {
                orderRepository.confirmSalesOrder(seed.orderId).blockingGet()
            }

            assertEquals("DRAFT", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("PENDING", "PENDING"))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1", seed.orderId))
        } finally {
            cleanupTrigger("trg_fail_confirm_${seed.suffix}", "fn_fail_confirm_${seed.suffix}")
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun fulfillRollsBackMovementWritesOnFailure() {
        val seed = createSeedOrder("FULFILL")
        orderRepository.confirmSalesOrder(seed.orderId).blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()
        installMovementFailureTrigger(seed.orderId, seed.product2Id, seed.suffix)

        try {
            assertFailsWithMessage("forced fulfill failure") {
                orderRepository.fulfillSalesOrder(seed.orderId, "tester", "rollback check").blockingGet()
            }

            assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("RESERVED", "RESERVED"))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1 AND status = 'RESERVED'", seed.orderId))
        } finally {
            cleanupTrigger("trg_fail_fulfill_${seed.suffix}", "fn_fail_fulfill_${seed.suffix}")
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun capturePaymentIsIdempotentForRepeatedRequests() {
        val seed = createSeedOrder("IDEM")
        orderRepository.confirmSalesOrder(seed.orderId).blockingGet()

        try {
            val first = orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "IDEM-${seed.suffix}").blockingGet()
            val second = orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "IDEM-${seed.suffix}").blockingGet()

            assertEquals(first.getJsonObject("payment").getString("paymentId"), second.getJsonObject("payment").getString("paymentId"))
            assertEquals(first.getJsonObject("payment").getString("transactionRef"), second.getJsonObject("payment").getString("transactionRef"))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'capturePayment'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun cancelRollsBackOrderAndLineUpdatesOnFailure() {
        val seed = createSeedOrder("CANCEL")
        installCancelFailureTrigger(seed.line2Id, seed.suffix)

        try {
            assertFailsWithMessage("forced cancel failure") {
                orderRepository.cancelSalesOrder(seed.orderId, "rollback check").blockingGet()
            }

            assertEquals("DRAFT", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("PENDING", "PENDING"))
        } finally {
            cleanupTrigger("trg_fail_cancel_${seed.suffix}", "fn_fail_cancel_${seed.suffix}")
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    private fun createSeedOrder(prefix: String): SeedOrder {
        val suffix = suffix()
        val location = createLocation("$prefix-$suffix")
        val product1 = createProduct("$prefix-A-$suffix")
        val product2 = createProduct("$prefix-B-$suffix")
        val order = orderRepository.createSalesOrderDraft("POS", location.getString("locationId"), null, "USD", null).blockingGet()
        val orderId = order.getString("salesOrderId")
        val line1 = orderRepository.addSalesOrderLine(orderId, product1.getString("productId"), null, 1.toBigDecimal(), 10.toBigDecimal()).blockingGet()
        val line2 = orderRepository.addSalesOrderLine(orderId, product2.getString("productId"), null, 1.toBigDecimal(), 20.toBigDecimal()).blockingGet()

        return SeedOrder(
            suffix = suffix,
            orderId = orderId,
            locationId = location.getString("locationId"),
            product1Id = product1.getString("productId"),
            product2Id = product2.getString("productId"),
            line1Id = line1.getString("lineId"),
            line2Id = line2.getString("lineId")
        )
    }

    private fun createLocation(code: String): JsonObject {
        return locationRepository.createLocation(code, "Test Location $code", "WAREHOUSE", true, JsonObject()).blockingGet()
    }

    private fun createProduct(sku: String): JsonObject {
        return productRepository.createProduct(sku, "Test Product $sku", "STOCK", TestDatabase.SEED_UOM_UNIT, true, JsonObject())
            .blockingGet()
    }

    private fun deleteLocation(locationId: String) {
        locationRepository.deleteLocation(locationId).blockingGet()
    }

    private fun deleteProduct(productId: String) {
        productRepository.deleteProduct(productId).blockingGet()
    }

    private fun installAddLineFailureTrigger(orderId: String, suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_add_line_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced add-line failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_add_line_$suffix
            BEFORE UPDATE OF total_amount ON sales_order
            FOR EACH ROW
            WHEN (NEW.sales_order_id = '$orderId')
            EXECUTE FUNCTION fn_fail_add_line_$suffix();
            """.trimIndent()
        )
    }

    private fun installReservationFailureTrigger(lineId: String, suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_confirm_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced confirm failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_confirm_$suffix
            BEFORE INSERT ON inventory_reservation
            FOR EACH ROW
            WHEN (NEW.sales_order_line_id = '$lineId')
            EXECUTE FUNCTION fn_fail_confirm_$suffix();
            """.trimIndent()
        )
    }

    private fun installMovementFailureTrigger(orderId: String, productId: String, suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_fulfill_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced fulfill failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_fulfill_$suffix
            BEFORE INSERT ON inventory_movement
            FOR EACH ROW
            WHEN (NEW.reference_id = '$orderId' AND NEW.product_id = '$productId')
            EXECUTE FUNCTION fn_fail_fulfill_$suffix();
            """.trimIndent()
        )
    }

    private fun installCancelFailureTrigger(lineId: String, suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_cancel_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced cancel failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_cancel_$suffix
            BEFORE UPDATE ON sales_order_line
            FOR EACH ROW
            WHEN (NEW.line_id = '$lineId' AND NEW.status = 'CANCELLED')
            EXECUTE FUNCTION fn_fail_cancel_$suffix();
            """.trimIndent()
        )
    }

    private fun cleanupTrigger(triggerName: String, functionName: String) {
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON sales_order;") }
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON inventory_reservation;") }
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON inventory_movement;") }
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON sales_order_line;") }
        runCatching { execSql("DROP FUNCTION IF EXISTS $functionName();") }
    }

    private fun cleanupOrderGraph(orderId: String) {
        execSql("DELETE FROM payment WHERE sales_order_id = '$orderId';")
        execSql("DELETE FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = '$orderId';")
        execSql("DELETE FROM sales_order WHERE sales_order_id = '$orderId';")
    }

    private fun assertLineStatuses(orderId: String, expected: List<String>) {
        val actual = pool.preparedQuery(
            "SELECT status FROM sales_order_line WHERE sales_order_id = $1 ORDER BY created_at ASC"
        ).rxExecute(Tuple.of(orderId)).blockingGet().map { it.getString("status") }
        assertEquals(expected, actual)
    }

    private fun countLong(sql: String, id: String): Long {
        return pool.preparedQuery(sql).rxExecute(Tuple.of(id)).blockingGet().first().getInteger("cnt").toLong()
    }

    private fun queryString(sql: String, id: String, column: String): String {
        return pool.preparedQuery(sql).rxExecute(Tuple.of(id)).blockingGet().first().getString(column)
    }

    private fun queryDecimal(sql: String, id: String, column: String): java.math.BigDecimal {
        return pool.preparedQuery(sql).rxExecute(Tuple.of(id)).blockingGet().first().getBigDecimal(column)
    }

    private fun execSql(sql: String) {
        pool.preparedQuery(sql).rxExecute().blockingGet()
    }

    private fun assertFailsWithMessage(messageFragment: String, action: () -> Unit) {
        try {
            action()
            error("Expected failure containing '$messageFragment'")
        } catch (error: Throwable) {
            val message = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(message.contains(messageFragment), "Expected error containing '$messageFragment' but was: $message")
        }
    }

    private fun suffix(): String = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

    private data class SeedOrder(
        val suffix: String,
        val orderId: String,
        val locationId: String,
        val product1Id: String,
        val product2Id: String,
        val line1Id: String,
        val line2Id: String,
    )
}
