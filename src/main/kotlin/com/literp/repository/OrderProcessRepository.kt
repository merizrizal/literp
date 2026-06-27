package com.literp.repository

import com.literp.common.ErrorCodes
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Row
import io.vertx.rxjava3.sqlclient.SqlConnection
import io.vertx.rxjava3.sqlclient.Tuple
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class OrderProcessRepository(pool: Pool) : BaseRepository(pool, OrderProcessRepository::class.java) {

    fun listSalesOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?
    ): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val rawField = parts.getOrNull(0)?.trim() ?: "orderDate"
        val rawOrder = parts.getOrNull(1)?.trim()?.uppercase() ?: "DESC"

        val sortField = when (rawField.lowercase()) {
            "ordernumber", "order_number" -> "order_number"
            "orderdate", "order_date" -> "order_date"
            "saleschannel", "sales_channel" -> "sales_channel"
            "status" -> "status"
            "totalamount", "total_amount" -> "total_amount"
            "createdat", "created_at" -> "created_at"
            "updatedat", "updated_at" -> "updated_at"
            else -> "order_date"
        }
        val sortOrder = if (rawOrder == "ASC" || rawOrder == "DESC") rawOrder else "DESC"

        val params = mutableListOf<Any?>()
        var whereClause = "WHERE true"

        if (!status.isNullOrBlank()) {
            whereClause += " AND status = $${params.size + 1}"
            params.add(status.uppercase())
        }
        if (!salesChannel.isNullOrBlank()) {
            whereClause += " AND sales_channel = $${params.size + 1}"
            params.add(salesChannel.uppercase())
        }
        if (!locationId.isNullOrBlank()) {
            whereClause += " AND location_id = $${params.size + 1}"
            params.add(locationId)
        }

        val countQuery = "SELECT COUNT(*) AS total FROM sales_order $whereClause"
        val dataQuery = """
            SELECT sales_order_id, order_number, order_date, sales_channel, customer_id, location_id, status, total_amount, currency, notes, created_at, updated_at
            FROM sales_order
            $whereClause
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        var total = 0

        return pool.preparedQuery(countQuery)
            .rxExecute(Tuple.from(params))
            .flatMap { countResult ->
                total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery).rxExecute(Tuple.from(params))
            }
            .map { result ->
                val data = result.map { row -> mapSalesOrderRow(row) }
                JsonObject()
                    .put("data", data)
                    .put(
                        "pagination",
                        JsonObject()
                            .put("page", page)
                            .put("size", size)
                            .put("totalElements", total)
                            .put("totalPages", (total + size - 1) / size)
                    )
            }
    }

    fun createSalesOrderDraft(
        salesChannel: String,
        locationId: String,
        customerId: String?,
        currency: String,
        notes: String?
    ): Single<JsonObject> {
        val orderId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO sales_order (sales_order_id, order_number, order_date, sales_channel, customer_id, location_id, status, total_amount, currency, notes, created_at, updated_at)
            VALUES ($1, $2, NOW(), $3, $4, $5, 'DRAFT', 0, $6, $7, NOW(), NOW())
            RETURNING sales_order_id, order_number, order_date, sales_channel, customer_id, location_id, status, total_amount, currency, notes, created_at, updated_at
        """.trimIndent()

        return generateOrderNumber().flatMap { orderNumber ->
            pool.preparedQuery(query)
                .rxExecute(
                    Tuple.tuple()
                        .addString(orderId)
                        .addString(orderNumber)
                        .addString(salesChannel.uppercase())
                        .addValue(customerId)
                        .addString(locationId)
                        .addString(currency.uppercase())
                        .addValue(notes)
                )
                .map { result -> mapSalesOrderRow(result.first()) }
        }
    }

    fun getSalesOrder(orderId: String): Single<JsonObject> {
        val orderQuery = """
            SELECT sales_order_id, order_number, order_date, sales_channel, customer_id, location_id, status, total_amount, currency, notes, created_at, updated_at
            FROM sales_order
            WHERE sales_order_id = $1
        """.trimIndent()
        val linesQuery = """
            SELECT line_id, sales_order_id, product_id, sku, quantity_ordered, quantity_fulfilled, unit_price, line_total, status, created_at, updated_at
            FROM sales_order_line
            WHERE sales_order_id = $1
            ORDER BY created_at ASC
        """.trimIndent()
        val reservationsQuery = """
            SELECT reservation_id, sales_order_id, sales_order_line_id, product_id, sku, location_id, quantity, status, created_at, updated_at
            FROM inventory_reservation
            WHERE sales_order_id = $1
            ORDER BY created_at ASC
        """.trimIndent()
        val paymentsQuery = """
            SELECT payment_id, sales_order_id, payment_method, amount, status, transaction_ref, created_at, updated_at
            FROM payment
            WHERE sales_order_id = $1
            ORDER BY created_at ASC
        """.trimIndent()

        return pool.preparedQuery(orderQuery)
            .rxExecute(Tuple.of(orderId))
            .flatMap { orderResult ->
                if (orderResult.size() == 0) {
                    Single.error(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    val order = mapSalesOrderRow(orderResult.first())
                    pool.preparedQuery(linesQuery)
                        .rxExecute(Tuple.of(orderId))
                        .flatMap { linesResult ->
                            val lines = linesResult.map { row -> mapSalesOrderLineRow(row) }
                            pool.preparedQuery(reservationsQuery)
                                .rxExecute(Tuple.of(orderId))
                                .flatMap { reservationResult ->
                                    val reservations = reservationResult.map { row -> mapReservationRow(row) }
                                    pool.preparedQuery(paymentsQuery)
                                        .rxExecute(Tuple.of(orderId))
                                        .map { paymentResult ->
                                            val payments = paymentResult.map { row -> mapPaymentRow(row) }
                                            order
                                                .put("lines", lines)
                                                .put("reservations", reservations)
                                                .put("payments", payments)
                                        }
                                }
                        }
                }
            }
    }

    fun getCurrentStock(productId: String, locationId: String): Single<JsonObject> {
        if (productId.isBlank() || locationId.isBlank()) {
            return Single.error(Exception("productId and locationId are required"))
        }

        val query = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN movement_type IN ('IN', 'ADJUSTMENT') AND to_location_id = $2 THEN quantity
                    WHEN movement_type = 'TRANSFER' AND to_location_id = $2 THEN quantity
                    WHEN movement_type = 'TRANSFER' AND from_location_id = $2 THEN -quantity
                    WHEN movement_type = 'OUT' AND from_location_id = $2 THEN -quantity
                    ELSE 0
                END
            ), 0) AS quantity
            FROM inventory_movement
            WHERE product_id = $1 AND (to_location_id = $2 OR from_location_id = $2)
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId, locationId))
            .map { result -> stockQuantity(productId, locationId, result.first().getBigDecimal("quantity"), "CURRENT") }
    }

    fun getAvailableStock(productId: String, locationId: String): Single<JsonObject> {
        if (productId.isBlank() || locationId.isBlank()) {
            return Single.error(Exception("productId and locationId are required"))
        }

        val query = """
            WITH current_stock AS (
                SELECT COALESCE(SUM(
                    CASE
                        WHEN movement_type IN ('IN', 'ADJUSTMENT') AND to_location_id = $2 THEN quantity
                        WHEN movement_type = 'TRANSFER' AND to_location_id = $2 THEN quantity
                        WHEN movement_type = 'TRANSFER' AND from_location_id = $2 THEN -quantity
                        WHEN movement_type = 'OUT' AND from_location_id = $2 THEN -quantity
                        ELSE 0
                    END
                ), 0) AS quantity
                FROM inventory_movement
                WHERE product_id = $1 AND (to_location_id = $2 OR from_location_id = $2)
            ), active_reservations AS (
                SELECT COALESCE(SUM(quantity), 0) AS quantity
                FROM inventory_reservation
                WHERE product_id = $1 AND location_id = $2 AND status = 'RESERVED'
            )
            SELECT current_stock.quantity - active_reservations.quantity AS quantity
            FROM current_stock, active_reservations
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId, locationId))
            .map { result -> stockQuantity(productId, locationId, result.first().getBigDecimal("quantity"), "AVAILABLE") }
    }

    private fun stockQuantity(productId: String, locationId: String, quantity: BigDecimal, quantityType: String): JsonObject {
        return JsonObject()
            .put("productId", productId)
            .put("locationId", locationId)
            .put("quantity", quantity)
            .put("quantityType", quantityType)
    }

    private fun getAvailableStockQuantity(connection: SqlConnection, productId: String, locationId: String): Single<BigDecimal> {
        val query = """
            WITH current_stock AS (
                SELECT COALESCE(SUM(
                    CASE
                        WHEN movement_type IN ('IN', 'ADJUSTMENT') AND to_location_id = $2 THEN quantity
                        WHEN movement_type = 'TRANSFER' AND to_location_id = $2 THEN quantity
                        WHEN movement_type = 'TRANSFER' AND from_location_id = $2 THEN -quantity
                        WHEN movement_type = 'OUT' AND from_location_id = $2 THEN -quantity
                        ELSE 0
                    END
                ), 0) AS quantity
                FROM inventory_movement
                WHERE product_id = $1 AND (to_location_id = $2 OR from_location_id = $2)
            ), active_reservations AS (
                SELECT COALESCE(SUM(quantity), 0) AS quantity
                FROM inventory_reservation
                WHERE product_id = $1 AND location_id = $2 AND status = 'RESERVED'
            )
            SELECT current_stock.quantity - active_reservations.quantity AS quantity
            FROM current_stock, active_reservations
        """.trimIndent()

        return connection.preparedQuery(query)
            .rxExecute(Tuple.of(productId, locationId))
            .map { result -> result.first().getBigDecimal("quantity") }
    }

    fun addSalesOrderLine(
        orderId: String,
        productId: String,
        sku: String?,
        quantityOrdered: BigDecimal,
        unitPrice: BigDecimal
    ): Single<JsonObject> {
        val orderQuery = "SELECT status FROM sales_order WHERE sales_order_id = $1"
        val productQuery = "SELECT sku FROM product WHERE product_id = $1 AND active = true"
        val lineInsertQuery = """
            INSERT INTO sales_order_line (line_id, sales_order_id, product_id, sku, quantity_ordered, quantity_fulfilled, unit_price, line_total, status, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, 0, $6, $7, 'PENDING', NOW(), NOW())
            RETURNING line_id, sales_order_id, product_id, sku, quantity_ordered, quantity_fulfilled, unit_price, line_total, status, created_at, updated_at
        """.trimIndent()
        val recalcTotalQuery = """
            UPDATE sales_order
            SET total_amount = COALESCE((SELECT SUM(line_total) FROM sales_order_line WHERE sales_order_id = $1), 0), updated_at = NOW()
            WHERE sales_order_id = $1
        """.trimIndent()

        if (quantityOrdered <= BigDecimal.ZERO || unitPrice < BigDecimal.ZERO) {
            return Single.error(Exception("quantityOrdered must be > 0 and unitPrice must be >= 0"))
        }

        return inTransaction { connection ->
            connection.preparedQuery(orderQuery)
                .rxExecute(Tuple.of(orderId))
                .flatMap { orderResult ->
                    if (orderResult.size() == 0) {
                        Single.error(Exception("Sales order not found"))
                    } else {
                        val status = orderResult.first().getString("status")
                        if (status != "DRAFT") {
                            Single.error(Exception("Order line can only be added to DRAFT orders"))
                        } else {
                            connection.preparedQuery(productQuery)
                                .rxExecute(Tuple.of(productId))
                                .flatMap { productResult ->
                                    if (productResult.size() == 0) {
                                        Single.error(Exception(ErrorCodes.fromStatus(404)))
                                    } else {
                                        val resolvedSku = sku ?: productResult.first().getString("sku")
                                        val lineId = UUID.randomUUID().toString()
                                        val lineTotal = unitPrice.multiply(quantityOrdered)
                                        connection.preparedQuery(lineInsertQuery)
                                            .rxExecute(
                                                Tuple.tuple()
                                                    .addString(lineId)
                                                    .addString(orderId)
                                                    .addString(productId)
                                                    .addString(resolvedSku)
                                                    .addValue(quantityOrdered)
                                                    .addValue(unitPrice)
                                                    .addValue(lineTotal)
                                            )
                                            .flatMap { insertedLine ->
                                                connection.preparedQuery(recalcTotalQuery)
                                                    .rxExecute(Tuple.of(orderId))
                                                    .map { mapSalesOrderLineRow(insertedLine.first()) }
                                            }
                                    }
                                }
                        }
                    }
                }
        }
    }

    fun confirmSalesOrder(orderId: String, idempotencyKey: String): Single<JsonObject> {
        val idempotencyKeyValue = idempotencyKey.trim()
        val commandName = "confirmSalesOrder"
        val requestFingerprint = commandName
        val orderQuery = "SELECT sales_order_id, status, location_id FROM sales_order WHERE sales_order_id = $1"
        val linesQuery = """
            SELECT line_id, product_id, sku, quantity_ordered, quantity_fulfilled
            FROM sales_order_line
            WHERE sales_order_id = $1
        """.trimIndent()
        val insertReservationQuery = """
            INSERT INTO inventory_reservation (reservation_id, sales_order_id, sales_order_line_id, product_id, sku, location_id, quantity, status, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, 'RESERVED', NOW(), NOW())
        """.trimIndent()
        val updateLineStatusQuery = """
            UPDATE sales_order_line
            SET status = 'RESERVED', updated_at = NOW()
            WHERE line_id = $1 AND status = 'PENDING'
        """.trimIndent()
        val updateOrderQuery = """
            UPDATE sales_order
            SET status = 'CONFIRMED', updated_at = NOW()
            WHERE sales_order_id = $1
        """.trimIndent()

        if (idempotencyKeyValue.isBlank()) {
            return Single.error(Exception("Idempotency-Key is required"))
        }

        return inTransaction { connection ->
            loadCommandIdempotency(connection, orderId, commandName, idempotencyKeyValue, requestFingerprint)
                .flatMap { state ->
                    state.responsePayload?.let { storedResponse ->
                        Single.just(storedResponse)
                    } ?: connection.preparedQuery(orderQuery)
                        .rxExecute(Tuple.of(orderId))
                        .flatMap { orderResult ->
                            if (orderResult.size() == 0) {
                                Single.error(Exception(ErrorCodes.fromStatus(404)))
                            } else {
                                val row = orderResult.first()
                                val orderStatus = row.getString("status")
                                val locationId = row.getString("location_id")
                                if (orderStatus != "DRAFT") {
                                    Single.error(Exception("Only DRAFT orders can be confirmed"))
                                } else {
                                    connection.preparedQuery(linesQuery)
                                        .rxExecute(Tuple.of(orderId))
                                        .flatMap { lineResult ->
                                            if (lineResult.size() == 0) {
                                                Single.error(Exception("Cannot confirm order without lines"))
                                            } else {
                                                val lines = lineResult.map { it }
                                                Observable.fromIterable(lines)
                                                    .concatMapCompletable { line ->
                                                        val quantityOrdered = line.getBigDecimal("quantity_ordered")
                                                        val quantityFulfilled = line.getBigDecimal("quantity_fulfilled")
                                                        val reserveQty = quantityOrdered.subtract(quantityFulfilled)
                                                        if (reserveQty <= BigDecimal.ZERO) {
                                                            connection.preparedQuery(updateLineStatusQuery)
                                                                .rxExecute(Tuple.of(line.getString("line_id")))
                                                                .ignoreElement()
                                                        } else {
                                                            val productId = line.getString("product_id")
                                                            getAvailableStockQuantity(connection, productId, locationId)
                                                                .flatMapCompletable { availableQty ->
                                                                    if (availableQty < reserveQty) {
                                                                        io.reactivex.rxjava3.core.Completable.error(
                                                                            Exception("Insufficient available stock for product $productId at location $locationId")
                                                                        )
                                                                    } else {
                                                                        connection.preparedQuery(insertReservationQuery)
                                                                            .rxExecute(
                                                                                Tuple.tuple()
                                                                                    .addString(UUID.randomUUID().toString())
                                                                                    .addString(orderId)
                                                                                    .addString(line.getString("line_id"))
                                                                                    .addString(productId)
                                                                                    .addString(line.getString("sku"))
                                                                                    .addString(locationId)
                                                                                    .addValue(reserveQty)
                                                                            )
                                                                            .ignoreElement()
                                                                            .andThen(
                                                                                connection.preparedQuery(updateLineStatusQuery)
                                                                                    .rxExecute(Tuple.of(line.getString("line_id")))
                                                                                    .ignoreElement()
                                                                            )
                                                                    }
                                                                }
                                                        }
                                                    }
                                                    .andThen(
                                                        connection.preparedQuery(updateOrderQuery)
                                                            .rxExecute(Tuple.of(orderId))
                                                            .ignoreElement()
                                                    )
                                                    .andThen(
                                                        insertSalesOrderEvent(
                                                            connection,
                                                            orderId,
                                                            "ORDER_CONFIRMED",
                                                            orderStatus,
                                                            "CONFIRMED",
                                                            commandName,
                                                            idempotencyKeyValue,
                                                            null,
                                                            null
                                                        )
                                                    )
                                                    .andThen(
                                                        Single.defer {
                                                            val response = JsonObject()
                                                                .put("salesOrderId", orderId)
                                                                .put("status", "CONFIRMED")
                                                                .put("reservedLineCount", lineResult.size())
                                                            storeCommandIdempotency(
                                                                connection,
                                                                orderId,
                                                                commandName,
                                                                idempotencyKeyValue,
                                                                requestFingerprint,
                                                                200,
                                                                response
                                                            )
                                                        }
                                                    )
                                            }
                                        }
                                }
                            }
                        }
                }
        }
    }

    fun capturePayment(
        orderId: String,
        paymentMethod: String,
        amount: BigDecimal,
        transactionRef: String?,
        idempotencyKey: String
    ): Single<JsonObject> {
        val normalizedPaymentMethod = paymentMethod.uppercase()
        val normalizedTransactionRef = transactionRef?.trim()?.takeIf { it.isNotBlank() }
        val idempotencyKeyValue = idempotencyKey.trim()
        val commandName = "capturePayment"
        val requestFingerprint = listOf(
            normalizedPaymentMethod,
            amount.toPlainString(),
            normalizedTransactionRef ?: ""
        ).joinToString("|")
        val orderQuery = """
            SELECT sales_order_id, status, total_amount
            FROM sales_order
            WHERE sales_order_id = $1
        """.trimIndent()
        val paymentInsertQuery = """
            INSERT INTO payment (payment_id, sales_order_id, payment_method, amount, status, transaction_ref, created_at, updated_at)
            VALUES ($1, $2, $3, $4, 'CAPTURED', $5, NOW(), NOW())
            RETURNING payment_id, sales_order_id, payment_method, amount, status, transaction_ref, created_at, updated_at
        """.trimIndent()
        val capturedTotalQuery = """
            SELECT COALESCE(SUM(amount), 0) AS total_captured
            FROM payment
            WHERE sales_order_id = $1 AND status = 'CAPTURED'
        """.trimIndent()

        if (amount <= BigDecimal.ZERO) {
            return Single.error(Exception("amount must be > 0"))
        }
        if (idempotencyKeyValue.isBlank()) {
            return Single.error(Exception("Idempotency-Key is required"))
        }

        return inTransaction { connection ->
            loadCommandIdempotency(connection, orderId, commandName, idempotencyKeyValue, requestFingerprint)
                .flatMap { state ->
                    state.responsePayload?.let { storedResponse ->
                        Single.just(storedResponse)
                    } ?: connection.preparedQuery(orderQuery)
                        .rxExecute(Tuple.of(orderId))
                        .flatMap { orderResult ->
                            if (orderResult.size() == 0) {
                                Single.error(Exception(ErrorCodes.fromStatus(404)))
                            } else {
                                val row = orderResult.first()
                                val status = row.getString("status")
                                val totalAmount = row.getBigDecimal("total_amount")
                                if (status != "CONFIRMED" && status != "FULFILLED") {
                                    Single.error(Exception("Payment can only be captured for CONFIRMED or FULFILLED orders"))
                                } else {
                                    connection.preparedQuery(paymentInsertQuery)
                                        .rxExecute(
                                            Tuple.of(
                                                UUID.randomUUID().toString(),
                                                orderId,
                                                normalizedPaymentMethod,
                                                amount,
                                                normalizedTransactionRef
                                            )
                                        )
                                        .flatMap { paymentInsertResult ->
                                            connection.preparedQuery(capturedTotalQuery)
                                                .rxExecute(Tuple.of(orderId))
                                                .flatMap { capturedResult ->
                                                    val totalCaptured = capturedResult.first().getBigDecimal("total_captured")
                                                    val response = JsonObject()
                                                        .put("payment", mapPaymentRow(paymentInsertResult.first()))
                                                        .put("totalAmount", totalAmount)
                                                        .put("totalCaptured", totalCaptured)
                                                        .put("balance", totalAmount.subtract(totalCaptured))
                                                    storeCommandIdempotency(
                                                        connection,
                                                        orderId,
                                                        commandName,
                                                        idempotencyKeyValue,
                                                        requestFingerprint,
                                                        201,
                                                        response
                                                    )
                                                }
                                        }
                                }
                            }
                        }
                }
        }
    }

    fun fulfillSalesOrder(orderId: String, createdBy: String?, notes: String?, idempotencyKey: String): Single<JsonObject> {
        val createdByValue = createdBy?.trim()?.takeIf { it.isNotBlank() }
        val notesValue = notes?.trim()?.takeIf { it.isNotBlank() }
        val idempotencyKeyValue = idempotencyKey.trim()
        val commandName = "fulfillSalesOrder"
        val requestFingerprint = listOf(createdByValue ?: "", notesValue ?: "").joinToString("|")
        val orderQuery = """
            SELECT sales_order_id, status, location_id, total_amount
            FROM sales_order
            WHERE sales_order_id = $1
        """.trimIndent()
        val capturedTotalQuery = """
            SELECT COALESCE(SUM(amount), 0) AS total_captured
            FROM payment
            WHERE sales_order_id = $1 AND status = 'CAPTURED'
        """.trimIndent()
        val linesQuery = """
            SELECT line_id, product_id, sku, quantity_ordered, quantity_fulfilled
            FROM sales_order_line
            WHERE sales_order_id = $1 AND (status IN ('PENDING', 'RESERVED') OR quantity_fulfilled < quantity_ordered)
        """.trimIndent()
        val movementInsertQuery = """
            INSERT INTO inventory_movement (movement_id, product_id, sku, movement_type, from_location_id, to_location_id, quantity, reference_type, reference_id, notes, created_by, created_at)
            VALUES ($1, $2, $3, 'OUT', $4, $5, $6, 'SALES_ORDER', $7, $8, $9, NOW())
        """.trimIndent()
        val updateLineQuery = """
            UPDATE sales_order_line
            SET quantity_fulfilled = quantity_ordered, status = 'FULFILLED', updated_at = NOW()
            WHERE line_id = $1
        """.trimIndent()
        val fulfillReservationQuery = """
            UPDATE inventory_reservation
            SET status = 'FULFILLED', updated_at = NOW()
            WHERE sales_order_line_id = $1 AND status = 'RESERVED'
        """.trimIndent()
        val updateOrderQuery = """
            UPDATE sales_order
            SET status = 'FULFILLED', updated_at = NOW()
            WHERE sales_order_id = $1
        """.trimIndent()

        if (idempotencyKeyValue.isBlank()) {
            return Single.error(Exception("Idempotency-Key is required"))
        }

        return inTransaction { connection ->
            loadCommandIdempotency(connection, orderId, commandName, idempotencyKeyValue, requestFingerprint)
                .flatMap { state ->
                    state.responsePayload?.let { storedResponse ->
                        Single.just(storedResponse)
                    } ?: connection.preparedQuery(orderQuery)
                        .rxExecute(Tuple.of(orderId))
                        .flatMap { orderResult ->
                            if (orderResult.size() == 0) {
                                Single.error(Exception(ErrorCodes.fromStatus(404)))
                            } else {
                                val orderRow = orderResult.first()
                                val status = orderRow.getString("status")
                                val locationId = orderRow.getString("location_id")
                                val totalAmount = orderRow.getBigDecimal("total_amount")

                                if (status != "CONFIRMED") {
                                    Single.error(Exception("Only CONFIRMED orders can be fulfilled"))
                                } else {
                                    connection.preparedQuery(capturedTotalQuery)
                                        .rxExecute(Tuple.of(orderId))
                                        .flatMap { capturedResult ->
                                            val totalCaptured = capturedResult.first().getBigDecimal("total_captured")
                                            if (totalCaptured < totalAmount) {
                                                Single.error(Exception("Insufficient captured payment for fulfillment"))
                                            } else {
                                                connection.preparedQuery(linesQuery)
                                                    .rxExecute(Tuple.of(orderId))
                                                    .flatMap { linesResult ->
                                                        if (linesResult.size() == 0) {
                                                            Single.error(Exception("No fulfillable order lines found"))
                                                        } else {
                                                            val lines = linesResult.map { it }
                                                            Observable.fromIterable(lines)
                                                                .concatMapCompletable { line ->
                                                                    val ordered = line.getBigDecimal("quantity_ordered")
                                                                    val fulfilled = line.getBigDecimal("quantity_fulfilled")
                                                                    val remaining = ordered.subtract(fulfilled)
                                                                    if (remaining <= BigDecimal.ZERO) {
                                                                        connection.preparedQuery(updateLineQuery)
                                                                            .rxExecute(Tuple.of(line.getString("line_id")))
                                                                            .ignoreElement()
                                                                    } else {
                                                                        connection.preparedQuery(movementInsertQuery)
                                                                            .rxExecute(
                                                                                Tuple.tuple()
                                                                                    .addString(UUID.randomUUID().toString())
                                                                                    .addString(line.getString("product_id"))
                                                                                    .addString(line.getString("sku"))
                                                                                    .addString(locationId)
                                                                                    .addValue(null)
                                                                                    .addValue(remaining)
                                                                                    .addString(orderId)
                                                                                    .addValue(notesValue)
                                                                                    .addValue(createdByValue)
                                                                            )
                                                                            .ignoreElement()
                                                                            .andThen(
                                                                                connection.preparedQuery(updateLineQuery)
                                                                                    .rxExecute(Tuple.of(line.getString("line_id")))
                                                                                    .ignoreElement()
                                                                            )
                                                                            .andThen(
                                                                                connection.preparedQuery(fulfillReservationQuery)
                                                                                    .rxExecute(Tuple.of(line.getString("line_id")))
                                                                                    .ignoreElement()
                                                                            )
                                                                    }
                                                                }
                                                                .andThen(
                                                                    connection.preparedQuery(updateOrderQuery)
                                                                        .rxExecute(Tuple.of(orderId))
                                                                        .ignoreElement()
                                                                )
                                                                .andThen(
                                                                    insertSalesOrderEvent(
                                                                        connection,
                                                                        orderId,
                                                                        "ORDER_FULFILLED",
                                                                        status,
                                                                        "FULFILLED",
                                                                        commandName,
                                                                        idempotencyKeyValue,
                                                                        notesValue,
                                                                        createdByValue
                                                                    )
                                                                )
                                                                .andThen(
                                                                    Single.defer {
                                                                        val response = JsonObject()
                                                                            .put("salesOrderId", orderId)
                                                                            .put("status", "FULFILLED")
                                                                            .put("fulfilledLineCount", linesResult.size())
                                                                        storeCommandIdempotency(
                                                                            connection,
                                                                            orderId,
                                                                            commandName,
                                                                            idempotencyKeyValue,
                                                                            requestFingerprint,
                                                                            200,
                                                                            response
                                                                        )
                                                                    }
                                                                )
                                                        }
                                                    }
                                            }
                                        }
                                }
                            }
                        }
                }
        }
    }

    fun cancelSalesOrder(orderId: String, reason: String?, idempotencyKey: String): Single<JsonObject> {
        val reasonValue = reason?.trim()?.takeIf { it.isNotBlank() }
        val idempotencyKeyValue = idempotencyKey.trim()
        val commandName = "cancelSalesOrder"
        val requestFingerprint = reasonValue ?: ""
        val orderQuery = """
            SELECT sales_order_id, status
            FROM sales_order
            WHERE sales_order_id = $1
        """.trimIndent()
        val capturedQuery = """
            SELECT COALESCE(SUM(amount), 0) AS total_captured
            FROM payment
            WHERE sales_order_id = $1 AND status = 'CAPTURED'
        """.trimIndent()
        val updateOrderQuery = """
            UPDATE sales_order
            SET status = 'CANCELLED', notes = $2, updated_at = NOW()
            WHERE sales_order_id = $1
        """.trimIndent()
        val updateLineQuery = """
            UPDATE sales_order_line
            SET status = 'CANCELLED', updated_at = NOW()
            WHERE sales_order_id = $1 AND status <> 'FULFILLED'
        """.trimIndent()
        val updateReservationQuery = """
            UPDATE inventory_reservation
            SET status = 'CANCELLED', updated_at = NOW()
            WHERE sales_order_id = $1 AND status = 'RESERVED'
        """.trimIndent()

        if (idempotencyKeyValue.isBlank()) {
            return Single.error(Exception("Idempotency-Key is required"))
        }

        return inTransaction { connection ->
            loadCommandIdempotency(connection, orderId, commandName, idempotencyKeyValue, requestFingerprint)
                .flatMap { state ->
                    state.responsePayload?.let { storedResponse ->
                        Single.just(storedResponse)
                    } ?: connection.preparedQuery(orderQuery)
                        .rxExecute(Tuple.of(orderId))
                        .flatMap { orderResult ->
                            if (orderResult.size() == 0) {
                                Single.error(Exception(ErrorCodes.fromStatus(404)))
                            } else {
                                val status = orderResult.first().getString("status")
                                when (status) {
                                    "FULFILLED" -> Single.error(Exception("Cannot cancel a fulfilled order"))
                                    "CANCELLED" -> {
                                        val response = JsonObject()
                                            .put("salesOrderId", orderId)
                                            .put("status", "CANCELLED")
                                            .put("message", "Order already cancelled")
                                        storeCommandIdempotency(
                                            connection,
                                            orderId,
                                            commandName,
                                            idempotencyKeyValue,
                                            requestFingerprint,
                                            200,
                                            response
                                        )
                                    }
                                    else -> connection.preparedQuery(capturedQuery)
                                        .rxExecute(Tuple.of(orderId))
                                        .flatMap { capturedResult ->
                                            val totalCaptured = capturedResult.first().getBigDecimal("total_captured")
                                            if (totalCaptured > BigDecimal.ZERO) {
                                                Single.error(Exception("Cannot cancel order with captured payment"))
                                            } else {
                                                val nextNotes = reasonValue?.let { "[CANCEL] $it" }
                                                connection.preparedQuery(updateOrderQuery)
                                                    .rxExecute(Tuple.of(orderId, nextNotes))
                                                    .flatMap {
                                                        connection.preparedQuery(updateLineQuery)
                                                            .rxExecute(Tuple.of(orderId))
                                                            .flatMap {
                                                                connection.preparedQuery(updateReservationQuery)
                                                                    .rxExecute(Tuple.of(orderId))
                                                                    .ignoreElement()
                                                                    .andThen(
                                                                        insertSalesOrderEvent(
                                                                            connection,
                                                                            orderId,
                                                                            "ORDER_CANCELLED",
                                                                            status,
                                                                            "CANCELLED",
                                                                            commandName,
                                                                            idempotencyKeyValue,
                                                                            reasonValue,
                                                                            null
                                                                        )
                                                                    )
                                                                    .andThen(
                                                                        Single.defer {
                                                                            val response = JsonObject()
                                                                                .put("salesOrderId", orderId)
                                                                                .put("status", "CANCELLED")
                                                                            storeCommandIdempotency(
                                                                                connection,
                                                                                orderId,
                                                                                commandName,
                                                                                idempotencyKeyValue,
                                                                                requestFingerprint,
                                                                                200,
                                                                                response
                                                                            )
                                                                        }
                                                                    )
                                                            }
                                                    }
                                            }
                                        }
                                }
                            }
                        }
                }
        }
    }

    private fun mapSalesOrderRow(row: Row): JsonObject {
        return JsonObject()
            .put("salesOrderId", row.getString("sales_order_id"))
            .put("orderNumber", row.getString("order_number"))
            .put("orderDate", row.getLocalDateTime("order_date")?.toString())
            .put("salesChannel", row.getString("sales_channel"))
            .put("customerId", row.getString("customer_id"))
            .put("locationId", row.getString("location_id"))
            .put("status", row.getString("status"))
            .put("totalAmount", row.getBigDecimal("total_amount"))
            .put("currency", row.getString("currency"))
            .put("notes", row.getString("notes"))
            .put("createdAt", row.getLocalDateTime("created_at")?.toString())
            .put("updatedAt", row.getLocalDateTime("updated_at")?.toString())
    }

    private fun mapSalesOrderLineRow(row: Row): JsonObject {
        return JsonObject()
            .put("lineId", row.getString("line_id"))
            .put("salesOrderId", row.getString("sales_order_id"))
            .put("productId", row.getString("product_id"))
            .put("sku", row.getString("sku"))
            .put("quantityOrdered", row.getBigDecimal("quantity_ordered"))
            .put("quantityFulfilled", row.getBigDecimal("quantity_fulfilled"))
            .put("unitPrice", row.getBigDecimal("unit_price"))
            .put("lineTotal", row.getBigDecimal("line_total"))
            .put("status", row.getString("status"))
            .put("createdAt", row.getLocalDateTime("created_at")?.toString())
            .put("updatedAt", row.getLocalDateTime("updated_at")?.toString())
    }

    private fun mapReservationRow(row: Row): JsonObject {
        return JsonObject()
            .put("reservationId", row.getString("reservation_id"))
            .put("salesOrderId", row.getString("sales_order_id"))
            .put("salesOrderLineId", row.getString("sales_order_line_id"))
            .put("productId", row.getString("product_id"))
            .put("sku", row.getString("sku"))
            .put("locationId", row.getString("location_id"))
            .put("quantity", row.getBigDecimal("quantity"))
            .put("status", row.getString("status"))
            .put("createdAt", row.getLocalDateTime("created_at")?.toString())
            .put("updatedAt", row.getLocalDateTime("updated_at")?.toString())
    }

    private fun mapPaymentRow(row: Row): JsonObject {
        return JsonObject()
            .put("paymentId", row.getString("payment_id"))
            .put("salesOrderId", row.getString("sales_order_id"))
            .put("paymentMethod", row.getString("payment_method"))
            .put("amount", row.getBigDecimal("amount"))
            .put("status", row.getString("status"))
            .put("transactionRef", row.getString("transaction_ref"))
            .put("createdAt", row.getLocalDateTime("created_at")?.toString())
            .put("updatedAt", row.getLocalDateTime("updated_at")?.toString())
    }

    private fun insertSalesOrderEvent(
        connection: SqlConnection,
        orderId: String,
        eventType: String,
        previousStatus: String?,
        newStatus: String?,
        commandName: String?,
        idempotencyKey: String?,
        notes: String?,
        createdBy: String?
    ) = connection.preparedQuery(
        """
        INSERT INTO sales_order_event (
            event_id,
            sales_order_id,
            event_type,
            previous_status,
            new_status,
            command_name,
            idempotency_key,
            notes,
            created_by,
            created_at
        )
        VALUES ($1, $2, $3, $4::order_status, $5::order_status, $6, $7, $8, $9, NOW())
        """.trimIndent()
    )
        .rxExecute(
            Tuple.tuple()
                .addString(UUID.randomUUID().toString())
                .addString(orderId)
                .addString(eventType)
                .addString(previousStatus)
                .addString(newStatus)
                .addString(commandName)
                .addString(idempotencyKey)
                .addString(notes)
                .addString(createdBy)
        )
        .ignoreElement()

    private data class CommandIdempotencyState(
        val responsePayload: JsonObject?
    )

    private fun loadCommandIdempotency(
        connection: SqlConnection,
        orderId: String,
        commandName: String,
        idempotencyKey: String,
        requestFingerprint: String
    ): Single<CommandIdempotencyState> {
        val selectQuery = """
            SELECT request_fingerprint, response_payload
            FROM order_command_idempotency
            WHERE sales_order_id = $1 AND command_name = $2 AND idempotency_key = $3
        """.trimIndent()
        val insertQuery = """
            INSERT INTO order_command_idempotency (
                idempotency_id,
                sales_order_id,
                command_name,
                idempotency_key,
                request_fingerprint,
                response_status,
                response_payload,
                created_at,
                updated_at
            )
            VALUES ($1, $2, $3, $4, $5, NULL, NULL, NOW(), NOW())
            ON CONFLICT (sales_order_id, command_name, idempotency_key) DO NOTHING
            RETURNING idempotency_id
        """.trimIndent()

        return connection.preparedQuery(selectQuery)
            .rxExecute(Tuple.of(orderId, commandName, idempotencyKey))
            .flatMap { result ->
                if (result.size() > 0) {
                    val row = result.first()
                    val storedFingerprint = row.getString("request_fingerprint")
                    if (storedFingerprint != requestFingerprint) {
                        Single.error(Exception("Idempotency key conflict"))
                    } else {
                        val storedResponse = row.getJsonObject("response_payload")
                        if (storedResponse == null) {
                            Single.error(Exception("Idempotency key conflict"))
                        } else {
                            Single.just(CommandIdempotencyState(storedResponse))
                        }
                    }
                } else {
                    connection.preparedQuery(insertQuery)
                        .rxExecute(
                            Tuple.tuple()
                                .addString(UUID.randomUUID().toString())
                                .addString(orderId)
                                .addString(commandName)
                                .addString(idempotencyKey)
                                .addString(requestFingerprint)
                        )
                        .flatMap { insertResult ->
                            if (insertResult.size() > 0) {
                                Single.just(CommandIdempotencyState(null))
                            } else {
                                connection.preparedQuery(selectQuery)
                                    .rxExecute(Tuple.of(orderId, commandName, idempotencyKey))
                                    .map { retryResult ->
                                        if (retryResult.size() == 0) {
                                            throw IllegalStateException("Failed to claim idempotency key")
                                        }
                                        val row = retryResult.first()
                                        val storedFingerprint = row.getString("request_fingerprint")
                                        if (storedFingerprint != requestFingerprint) {
                                            throw IllegalStateException("Idempotency key conflict")
                                        }
                                        val storedResponse = row.getJsonObject("response_payload")
                                            ?: throw IllegalStateException("Idempotency key conflict")
                                        CommandIdempotencyState(storedResponse)
                                    }
                            }
                        }
                }
            }
    }

    private fun storeCommandIdempotency(
        connection: SqlConnection,
        orderId: String,
        commandName: String,
        idempotencyKey: String,
        requestFingerprint: String,
        responseStatus: Int,
        responsePayload: JsonObject
    ): Single<JsonObject> {
        val updateQuery = """
            UPDATE order_command_idempotency
            SET response_status = $4, response_payload = $5, updated_at = NOW()
            WHERE sales_order_id = $1 AND command_name = $2 AND idempotency_key = $3 AND request_fingerprint = $6
        """.trimIndent()

        return connection.preparedQuery(updateQuery)
            .rxExecute(
                Tuple.tuple()
                    .addString(orderId)
                    .addString(commandName)
                    .addString(idempotencyKey)
                    .addValue(responseStatus)
                    .addValue(responsePayload)
                    .addString(requestFingerprint)
            )
            .flatMap { updateResult ->
                if (updateResult.rowCount() == 0) {
                    Single.error(Exception("Idempotency key conflict"))
                } else {
                    Single.just(responsePayload)
                }
            }
    }

    private fun generateOrderNumber(): Single<String> {
        val sequenceQuery = "SELECT nextval('sales_order_number_seq') AS sequence_value"
        return pool.preparedQuery(sequenceQuery)
            .rxExecute(Tuple.tuple())
            .map { result ->
                val sequenceValue = result.first().getLong("sequence_value")
                    ?: throw IllegalStateException("sales_order_number_seq did not return a value")
                val epoch = LocalDateTime.now().toString().replace(":", "").replace("-", "").replace(".", "")
                val suffix = sequenceValue.toString().padStart(6, '0')
                "SO-$epoch-$suffix"
            }
    }
}
