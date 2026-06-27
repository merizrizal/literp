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
                orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
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
    fun confirmSalesOrderIsIdempotentAndWritesAuditEvent() {
        val seed = createSeedOrder("CONFIDEM")

        try {
            val first = orderRepository.confirmSalesOrder(seed.orderId, "CONFIDEM-${seed.suffix}").blockingGet()
            val second = orderRepository.confirmSalesOrder(seed.orderId, "CONFIDEM-${seed.suffix}").blockingGet()

            assertEquals("CONFIRMED", first.getString("status"))
            assertEquals(first.getString("salesOrderId"), second.getString("salesOrderId"))
            assertEquals(first.getString("status"), second.getString("status"))
            assertEquals(first.getInteger("reservedLineCount"), second.getInteger("reservedLineCount"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'confirmSalesOrder'", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_event WHERE sales_order_id = $1 AND event_type = 'ORDER_CONFIRMED'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun fulfillRollsBackMovementWritesOnFailure() {
        val seed = createSeedOrder("FULFILL")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()
        installMovementFailureTrigger(seed.orderId, seed.product2Id, seed.suffix)

        try {
            assertFailsWithMessage("forced fulfill failure") {
                orderRepository.fulfillSalesOrder(seed.orderId, "tester", "rollback check", "FULFILL-${seed.suffix}").blockingGet()
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
    fun fulfillSalesOrderIsIdempotentAndWritesAuditEvent() {
        val seed = createSeedOrder("FULIDEM")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()

        try {
            val first = orderRepository.fulfillSalesOrder(seed.orderId, "tester", "ship now", "FULIDEM-${seed.suffix}").blockingGet()
            val second = orderRepository.fulfillSalesOrder(seed.orderId, "tester", "ship now", "FULIDEM-${seed.suffix}").blockingGet()

            assertEquals("FULFILLED", first.getString("status"))
            assertEquals(first.getString("salesOrderId"), second.getString("salesOrderId"))
            assertEquals(first.getString("status"), second.getString("status"))
            assertEquals(first.getInteger("fulfilledLineCount"), second.getInteger("fulfilledLineCount"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1 AND to_location_id IS NULL", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1 AND status = 'FULFILLED'", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'fulfillSalesOrder'", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_event WHERE sales_order_id = $1 AND event_type = 'ORDER_FULFILLED'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun draftSalesOrderCanBeCancelledWithoutReservationsOrMovements() {
        val seed = createSeedOrder("DRAFTCAN")

        try {
            val result = orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "DRAFTCAN-${seed.suffix}").blockingGet()

            assertEquals("CANCELLED", result.getString("status"))
            assertEquals("CANCELLED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("CANCELLED", "CANCELLED"))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1", seed.orderId))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun confirmedSalesOrderCanBeCancelledWithoutCapturedPayment() {
        val seed = createSeedOrder("CONFCAN")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

        try {
            val result = orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "CONFCAN-${seed.suffix}").blockingGet()

            assertEquals("CANCELLED", result.getString("status"))
            assertEquals("CANCELLED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("CANCELLED", "CANCELLED"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1 AND status = 'CANCELLED'", seed.orderId))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun confirmedSalesOrderCanBeFulfilledWithSplitCapturesAndWritesExplicitOutMovements() {
        val seed = createSeedOrder("FULSPLIT")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
        orderRepository.capturePayment(seed.orderId, "CARD", 10.toBigDecimal(), "TXN1-${seed.suffix}", "PAY1-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 20.toBigDecimal(), "TXN2-${seed.suffix}", "PAY2-${seed.suffix}").blockingGet()

        try {
            val totalCaptured = queryDecimal(
                "SELECT COALESCE(SUM(amount), 0) AS total_captured FROM payment WHERE sales_order_id = $1 AND status = 'CAPTURED'",
                seed.orderId,
                "total_captured"
            )

            assertTrue(totalCaptured.compareTo(30.toBigDecimal()) == 0)

            val result = orderRepository.fulfillSalesOrder(seed.orderId, "tester", "ship now", "FULSPLIT-${seed.suffix}").blockingGet()

            assertEquals("FULFILLED", result.getString("status"))
            assertEquals("FULFILLED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("FULFILLED", "FULFILLED"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1 AND to_location_id IS NULL", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement im JOIN sales_order so ON so.sales_order_id = im.reference_id WHERE im.reference_type = 'SALES_ORDER' AND im.reference_id = $1 AND im.from_location_id = so.location_id", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1 AND status = 'CAPTURED'", seed.orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1 AND status = 'FULFILLED'", seed.orderId))

            val product1Stock = orderRepository.getCurrentStock(seed.product1Id, seed.locationId).blockingGet()
            val product2Stock = orderRepository.getCurrentStock(seed.product2Id, seed.locationId).blockingGet()
            assertStockQuantity(product1Stock, seed.product1Id, seed.locationId, "CURRENT", 99.toBigDecimal())
            assertStockQuantity(product2Stock, seed.product2Id, seed.locationId, "CURRENT", 99.toBigDecimal())
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun addLineRejectsWhenOrderIsNotDraft() {
        val seed = createSeedOrder("ADDLINE")

        try {
            orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

            assertFailsWithMessage("Order line can only be added to DRAFT orders") {
                orderRepository.addSalesOrderLine(seed.orderId, seed.product1Id, null, 1.toBigDecimal(), 10.toBigDecimal()).blockingGet()
            }

            assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_line WHERE sales_order_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun confirmRejectsWhenOrderIsNotDraft() {
        val seed = createSeedOrder("CONFNON")

        try {
            orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

            assertFailsWithMessage("Only DRAFT orders can be confirmed") {
                orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM2-${seed.suffix}").blockingGet()
            }

            assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun fulfillRejectsWhenCapturedPaymentDoesNotCoverOrderTotal() {
        val seed = createSeedOrder("FULLOW")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 10.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()

        try {
            assertFailsWithMessage("Insufficient captured payment for fulfillment") {
                orderRepository.fulfillSalesOrder(seed.orderId, "tester", "ship now", "FULLOW-${seed.suffix}").blockingGet()
            }

            assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("RESERVED", "RESERVED"))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1 AND status = 'CAPTURED'", seed.orderId))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_movement WHERE reference_type = 'SALES_ORDER' AND reference_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun cancelRejectsFulfilledOrders() {
        val seed = createSeedOrder("CANCF")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()
        orderRepository.fulfillSalesOrder(seed.orderId, "tester", "ship now", "FUL-${seed.suffix}").blockingGet()

        try {
            assertFailsWithMessage("Cannot cancel a fulfilled order") {
                orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "CANCF-${seed.suffix}").blockingGet()
            }

            assertEquals("FULFILLED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_event WHERE sales_order_id = $1 AND event_type = 'ORDER_CANCELLED'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun cancelRejectsOrdersWithCapturedPayment() {
        val seed = createSeedOrder("CANPAY")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
        orderRepository.capturePayment(seed.orderId, "CARD", 30.toBigDecimal(), "TXN-${seed.suffix}", "PAY-${seed.suffix}").blockingGet()

        try {
            assertFailsWithMessage("Cannot cancel order with captured payment") {
                orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "CANPAY-${seed.suffix}").blockingGet()
            }

            assertEquals("CONFIRMED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("RESERVED", "RESERVED"))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1 AND status = 'CAPTURED'", seed.orderId))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_event WHERE sales_order_id = $1 AND event_type = 'ORDER_CANCELLED'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun capturePaymentRejectsInvalidAmountsAndBlankIdempotencyKey() {
        val seed = createSeedOrder("PAYGUARD")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

        try {
            assertFailsWithMessage("amount must be > 0") {
                orderRepository.capturePayment(seed.orderId, "CARD", 0.toBigDecimal(), "TXN0-${seed.suffix}", "PAY0-${seed.suffix}").blockingGet()
            }
            assertFailsWithMessage("amount must be > 0") {
                orderRepository.capturePayment(seed.orderId, "CARD", (-5).toBigDecimal(), "TXNNEG-${seed.suffix}", "PAYNEG-${seed.suffix}").blockingGet()
            }
            assertFailsWithMessage("Idempotency-Key is required") {
                orderRepository.capturePayment(seed.orderId, "CARD", 5.toBigDecimal(), "TXNBLANK-${seed.suffix}", "  ").blockingGet()
            }

            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun capturePaymentRejectsIdempotencyKeyConflict() {
        val seed = createSeedOrder("PAYCON")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

        try {
            val first = orderRepository.capturePayment(seed.orderId, "CARD", 10.toBigDecimal(), "TXN-${seed.suffix}", "PAYCON-${seed.suffix}").blockingGet()
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", seed.orderId))

            assertFailsWithMessage("Idempotency key conflict") {
                orderRepository.capturePayment(seed.orderId, "CARD", 20.toBigDecimal(), "TXN-${seed.suffix}", "PAYCON-${seed.suffix}").blockingGet()
            }

            assertEquals(first.getJsonObject("payment").getString("paymentId"), queryString("SELECT payment_id FROM payment WHERE sales_order_id = $1", seed.orderId, "payment_id"))
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
    fun capturePaymentIsIdempotentForRepeatedRequests() {
        val seed = createSeedOrder("IDEM")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

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
                orderRepository.cancelSalesOrder(seed.orderId, "rollback check", "CANCEL-${seed.suffix}").blockingGet()
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

    @Test
    fun cancelSalesOrderIsIdempotentAndWritesAuditEvent() {
        val seed = createSeedOrder("CANIDEM")
        orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

        try {
            val first = orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "CANIDEM-${seed.suffix}").blockingGet()
            val second = orderRepository.cancelSalesOrder(seed.orderId, "customer changed mind", "CANIDEM-${seed.suffix}").blockingGet()

            assertEquals("CANCELLED", first.getString("status"))
            assertEquals(first.getString("salesOrderId"), second.getString("salesOrderId"))
            assertEquals(first.getString("status"), second.getString("status"))
            assertEquals("CANCELLED", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("CANCELLED", "CANCELLED"))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1 AND status = 'CANCELLED'", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'cancelSalesOrder'", seed.orderId))
            assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM sales_order_event WHERE sales_order_id = $1 AND event_type = 'ORDER_CANCELLED'", seed.orderId))
        } finally {
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun currentStockCombinesInboundTransfersAndOutboundMovements() {
        val suffix = suffix()
        val referenceId = "STOCK-$suffix"
        val sourceLocation = createLocation("STK-A-$suffix")
        val targetLocation = createLocation("STK-B-$suffix")
        val sku = "STK-$suffix"
        val product = createProduct(sku)
        val productId = product.getString("productId")
        val sourceLocationId = sourceLocation.getString("locationId")
        val targetLocationId = targetLocation.getString("locationId")

        try {
            insertInventoryMovement(productId, sku, "IN", null, sourceLocationId, 10.toBigDecimal(), referenceId)
            insertInventoryMovement(productId, sku, "TRANSFER", sourceLocationId, targetLocationId, 3.toBigDecimal(), referenceId)
            insertInventoryMovement(productId, sku, "OUT", sourceLocationId, null, 2.toBigDecimal(), referenceId)

            val sourceStock = orderRepository.getCurrentStock(productId, sourceLocationId).blockingGet()
            val targetStock = orderRepository.getCurrentStock(productId, targetLocationId).blockingGet()

            assertStockQuantity(sourceStock, productId, sourceLocationId, "CURRENT", 5.toBigDecimal())
            assertStockQuantity(targetStock, productId, targetLocationId, "CURRENT", 3.toBigDecimal())
        } finally {
            cleanupInventoryMovements(referenceId)
            deleteProduct(productId)
            deleteLocation(sourceLocationId)
            deleteLocation(targetLocationId)
        }
    }

    @Test
    fun availableStockSubtractsReservedQuantity() {
        val seed = createSeedOrder("STOCKAVL", seedStock = false)
        val referenceId = "STOCKAVL-${seed.suffix}"
        val sku1 = queryString("SELECT sku FROM product WHERE product_id = $1", seed.product1Id, "sku")
        val sku2 = queryString("SELECT sku FROM product WHERE product_id = $1", seed.product2Id, "sku")

        try {
            insertInventoryMovement(seed.product1Id, sku1, "IN", null, seed.locationId, 5.toBigDecimal(), referenceId)
            insertInventoryMovement(seed.product2Id, sku2, "IN", null, seed.locationId, 5.toBigDecimal(), referenceId)
            orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()

            val availableStock = orderRepository.getAvailableStock(seed.product1Id, seed.locationId).blockingGet()

            assertStockQuantity(availableStock, seed.product1Id, seed.locationId, "AVAILABLE", 4.toBigDecimal())
        } finally {
            cleanupInventoryMovements(referenceId)
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    @Test
    fun confirmRejectsWhenAvailableStockIsInsufficient() {
        val seed = createSeedOrder("STOCKLOW", seedStock = false)
        val referenceId = "STOCKLOW-${seed.suffix}"
        val sku = queryString("SELECT sku FROM product WHERE product_id = $1", seed.product1Id, "sku")

        try {
            insertInventoryMovement(seed.product1Id, sku, "IN", null, seed.locationId, 0.toBigDecimal(), referenceId)

            assertFailsWithMessage("Insufficient available stock") {
                orderRepository.confirmSalesOrder(seed.orderId, "CONFIRM-${seed.suffix}").blockingGet()
            }

            assertEquals("DRAFT", queryString("SELECT status FROM sales_order WHERE sales_order_id = $1", seed.orderId, "status"))
            assertLineStatuses(seed.orderId, listOf("PENDING", "PENDING"))
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM inventory_reservation WHERE sales_order_id = $1", seed.orderId))
        } finally {
            cleanupInventoryMovements(referenceId)
            cleanupOrderGraph(seed.orderId)
            deleteProduct(seed.product1Id)
            deleteProduct(seed.product2Id)
            deleteLocation(seed.locationId)
        }
    }

    private fun createSeedOrder(prefix: String, seedStock: Boolean = true): SeedOrder {
        val suffix = suffix()
        val location = createLocation("$prefix-$suffix")
        val sku1 = "$prefix-A-$suffix"
        val sku2 = "$prefix-B-$suffix"
        val product1 = createProduct(sku1)
        val product2 = createProduct(sku2)
        val order = orderRepository.createSalesOrderDraft("POS", location.getString("locationId"), null, "USD", null).blockingGet()
        val orderId = order.getString("salesOrderId")
        val line1 = orderRepository.addSalesOrderLine(orderId, product1.getString("productId"), null, 1.toBigDecimal(), 10.toBigDecimal()).blockingGet()
        val line2 = orderRepository.addSalesOrderLine(orderId, product2.getString("productId"), null, 1.toBigDecimal(), 20.toBigDecimal()).blockingGet()

        if (seedStock) {
            insertInventoryMovement(product1.getString("productId"), sku1, "IN", null, location.getString("locationId"), 100.toBigDecimal(), orderId)
            insertInventoryMovement(product2.getString("productId"), sku2, "IN", null, location.getString("locationId"), 100.toBigDecimal(), orderId)
        }

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

    private fun insertInventoryMovement(
        productId: String,
        sku: String,
        movementType: String,
        fromLocationId: String?,
        toLocationId: String?,
        quantity: java.math.BigDecimal,
        referenceId: String
    ) {
        val referenceType = when (movementType) {
            "TRANSFER" -> "TRANSFER"
            "OUT" -> "SALES_ORDER"
            else -> "ADJUSTMENT"
        }
        pool.preparedQuery(
            """
            INSERT INTO inventory_movement (movement_id, product_id, sku, movement_type, from_location_id, to_location_id, quantity, reference_type, reference_id, notes, created_by, created_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'stock calculation test', 'test', NOW())
            """.trimIndent()
        ).rxExecute(
            Tuple.tuple()
                .addString(UUID.randomUUID().toString())
                .addString(productId)
                .addString(sku)
                .addString(movementType)
                .addValue(fromLocationId)
                .addValue(toLocationId)
                .addValue(quantity)
                .addString(referenceType)
                .addString(referenceId)
        ).blockingGet()
    }

    private fun cleanupInventoryMovements(referenceId: String) {
        pool.preparedQuery("DELETE FROM inventory_movement WHERE reference_id = $1")
            .rxExecute(Tuple.of(referenceId))
            .blockingGet()
    }

    private fun assertStockQuantity(
        stock: JsonObject,
        productId: String,
        locationId: String,
        quantityType: String,
        expectedQuantity: java.math.BigDecimal
    ) {
        assertEquals(productId, stock.getString("productId"))
        assertEquals(locationId, stock.getString("locationId"))
        assertEquals(quantityType, stock.getString("quantityType"))
        val actualQuantity = stock.getValue("quantity").toString().toBigDecimal()
        assertTrue(
            actualQuantity.compareTo(expectedQuantity) == 0,
            "Expected $expectedQuantity but was $actualQuantity"
        )
    }

    private fun cleanupOrderGraph(orderId: String) {
        execSql("DELETE FROM payment WHERE sales_order_id = '$orderId';")
        cleanupInventoryMovements(orderId)
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
