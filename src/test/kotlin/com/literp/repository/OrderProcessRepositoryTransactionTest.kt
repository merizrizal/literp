package com.literp.repository

import com.literp.common.ErrorCodes
import com.literp.test.TestDatabase
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderProcessRepositoryTransactionTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var pool: Pool
    private lateinit var orderRepository: OrderProcessRepository
    private lateinit var posOperationsRepository: PosOperationsRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var locationRepository: LocationRepository

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        pool = TestDatabase.createPool(rxVertx)
        TestDatabase.assumeAvailable(pool)

        orderRepository = OrderProcessRepository(pool)
        posOperationsRepository = PosOperationsRepository(pool)
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
    fun attributedDraftPersistsContextAndRollsBackOrderOnContextFailure() {
        val suffix = suffix()
        val locationId = createLocation("POSCTX-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSCTX-$suffix")
        val actorSubject = "pos-draft-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        var attributedOrderId: String? = null
        var contextlessOrderId: String? = null

        try {
            val attributed = orderRepository.createAttributedSalesOrderDraft(
                "POS",
                locationId,
                null,
                "USD",
                "attributed draft",
                shiftId,
                actorSubject
            ).blockingGet()
            attributedOrderId = attributed.getString("salesOrderId")
            val posContext = attributed.getJsonObject("posContext")
            assertEquals(shiftId, posContext.getString("shiftId"))
            assertEquals(actorSubject, posContext.getString("draftOperatorId"))
            assertEquals(1L, countLong(
                "SELECT COUNT(*) AS cnt FROM pos_order_context WHERE sales_order_id = $1",
                attributedOrderId
            ))

            val readBack = orderRepository.getSalesOrder(attributedOrderId).blockingGet()
            assertEquals(shiftId, readBack.getJsonObject("posContext").getString("shiftId"))
            assertEquals(actorSubject, readBack.getJsonObject("posContext").getString("draftOperatorId"))

            val contextless = orderRepository
                .createSalesOrderDraft("POS", locationId, null, "USD", "legacy draft")
                .blockingGet()
            contextlessOrderId = contextless.getString("salesOrderId")
            assertEquals(null, contextless.getValue("posContext"))

            val orderCountBeforeFailure = countLong(
                "SELECT COUNT(*) AS cnt FROM sales_order WHERE location_id = $1",
                locationId
            )
            installContextFailureTrigger(suffix)
            assertFailsWithMessage("forced POS context failure") {
                orderRepository.createAttributedSalesOrderDraft(
                    "POS",
                    locationId,
                    null,
                    "USD",
                    "must roll back",
                    shiftId,
                    actorSubject
                ).blockingGet()
            }
            assertEquals(
                orderCountBeforeFailure,
                countLong("SELECT COUNT(*) AS cnt FROM sales_order WHERE location_id = $1", locationId)
            )
        } finally {
            cleanupTrigger("trg_fail_pos_context_$suffix", "fn_fail_pos_context_$suffix")
            contextlessOrderId?.let(::cleanupOrderGraph)
            attributedOrderId?.let(::cleanupOrderGraph)
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun attributedDraftRejectsForeignClosedOwnerAndCurrencyContexts() {
        val suffix = suffix()
        val locationId = createLocation("POSRULE-$suffix").getString("locationId")
        val foreignLocationId = createLocation("POSFOREIGN-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSRULE-$suffix")
        val actorSubject = "pos-rule-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        val closedShiftId = createShift(terminalId, actorSubject, "USD", "CLOSED", 2)

        try {
            assertFailsWithMessage("POS shift owner mismatch") {
                orderRepository.createAttributedSalesOrderDraft(
                    "POS", locationId, null, "USD", null, shiftId, "different-owner"
                ).blockingGet()
            }
            assertFailsWithMessage("POS shift is not open") {
                orderRepository.createAttributedSalesOrderDraft(
                    "POS", locationId, null, "USD", null, closedShiftId, actorSubject
                ).blockingGet()
            }
            assertFailsWithMessage("POS shift currency does not match") {
                orderRepository.createAttributedSalesOrderDraft(
                    "POS", locationId, null, "EUR", null, shiftId, actorSubject
                ).blockingGet()
            }
            assertFailsWithMessage(ErrorCodes.fromStatus(404)) {
                orderRepository.createAttributedSalesOrderDraft(
                    "POS", foreignLocationId, null, "USD", null, shiftId, actorSubject
                ).blockingGet()
            }
        } finally {
            deleteShift(closedShiftId)
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(foreignLocationId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun attributedCommandsPersistPaymentContextAndAllowAuthorizedReplayAfterClose() {
        val suffix = suffix()
        val locationId = createLocation("POSCMD-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSCMD-$suffix")
        val actorSubject = "pos-command-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        val closeKey = "POSCMD-CLOSE-$suffix"
        val closeOrganizationId = "repo-pos-close-$suffix"
        val stockReference = "POSCMD-STOCK-$suffix"
        var orderId: String? = null

        try {
            val draft = orderRepository.createAttributedSalesOrderDraft(
                "POS",
                locationId,
                null,
                "USD",
                "attributed command test",
                shiftId,
                actorSubject
            ).blockingGet()
            orderId = draft.getString("salesOrderId")
            val product = createProduct("POSCMD-PRODUCT-$suffix")
            insertInventoryMovement(
                product.getString("productId"),
                "POSCMD-PRODUCT-$suffix",
                "IN",
                null,
                locationId,
                10.toBigDecimal(),
                stockReference
            )
            try {
                orderRepository.addSalesOrderLineWithActor(
                    orderId,
                    product.getString("productId"),
                    null,
                    3.toBigDecimal(),
                    10.toBigDecimal(),
                    actorSubject,
                    true,
                    true
                ).blockingGet()
                orderRepository.confirmSalesOrderWithActor(
                    orderId,
                    "POSCMD-CONFIRM-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()

                val firstPayment = orderRepository.capturePaymentWithActor(
                    orderId,
                    "CASH",
                    30.toBigDecimal(),
                    "POSCMD-CASH-$suffix",
                    "POSCMD-PAY-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()
                val replay = orderRepository.capturePaymentWithActor(
                    orderId,
                    "CASH",
                    30.toBigDecimal(),
                    "POSCMD-CASH-$suffix",
                    "POSCMD-PAY-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()
                val paymentId = firstPayment.getJsonObject("payment").getString("paymentId")

                assertEquals(paymentId, replay.getJsonObject("payment").getString("paymentId"))
                assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", orderId))
                assertEquals(1L, countLong("SELECT COUNT(*) AS cnt FROM pos_payment_context WHERE payment_id = $1", paymentId))
                assertEquals(shiftId, queryString("SELECT shift_id FROM pos_payment_context WHERE payment_id = $1", paymentId, "shift_id"))
                assertEquals(actorSubject, queryString("SELECT capture_operator_id FROM pos_payment_context WHERE payment_id = $1", paymentId, "capture_operator_id"))

                val closed = posOperationsRepository.closePosShift(
                    shiftId = shiftId,
                    closingBalance = "25.00",
                    idempotencyKey = closeKey,
                    actorSubject = actorSubject,
                    organizationId = closeOrganizationId,
                    authorizedLocationIds = setOf(locationId)
                ).blockingGet()
                assertEquals("CLOSED", closed.getString("status"))
                assertEquals("30.00".toBigDecimal(), closed.getValue("expectedCash").toString().toBigDecimal())
                assertEquals("-5.00".toBigDecimal(), closed.getValue("cashVariance").toString().toBigDecimal())
                assertEquals(actorSubject, closed.getString("closedBy"))

                val closeReplay = posOperationsRepository.closePosShift(
                    shiftId = shiftId,
                    closingBalance = "25.00",
                    idempotencyKey = closeKey,
                    actorSubject = actorSubject,
                    organizationId = closeOrganizationId,
                    authorizedLocationIds = setOf(locationId)
                ).blockingGet()
                assertEquals(closed.getString("closedAt"), closeReplay.getString("closedAt"))
                assertFailsWithMessage("Idempotency key conflict") {
                    posOperationsRepository.closePosShift(
                        shiftId = shiftId,
                        closingBalance = "26.00",
                        idempotencyKey = closeKey,
                        actorSubject = actorSubject,
                        organizationId = closeOrganizationId,
                        authorizedLocationIds = setOf(locationId)
                    ).blockingGet()
                }

                val confirmedReplay = orderRepository.confirmSalesOrderWithActor(
                    orderId,
                    "POSCMD-CONFIRM-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()
                assertEquals("CONFIRMED", confirmedReplay.getString("status"))

                val closedPaymentReplay = orderRepository.capturePaymentWithActor(
                    orderId,
                    "CASH",
                    30.toBigDecimal(),
                    "POSCMD-CASH-$suffix",
                    "POSCMD-PAY-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()
                assertEquals(paymentId, closedPaymentReplay.getJsonObject("payment").getString("paymentId"))

                assertFailsWithMessage("POS shift is not open") {
                    orderRepository.capturePaymentWithActor(
                        orderId,
                        "CASH",
                        1.toBigDecimal(),
                        "POSCMD-LATE-CASH-$suffix",
                        "POSCMD-LATE-PAY-$suffix",
                        actorSubject,
                        true,
                        true
                    ).blockingGet()
                }
                assertFailsWithMessage("POS shift is not open") {
                    orderRepository.cancelSalesOrderWithActor(
                        orderId,
                        "late cancellation",
                        "POSCMD-LATE-CANCEL-$suffix",
                        actorSubject,
                        true,
                        true
                    ).blockingGet()
                }
            } finally {
                orderId?.let(::cleanupOrderGraph)
                cleanupInventoryMovements(stockReference)
                deleteProduct(product.getString("productId"))
            }
        } finally {
            orderId?.let(::cleanupOrderGraph)
            pool.preparedQuery(
                """
                DELETE FROM pos_command_ledger
                WHERE organization_id = $1 AND actor_subject = $2 AND operation_id = 'closePosShift'
                  AND target_id = $3 AND idempotency_key = $4
                """.trimIndent()
            ).rxExecute(Tuple.of(closeOrganizationId, actorSubject, shiftId, closeKey)).blockingGet()
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun attributedCommandsRequirePosCapabilityOwnerAndRemainingCashBalance() {
        val suffix = suffix()
        val locationId = createLocation("POSGUARD-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSGUARD-$suffix")
        val actorSubject = "pos-guard-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        val stockReference = "POSGUARD-STOCK-$suffix"
        var orderId: String? = null
        var productId: String? = null

        try {
            val draft = orderRepository.createAttributedSalesOrderDraft(
                "POS",
                locationId,
                null,
                "USD",
                null,
                shiftId,
                actorSubject
            ).blockingGet()
            orderId = draft.getString("salesOrderId")
            val product = createProduct("POSGUARD-PRODUCT-$suffix")
            productId = product.getString("productId")
            insertInventoryMovement(
                productId!!,
                "POSGUARD-PRODUCT-$suffix",
                "IN",
                null,
                locationId,
                10.toBigDecimal(),
                stockReference
            )

            assertFailsWithMessage("pos.order.use") {
                orderRepository.addSalesOrderLineWithActor(
                    orderId,
                    productId,
                    null,
                    3.toBigDecimal(),
                    10.toBigDecimal(),
                    actorSubject,
                    true,
                    false
                ).blockingGet()
            }
            assertFailsWithMessage("POS shift owner mismatch") {
                orderRepository.addSalesOrderLineWithActor(
                    orderId,
                    productId,
                    null,
                    3.toBigDecimal(),
                    10.toBigDecimal(),
                    "different-owner-$suffix",
                    true,
                    true
                ).blockingGet()
            }

            orderRepository.addSalesOrderLineWithActor(
                orderId,
                productId,
                null,
                3.toBigDecimal(),
                10.toBigDecimal(),
                actorSubject,
                true,
                true
            ).blockingGet()
            orderRepository.confirmSalesOrderWithActor(
                orderId,
                "POSGUARD-CONFIRM-$suffix",
                actorSubject,
                true,
                true
            ).blockingGet()
            orderRepository.capturePaymentWithActor(
                orderId,
                "CASH",
                10.toBigDecimal(),
                "POSGUARD-CASH-1-$suffix",
                "POSGUARD-PAY-1-$suffix",
                actorSubject,
                true,
                true
            ).blockingGet()

            val concurrentCaptures = listOf(
                java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching {
                        orderRepository.capturePaymentWithActor(
                            orderId,
                            "CASH",
                            20.toBigDecimal(),
                            "POSGUARD-CASH-2-$suffix",
                            "POSGUARD-PAY-2-$suffix",
                            actorSubject,
                            true,
                            true
                        ).blockingGet()
                    }
                },
                java.util.concurrent.CompletableFuture.supplyAsync {
                    runCatching {
                        orderRepository.capturePaymentWithActor(
                            orderId,
                            "CASH",
                            20.toBigDecimal(),
                            "POSGUARD-CASH-3-$suffix",
                            "POSGUARD-PAY-3-$suffix",
                            actorSubject,
                            true,
                            true
                        ).blockingGet()
                    }
                }
            ).map { it.get() }

            assertEquals(1, concurrentCaptures.count { it.isSuccess })
            assertEquals(1, concurrentCaptures.count { it.isFailure })
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", orderId))
            assertEquals(2L, countLong("SELECT COUNT(*) AS cnt FROM pos_payment_context WHERE payment_id IN (SELECT payment_id FROM payment WHERE sales_order_id = $1)", orderId))
        } finally {
            orderId?.let(::cleanupOrderGraph)
            cleanupInventoryMovements(stockReference)
            productId?.let(::deleteProduct)
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun concurrentAttributedCaptureAndCloseProduceOneStableSnapshot() {
        val suffix = suffix()
        val locationId = createLocation("POSCLOSE-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSCLOSE-$suffix")
        val actorSubject = "pos-close-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        val stockReference = "POSCLOSE-STOCK-$suffix"
        val closeKey = "POSCLOSE-CLOSE-$suffix"
        val closeOrganizationId = "repo-pos-concurrent-close-$suffix"
        var orderId: String? = null
        var productId: String? = null

        try {
            orderId = orderRepository.createAttributedSalesOrderDraft(
                "POS",
                locationId,
                null,
                "USD",
                null,
                shiftId,
                actorSubject
            ).blockingGet().getString("salesOrderId")
            val product = createProduct("POSCLOSE-PRODUCT-$suffix")
            productId = product.getString("productId")
            insertInventoryMovement(
                productId,
                "POSCLOSE-PRODUCT-$suffix",
                "IN",
                null,
                locationId,
                10.toBigDecimal(),
                stockReference
            )
            val attributedOrderId = requireNotNull(orderId)
            orderRepository.addSalesOrderLineWithActor(
                attributedOrderId,
                productId,
                null,
                1.toBigDecimal(),
                10.toBigDecimal(),
                actorSubject,
                true,
                true
            ).blockingGet()
            orderRepository.confirmSalesOrderWithActor(
                attributedOrderId,
                "POSCLOSE-CONFIRM-$suffix",
                actorSubject,
                true,
                true
            ).blockingGet()

            val ready = java.util.concurrent.CountDownLatch(2)
            val start = java.util.concurrent.CountDownLatch(1)
            val capture = java.util.concurrent.CompletableFuture.supplyAsync {
                ready.countDown()
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Capture did not receive the start signal" }
                runCatching {
                    orderRepository.capturePaymentWithActor(
                        attributedOrderId,
                        "CASH",
                        10.toBigDecimal(),
                        "POSCLOSE-CASH-$suffix",
                        "POSCLOSE-PAY-$suffix",
                        actorSubject,
                        true,
                        true
                    ).blockingGet()
                }
            }
            val close = java.util.concurrent.CompletableFuture.supplyAsync {
                ready.countDown()
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Close did not receive the start signal" }
                runCatching {
                    posOperationsRepository.closePosShift(
                        shiftId = shiftId,
                        closingBalance = "10.00",
                        idempotencyKey = closeKey,
                        actorSubject = actorSubject,
                        organizationId = closeOrganizationId,
                        authorizedLocationIds = setOf(locationId)
                    ).blockingGet()
                }
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS), "Both commands must be ready before release")
            start.countDown()

            val captureResult = capture.get()
            val closeResult = close.get()
            assertTrue(closeResult.isSuccess, closeResult.exceptionOrNull()?.message ?: "Close failed")
            val closed = closeResult.getOrThrow()
            val captured = captureResult.isSuccess
            if (!captured) {
                val message = captureResult.exceptionOrNull()?.cause?.message
                    ?: captureResult.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.contains("POS shift is not open"), "Unexpected capture failure: $message")
            }

            assertEquals("CLOSED", closed.getString("status"))
            assertEquals(
                if (captured) "10.00".toBigDecimal() else "0.00".toBigDecimal(),
                closed.getValue("expectedCash").toString().toBigDecimal()
            )
            assertEquals(
                if (captured) 1L else 0L,
                countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", attributedOrderId)
            )
            assertEquals(
                if (captured) 1L else 0L,
                countLong(
                    "SELECT COUNT(*) AS cnt FROM pos_payment_context WHERE payment_id IN (SELECT payment_id FROM payment WHERE sales_order_id = $1)",
                    attributedOrderId
                )
            )
        } finally {
            orderId?.let(::cleanupOrderGraph)
            cleanupInventoryMovements(stockReference)
            productId?.let(::deleteProduct)
            pool.preparedQuery(
                """
                DELETE FROM pos_command_ledger
                WHERE organization_id = $1 AND actor_subject = $2 AND operation_id = 'closePosShift'
                  AND target_id = $3 AND idempotency_key = $4
                """.trimIndent()
            ).rxExecute(Tuple.of(closeOrganizationId, actorSubject, shiftId, closeKey)).blockingGet()
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
        }
    }

    @Test
    fun attributedCaptureRollsBackPaymentContextAndPaymentTogether() {
        val suffix = suffix()
        val locationId = createLocation("POSROLL-$suffix").getString("locationId")
        val terminalId = createTerminal(locationId, "POSROLL-$suffix")
        val actorSubject = "pos-rollback-owner-$suffix"
        val shiftId = createShift(terminalId, actorSubject, "USD", "OPEN")
        val stockReference = "POSROLL-STOCK-$suffix"
        var orderId: String? = null
        var productId: String? = null

        try {
            orderId = orderRepository.createAttributedSalesOrderDraft(
                "POS",
                locationId,
                null,
                "USD",
                null,
                shiftId,
                actorSubject
            ).blockingGet().getString("salesOrderId")
            val product = createProduct("POSROLL-PRODUCT-$suffix")
            productId = product.getString("productId")
            insertInventoryMovement(
                productId,
                "POSROLL-PRODUCT-$suffix",
                "IN",
                null,
                locationId,
                10.toBigDecimal(),
                stockReference
            )
            orderRepository.addSalesOrderLineWithActor(
                orderId,
                productId,
                null,
                3.toBigDecimal(),
                10.toBigDecimal(),
                actorSubject,
                true,
                true
            ).blockingGet()
            orderRepository.confirmSalesOrderWithActor(
                orderId,
                "POSROLL-CONFIRM-$suffix",
                actorSubject,
                true,
                true
            ).blockingGet()
            installPaymentContextFailureTrigger(suffix)

            assertFailsWithMessage("forced payment context failure") {
                orderRepository.capturePaymentWithActor(
                    orderId,
                    "CASH",
                    30.toBigDecimal(),
                    "POSROLL-CASH-$suffix",
                    "POSROLL-PAY-$suffix",
                    actorSubject,
                    true,
                    true
                ).blockingGet()
            }
            assertEquals(0L, countLong("SELECT COUNT(*) AS cnt FROM payment WHERE sales_order_id = $1", orderId))
            assertEquals(
                0L,
                countLong(
                    "SELECT COUNT(*) AS cnt FROM pos_payment_context pc JOIN payment p ON p.payment_id = pc.payment_id WHERE p.sales_order_id = $1",
                    orderId
                )
            )
            assertEquals(
                0L,
                countLong(
                    "SELECT COUNT(*) AS cnt FROM order_command_idempotency WHERE sales_order_id = $1 AND command_name = 'capturePayment'",
                    orderId
                )
            )
        } finally {
            cleanupTrigger("trg_fail_payment_context_$suffix", "fn_fail_payment_context_$suffix")
            orderId?.let(::cleanupOrderGraph)
            cleanupInventoryMovements(stockReference)
            productId?.let(::deleteProduct)
            deleteShift(shiftId)
            deleteTerminal(terminalId)
            deleteLocation(locationId)
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

    private fun createTerminal(locationId: String, terminalCode: String): String {
        val terminalId = UUID.randomUUID().toString()
        pool.preparedQuery(
            """
            INSERT INTO pos_terminal (
                terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
            )
            VALUES ($1, $2, $3, $4, true, NOW(), NOW())
            """.trimIndent()
        ).rxExecute(
            Tuple.of(terminalId, locationId, terminalCode, "Repository test terminal $terminalCode")
        ).blockingGet()
        return terminalId
    }

    private fun createShift(
        terminalId: String,
        operatorId: String,
        currency: String,
        status: String,
        shiftNumber: Int = 1
    ): String {
        val shiftId = UUID.randomUUID().toString()
        pool.preparedQuery(
            """
            INSERT INTO pos_shift (
                shift_id, terminal_id, operator_id, shift_date, shift_number,
                opened_at, opening_balance, status, created_at, currency, is_reconcilable
            )
            VALUES ($1, $2, $3, CURRENT_DATE, $4, NOW(), 0, $5::shift_status, NOW(), $6, true)
            """.trimIndent()
        ).rxExecute(
            Tuple.tuple()
                .addString(shiftId)
                .addString(terminalId)
                .addString(operatorId)
                .addInteger(shiftNumber)
                .addString(status)
                .addString(currency)
        ).blockingGet()
        return shiftId
    }

    private fun deleteShift(shiftId: String) {
        pool.preparedQuery("DELETE FROM pos_shift WHERE shift_id = $1")
            .rxExecute(Tuple.of(shiftId))
            .blockingGet()
    }

    private fun deleteTerminal(terminalId: String) {
        pool.preparedQuery("DELETE FROM pos_terminal WHERE terminal_id = $1")
            .rxExecute(Tuple.of(terminalId))
            .blockingGet()
    }

    private fun installContextFailureTrigger(suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_pos_context_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced POS context failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_pos_context_$suffix
            BEFORE INSERT ON pos_order_context
            FOR EACH ROW
            EXECUTE FUNCTION fn_fail_pos_context_$suffix();
            """.trimIndent()
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

    private fun installPaymentContextFailureTrigger(suffix: String) {
        execSql(
            """
            CREATE OR REPLACE FUNCTION fn_fail_payment_context_$suffix()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced payment context failure';
            END;
            $$ LANGUAGE plpgsql;
            """.trimIndent()
        )
        execSql(
            """
            CREATE TRIGGER trg_fail_payment_context_$suffix
            BEFORE INSERT ON pos_payment_context
            FOR EACH ROW
            EXECUTE FUNCTION fn_fail_payment_context_$suffix();
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
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON pos_order_context;") }
        runCatching { execSql("DROP TRIGGER IF EXISTS $triggerName ON pos_payment_context;") }
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
        execSql("DELETE FROM pos_payment_context WHERE payment_id IN (SELECT payment_id FROM payment WHERE sales_order_id = '$orderId');")
        execSql("DELETE FROM pos_order_context WHERE sales_order_id = '$orderId';")
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
