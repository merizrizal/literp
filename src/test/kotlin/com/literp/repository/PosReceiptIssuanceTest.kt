package com.literp.repository

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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.vertx.rxjava3.core.Vertx as RxVertx

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PosReceiptIssuanceTest {
    private lateinit var vertx: Vertx
    private lateinit var pool: Pool
    private lateinit var repository: PosOperationsRepository

    @BeforeAll
    fun setUp() {
        vertx = Vertx.vertx()
        pool = TestDatabase.createPool(RxVertx.newInstance(vertx))
        TestDatabase.assumeAvailable(pool)
        repository = PosOperationsRepository(pool)
    }

    @AfterAll
    fun tearDown() {
        if (::pool.isInitialized) pool.rxClose().blockingAwait()
        if (::vertx.isInitialized) vertx.close().toCompletionStage().toCompletableFuture().get()
    }

    @Test
    fun issuesImmutableFractionalSplitPaymentSnapshotAfterShiftCloseAndReplays() = withSale { sale ->
        val receipt = issue(sale)
        assertTrue(receipt.getString("receiptNumber").matches(Regex("RCPT-[0-9]{8}-[0-9]+")))
        assertEquals(sale.shiftId, receipt.getString("shiftId"))
        assertEquals("USD", receipt.getString("currency"))
        assertEquals(0, receipt.getValue("totalItems").toString().toBigDecimal().compareTo("1.250".toBigDecimal()))
        assertEquals(0, receipt.getValue("totalAmount").toString().toBigDecimal().compareTo("12.50".toBigDecimal()))
        assertEquals(0, receipt.getValue("taxAmount").toString().toBigDecimal().signum())
        assertEquals("ISSUED", receipt.getString("adjustmentStatus"))
        val snapshot = receipt.getJsonObject("receiptData")
        assertEquals("issuer", snapshot.getString("issuerSubject"))
        assertEquals(sale.actor, snapshot.getString("draftOperatorId"))
        assertEquals(2, snapshot.getJsonArray("payments").size())
        assertEquals(1, snapshot.getJsonArray("lines").size())
        assertEquals("CLOSED", snapshot.getJsonObject("shift").getString("status"))
        execute("UPDATE payment SET transaction_ref = 'changed-after-issuance' WHERE payment_id = $1", sale.paymentId)
        assertEquals(receipt, issue(sale))
        assertTrue(runCatching { issue(sale, "different-key") }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(receipt, repository.getPosReceiptByNumber(receipt.getString("receiptNumber"), setOf(sale.locationId)).blockingGet())
        assertEquals(1L, receiptCount(sale))
    }

    @Test
    fun reauthorizesReplayAndHidesMissingOrForeignOrders() = withSale { sale ->
        issue(sale)
        val hidden = runCatching {
            repository.generatePosReceipt(sale.orderId, "issue", "issuer", sale.organization, setOf(UUID.randomUUID().toString())).blockingGet()
        }.exceptionOrNull()
        assertTrue(hidden?.message.orEmpty().endsWith("RESOURCE_NOT_FOUND"))
        val empty = runCatching {
            repository.generatePosReceipt(sale.orderId, "issue", "issuer", sale.organization, emptySet()).blockingGet()
        }.exceptionOrNull()
        assertTrue(empty is PosOperationsScopeViolation)
        val missing = runCatching {
            repository.generatePosReceipt(UUID.randomUUID().toString(), "issue", "issuer", sale.organization, setOf(sale.locationId)).blockingGet()
        }.exceptionOrNull()
        assertEquals(hidden?.message, missing?.message)
        assertEquals(1L, receiptCount(sale))
    }

    @Test
    fun rejectsUnderpaymentOvercaptureAndInconsistentLinesWithoutClaims() = withSale { sale ->
        listOf("4.99", "5.01").forEach { amount ->
            execute("UPDATE payment SET amount = $2 WHERE payment_id = $1", sale.paymentId, amount.toBigDecimal())
            assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
            assertEquals(0L, receiptCount(sale))
            assertEquals(0L, issuanceCommandCount(sale))
        }
        execute("UPDATE payment SET amount = 5 WHERE payment_id = $1", sale.paymentId)
        execute("UPDATE sales_order_line SET quantity_fulfilled = 0 WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(0L, issuanceCommandCount(sale))
        execute("UPDATE sales_order_line SET quantity_fulfilled = quantity_ordered, line_total = 12.49 WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(0L, receiptCount(sale))
    }

    @Test
    fun rejectsUnattributedNonPosUnfulfilledAndUntrustedPayments() = withSale { sale ->
        execute("UPDATE sales_order SET status = 'CONFIRMED' WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        execute("UPDATE sales_order SET status = 'FULFILLED', sales_channel = 'ONLINE' WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        execute("UPDATE sales_order SET sales_channel = 'POS' WHERE sales_order_id = $1", sale.orderId)
        execute("DELETE FROM pos_payment_context WHERE payment_id = $1", sale.paymentId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        execute("DELETE FROM pos_order_context WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { issue(sale) }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(0L, receiptCount(sale))
        assertEquals(0L, issuanceCommandCount(sale))
    }

    @Test
    fun rollsBackReceiptWhenCommandResponsePersistenceFails() = withSale { sale ->
        val name = "receipt_fail_${sale.suffix}"
        pool.query("""
            CREATE FUNCTION $name() RETURNS trigger AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'forced receipt command failure'; END;
            ${'$'}${'$'} LANGUAGE plpgsql
        """.trimIndent()).rxExecute().blockingGet()
        try {
            pool.query("""
                CREATE TRIGGER $name BEFORE UPDATE ON pos_command_ledger
                FOR EACH ROW WHEN (NEW.target_id = '${sale.orderId}') EXECUTE FUNCTION $name()
            """.trimIndent()).rxExecute().blockingGet()
            assertTrue(runCatching { issue(sale) }.exceptionOrNull()?.message.orEmpty().contains("forced receipt command failure"))
            assertEquals(0L, receiptCount(sale))
            assertEquals(0L, issuanceCommandCount(sale))
        } finally {
            pool.query("DROP TRIGGER IF EXISTS $name ON pos_command_ledger").rxExecute().blockingGet()
            pool.query("DROP FUNCTION $name()").rxExecute().blockingGet()
        }
        assertEquals(1L, issue(sale).let { receiptCount(sale) })
    }

    @Test
    fun concurrentDifferentKeysIssueOnlyOneReceipt() = withSale { sale ->
        val results = concurrentIssues(sale, listOf("first", "second"))
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(1L, receiptCount(sale))
        assertEquals(1L, issuanceCommandCount(sale))
    }

    @Test
    fun concurrentSameKeyReturnsSameCommittedReceipt() = withSale { sale ->
        val results = concurrentIssues(sale, listOf("same", "same"))
        assertTrue(results.all { it.isSuccess })
        assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
        assertEquals(1L, receiptCount(sale))
        assertEquals(1L, issuanceCommandCount(sale))
    }

    @Test
    fun refundsAreBoundedAppendOnlyAndReplayAfterClose() = withSale { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale)
        val first = refund(sale, receipt, shift, "2.00", "partial")
        assertEquals("PARTIALLY_REFUNDED", first.getJsonObject("receiptAdjustment").getString("adjustmentStatus"))
        assertEquals("CAPTURED", paymentStatus(sale))
        assertEquals(first, refund(sale, receipt, shift, "2.0", "partial"))
        assertTrue(runCatching { refund(sale, receipt, shift, "2.01", "partial") }.exceptionOrNull() is PosOperationsConflict)
        assertTrue(runCatching { refund(sale, receipt, shift, "3.01", "excess") }.exceptionOrNull() is PosOperationsConflict)
        refund(sale, receipt, shift, "3", "rest")
        assertEquals("REFUNDED", paymentStatus(sale))
        val secondPayment = receipt.getJsonObject("receiptData").getJsonArray("payments").filterIsInstance<JsonObject>()
            .single { it.getString("paymentId") != sale.paymentId }.getString("paymentId")
        val full = repository.createPosReceiptRefund(receipt.getString("receiptId"), secondPayment, "7.50", shift, "return", "second-payment", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
        assertEquals("REFUNDED", full.getJsonObject("receiptAdjustment").getString("adjustmentStatus"))
        assertEquals(0, full.getJsonObject("receiptAdjustment").getValue("netAmount").toString().toBigDecimal().signum())
        repository.closePosShift(shift, "0", "close-refund", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
        assertEquals(first, refund(sale, receipt, shift, "2", "partial"))
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "closed") }.exceptionOrNull() is PosOperationsConflict)
        val mutation = runCatching { execute("DELETE FROM pos_receipt_refund WHERE receipt_id = $1", receipt.getString("receiptId")) }.exceptionOrNull()
        assertTrue(mutation?.message.orEmpty().contains("append-only"))
        val read = repository.getPosReceiptByNumber(receipt.getString("receiptNumber"), setOf(sale.locationId)).blockingGet()
        assertEquals(receipt.getJsonObject("receiptData"), read.getJsonObject("receiptData"))
    }

    @Test
    fun sameShiftFullCashRefundClosesAtZeroWithoutDoubleSubtracting() = withSale(cash = true, closeOriginal = false) { sale ->
        val receipt = issue(sale)
        refund(sale, receipt, sale.shiftId, "5", "cash-first")
        val other = receipt.getJsonObject("receiptData").getJsonArray("payments").filterIsInstance<JsonObject>()
            .single { it.getString("paymentId") != sale.paymentId }.getString("paymentId")
        repository.createPosReceiptRefund(receipt.getString("receiptId"), other, "7.50", sale.shiftId, "return", "cash-second", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
        val close = repository.closePosShift(sale.shiftId, "0", "cash-close", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
        assertEquals(0, close.getValue("expectedCash").toString().toBigDecimal().signum())
    }

    @Test
    fun laterShiftCashRefundUsesOnlyLaterDrawerAndPreservesClosedSnapshot() = withSale(cash = true) { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale, "12.50")
        refund(sale, receipt, shift, "5", "later")
        val close = repository.closePosShift(shift, "7.50", "close-later", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
        assertEquals(0, close.getValue("expectedCash").toString().toBigDecimal().compareTo("7.50".toBigDecimal()))
        val original = pool.preparedQuery("SELECT expected_cash FROM pos_shift WHERE shift_id = $1").rxExecute(Tuple.of(sale.shiftId)).blockingGet().first().getBigDecimal("expected_cash")
        assertEquals(0, original.compareTo("12.50".toBigDecimal()))
    }

    @Test
    fun cashRefundGuardsRejectBeforeMutation() = withSale(cash = true) { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale)
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "no-cash") }.exceptionOrNull() is PosOperationsConflict)
        listOf("0", "-1", "0.001", "1000000000000").forEach { amount ->
            assertTrue(runCatching { refund(sale, receipt, shift, amount, "invalid-$amount") }.exceptionOrNull() is PosOperationsValidation)
        }
        assertTrue(runCatching {
            repository.createPosReceiptRefund(receipt.getString("receiptId"), sale.paymentId, "1", shift, "return", "wrong-owner", "other-owner", sale.organization, setOf(sale.locationId)).blockingGet()
        }.exceptionOrNull() is PosOperationsScopeViolation)
        assertTrue(runCatching {
            repository.createPosReceiptRefund(receipt.getString("receiptId"), sale.paymentId, "1", shift, "return", "hidden", sale.actor, sale.organization, setOf(UUID.randomUUID().toString())).blockingGet()
        }.exceptionOrNull()?.message.orEmpty().endsWith("RESOURCE_NOT_FOUND"))
        execute("UPDATE pos_shift SET currency = 'EUR' WHERE shift_id = $1", shift)
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "currency") }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(0L, count("SELECT count(*) AS cnt FROM pos_receipt_refund WHERE receipt_id = $1", receipt.getString("receiptId")))
    }

    @Test
    fun refundsRejectForeignPaymentsLegacyReceiptsAndMalformedSnapshots() = withSale { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale)
        val foreignPayment = pool.preparedQuery("SELECT payment_id FROM payment WHERE sales_order_id <> $1 LIMIT 1")
            .rxExecute(Tuple.of(sale.orderId)).blockingGet().first().getString("payment_id")
        val foreign = runCatching { repository.createPosReceiptRefund(receipt.getString("receiptId"), foreignPayment, "1", shift,
            "return", "foreign", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet() }.exceptionOrNull()
        assertTrue(foreign?.message.orEmpty().endsWith("RESOURCE_NOT_FOUND"))
        assertTrue(runCatching { repository.createPosReceiptRefund(receipt.getString("receiptId"), sale.paymentId, "1", shift,
            "   ", "blank", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet() }.exceptionOrNull() is PosOperationsValidation)
        execute("UPDATE receipt SET is_api_issued = false WHERE receipt_id = $1", receipt.getString("receiptId"))
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "legacy") }.exceptionOrNull() is PosOperationsConflict)
        execute("UPDATE receipt SET is_api_issued = true, total_amount = 0.50 WHERE receipt_id = $1", receipt.getString("receiptId"))
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "receipt-cap") }.exceptionOrNull() is PosOperationsConflict)
        execute("UPDATE receipt SET total_amount = 12.50 WHERE receipt_id = $1", receipt.getString("receiptId"))
        execute("UPDATE sales_order SET currency = 'EUR' WHERE sales_order_id = $1", sale.orderId)
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "order-currency") }.exceptionOrNull() is PosOperationsConflict)
        execute("UPDATE sales_order SET currency = 'USD' WHERE sales_order_id = $1", sale.orderId)
        val malformed = receipt.getJsonObject("receiptData").copy()
        malformed.getJsonArray("payments").filterIsInstance<JsonObject>().forEach { it.put("amount", "not-money") }
        execute("UPDATE receipt SET receipt_data = $2 WHERE receipt_id = $1", receipt.getString("receiptId"), malformed)
        assertTrue(runCatching { refund(sale, receipt, shift, "1", "malformed") }.exceptionOrNull() is PosOperationsConflict)
        assertEquals(0L, count("SELECT count(*) AS cnt FROM pos_receipt_refund WHERE receipt_id = $1", receipt.getString("receiptId")))
    }

    @Test
    fun concurrentRefundsCannotExceedPaymentAndSameKeyReplays() = withSale { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale)
        val results = concurrentCommands(sale, listOf(
            { refund(sale, receipt, shift, "4", "a") }, { refund(sale, receipt, shift, "4", "b") }
        ))
        assertEquals(1, results.count { it.isSuccess })
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is PosOperationsConflict)
        val repeats = concurrentCommands(sale, listOf(
            { refund(sale, receipt, shift, "1", "same-refund") }, { refund(sale, receipt, shift, "1", "same-refund") }
        ))
        assertTrue(repeats.all { it.isSuccess })
        assertEquals(repeats[0].getOrThrow(), repeats[1].getOrThrow())
        assertEquals("REFUNDED", paymentStatus(sale))
    }

    @Test
    fun refundAndCloseSerializeAndFailedCommandRollsBackPaymentAndRefund() = withSale { sale ->
        val receipt = issue(sale)
        val shift = refundShift(sale)
        val name = "refund_fail_${sale.suffix}"
        pool.query("CREATE FUNCTION $name() RETURNS trigger AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'forced refund failure'; END; ${'$'}${'$'} LANGUAGE plpgsql").rxExecute().blockingGet()
        try {
            pool.query("CREATE TRIGGER $name BEFORE UPDATE ON pos_command_ledger FOR EACH ROW WHEN (NEW.operation_id = 'createPosReceiptRefund' AND NEW.target_id = '${receipt.getString("receiptId")}') EXECUTE FUNCTION $name()").rxExecute().blockingGet()
            assertTrue(runCatching { refund(sale, receipt, shift, "5", "rollback") }.exceptionOrNull()?.message.orEmpty().contains("forced refund failure"))
            assertEquals("CAPTURED", paymentStatus(sale))
            assertEquals(0L, count("SELECT count(*) AS cnt FROM pos_receipt_refund WHERE receipt_id = $1", receipt.getString("receiptId")))
        } finally {
            pool.query("DROP TRIGGER IF EXISTS $name ON pos_command_ledger").rxExecute().blockingGet()
            pool.query("DROP FUNCTION $name()").rxExecute().blockingGet()
        }
        val results = concurrentCommands(sale, listOf(
            { refund(sale, receipt, shift, "5", "race") },
            { repository.closePosShift(shift, "0", "race-close", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet() }
        ))
        assertTrue(results[1].isSuccess)
        if (results[0].isFailure) assertTrue(results[0].exceptionOrNull() is PosOperationsConflict)
        assertEquals(if (results[0].isSuccess) 1L else 0L, count("SELECT count(*) AS cnt FROM pos_receipt_refund WHERE receipt_id = $1", receipt.getString("receiptId")))
    }

    private fun refundShift(sale: Sale, opening: String = "0"): String = repository.openPosShift(
        sale.terminalId, opening, "USD", "refund-open", sale.actor, sale.organization, setOf(sale.locationId)
    ).blockingGet().getString("shiftId")

    private fun refund(sale: Sale, receipt: JsonObject, shift: String, amount: String, key: String): JsonObject =
        repository.createPosReceiptRefund(receipt.getString("receiptId"), sale.paymentId, amount, shift, "return", key,
            sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()

    private fun paymentStatus(sale: Sale): String = pool.preparedQuery("SELECT status FROM payment WHERE payment_id = $1")
        .rxExecute(Tuple.of(sale.paymentId)).blockingGet().first().getString("status")

    private fun concurrentIssues(sale: Sale, keys: List<String>): List<Result<JsonObject>> =
        concurrentCommands(sale, keys.map { key -> { issue(sale, key) } })

    private fun concurrentCommands(sale: Sale, actions: List<() -> JsonObject>): List<Result<JsonObject>> {
        val blocker = pool.rxGetConnection().blockingGet()
        val commands = mutableListOf<CompletableFuture<Result<JsonObject>>>()
        try {
            val transaction = blocker.rxBegin().blockingGet()
            try {
                val pid = blocker.query("SELECT pg_backend_pid() AS pid").rxExecute().blockingGet().first().getInteger("pid")
                blocker.preparedQuery("SELECT terminal_id FROM pos_terminal WHERE terminal_id = $1 FOR UPDATE")
                    .rxExecute(Tuple.of(sale.terminalId)).blockingGet()
                actions.forEach { action -> commands += CompletableFuture.supplyAsync { runCatching(action) } }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waiting = 0L
                while (waiting < 2 && System.nanoTime() < deadline) {
                    waiting = pool.preparedQuery("""
                        WITH RECURSIVE blocked(pid) AS (
                            SELECT pid FROM pg_stat_activity WHERE $1::integer = ANY(pg_blocking_pids(pid))
                            UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid = ANY(pg_blocking_pids(a.pid))
                        ) SELECT count(*) AS cnt FROM blocked
                    """.trimIndent())
                        .rxExecute(Tuple.of(pid)).blockingGet().first().getLong("cnt")
                    if (waiting < 2) Thread.sleep(10)
                }
                assertEquals(2L, waiting, "Both issuers must wait on the original terminal lock")
            } finally {
                transaction.rxRollback().blockingAwait()
            }
        } finally {
            blocker.rxClose().blockingAwait()
            commands.forEach { it.get(10, TimeUnit.SECONDS) }
        }
        return commands.map { it.get(10, TimeUnit.SECONDS) }
    }

    private fun issue(sale: Sale, key: String = "issue"): JsonObject = repository.generatePosReceipt(
        sale.orderId, key, "issuer", sale.organization, setOf(sale.locationId)
    ).blockingGet()

    private fun receiptCount(sale: Sale): Long = count("SELECT count(*) AS cnt FROM receipt WHERE sales_order_id = $1", sale.orderId)
    private fun issuanceCommandCount(sale: Sale): Long = count("SELECT count(*) AS cnt FROM pos_command_ledger WHERE target_id = $1 AND operation_id = 'generatePosReceipt'", sale.orderId)
    private fun count(query: String, id: String): Long = pool.preparedQuery(query).rxExecute(Tuple.of(id)).blockingGet().first().getLong("cnt")
    private fun execute(query: String, vararg values: Any) { pool.preparedQuery(query).rxExecute(Tuple.from(values.toList())).blockingGet() }

    private fun withSale(cash: Boolean = false, closeOriginal: Boolean = true, test: (Sale) -> Unit) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val sale = Sale(suffix, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val locationRepository = LocationRepository(pool)
        val location = locationRepository.createLocation("RCPT-$suffix", "Receipt test", "WAREHOUSE", true, JsonObject()).blockingGet()
        sale.locationId = location.getString("locationId")
        try {
            execute("INSERT INTO pos_terminal (terminal_id, location_id, terminal_code, device_name, is_active) VALUES ($1, $2, $3, 'Receipt test', true)", sale.terminalId, sale.locationId, "RCPT-$suffix")
            execute("INSERT INTO pos_shift (shift_id, terminal_id, operator_id, shift_date, shift_number, opened_at, opening_balance, status, currency, is_reconcilable) VALUES ($1, $2, $3, CURRENT_DATE, 1, NOW(), 0, 'OPEN', 'USD', true)", sale.shiftId, sale.terminalId, sale.actor)
            execute("INSERT INTO sales_order (sales_order_id, order_number, order_date, sales_channel, location_id, status, total_amount, currency) VALUES ($1, $2, NOW(), 'POS', $3, 'FULFILLED', 12.50, 'USD')", sale.orderId, "RCPT-$suffix", sale.locationId)
            execute("INSERT INTO pos_order_context (sales_order_id, shift_id, draft_operator_id) VALUES ($1, $2, $3)", sale.orderId, sale.shiftId, sale.actor)
            execute("INSERT INTO sales_order_line (line_id, sales_order_id, product_id, sku, quantity_ordered, quantity_fulfilled, unit_price, line_total, status) SELECT $1, $2, product_id, sku, 1.250, 1.250, 10, 12.50, 'FULFILLED' FROM product ORDER BY product_id LIMIT 1", UUID.randomUUID().toString(), sale.orderId)
            listOf(sale.paymentId to "5.00", UUID.randomUUID().toString() to "7.50").forEach { (id, amount) ->
                execute("INSERT INTO payment (payment_id, sales_order_id, payment_method, amount, status) VALUES ($1, $2, $4::payment_method, $3, 'CAPTURED')", id, sale.orderId, amount.toBigDecimal(), if (cash) "CASH" else "CARD")
                execute("INSERT INTO pos_payment_context (payment_id, shift_id, capture_operator_id) VALUES ($1, $2, $3)", id, sale.shiftId, sale.actor)
            }
            if (closeOriginal) repository.closePosShift(sale.shiftId, if (cash) "12.50" else "0", "close", sale.actor, sale.organization, setOf(sale.locationId)).blockingGet()
            test(sale)
        } finally {
            TestDatabase.deleteRefundFixtures(pool, sale.orderId)
            execute("DELETE FROM pos_command_ledger WHERE organization_id = $1", sale.organization)
            execute("DELETE FROM receipt WHERE sales_order_id = $1", sale.orderId)
            execute("DELETE FROM pos_payment_context WHERE payment_id IN (SELECT payment_id FROM payment WHERE sales_order_id = $1)", sale.orderId)
            execute("DELETE FROM pos_order_context WHERE sales_order_id = $1", sale.orderId)
            execute("DELETE FROM payment WHERE sales_order_id = $1", sale.orderId)
            execute("DELETE FROM sales_order WHERE sales_order_id = $1", sale.orderId)
            execute("DELETE FROM pos_shift WHERE terminal_id = $1", sale.terminalId)
            execute("DELETE FROM pos_terminal WHERE terminal_id = $1", sale.terminalId)
            locationRepository.deleteLocation(sale.locationId).blockingGet()
        }
    }

    private data class Sale(
        val suffix: String, val orderId: String, val terminalId: String, val shiftId: String, val paymentId: String,
        var locationId: String = "", val actor: String = "sale-owner-$suffix", val organization: String = "receipt-test-$suffix"
    )
}
