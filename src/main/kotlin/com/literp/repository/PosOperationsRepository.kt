package com.literp.repository

import com.literp.common.ErrorCodes
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Row
import io.vertx.rxjava3.sqlclient.SqlConnection
import io.vertx.rxjava3.sqlclient.Tuple
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

class PosOperationsRepository(pool: Pool) : BaseRepository(pool, PosOperationsRepository::class.java) {

    fun listPosTerminals(
        page: Int,
        size: Int,
        sort: String,
        locationId: String?,
        isActive: Boolean?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        validateScope(locationId, authorizedLocationIds)

        val offset = page * size
        val sortParts = sort.split(",")
        val requestedSortField = sortParts.getOrNull(0)?.trim()?.lowercase() ?: "terminalcode"
        val requestedSortOrder = sortParts.getOrNull(1)?.trim()?.uppercase() ?: "ASC"
        val sortField = when (requestedSortField) {
            "terminalcode", "terminal_code" -> "terminal_code"
            "createdat", "created_at" -> "created_at"
            else -> "terminal_code"
        }
        val sortOrder = if (requestedSortOrder == "ASC" || requestedSortOrder == "DESC") requestedSortOrder else "ASC"

        val params = mutableListOf<Any?>()
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, authorizedLocationId ->
            params.add(authorizedLocationId)
            "$${index + 1}"
        }
        var whereClause = "WHERE location_id IN (${locationPlaceholders.joinToString(", ")})"

        if (!locationId.isNullOrBlank()) {
            whereClause += " AND location_id = $${params.size + 1}"
            params.add(locationId)
        }
        if (isActive != null) {
            whereClause += " AND is_active = $${params.size + 1}"
            params.add(isActive)
        }

        val countQuery = "SELECT COUNT(*) AS total FROM pos_terminal $whereClause"
        val dataQuery = """
            SELECT terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
            FROM pos_terminal
            $whereClause
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        return pool.preparedQuery(countQuery)
            .rxExecute(Tuple.from(params))
            .flatMap { countResult ->
                val total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery)
                    .rxExecute(Tuple.from(params))
                    .map { dataResult ->
                        JsonObject()
                            .put("data", dataResult.map(::mapTerminalRow))
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
    }

    fun createPosTerminal(
        locationId: String,
        terminalCode: String,
        deviceName: String,
        idempotencyKey: String,
        actorSubject: String,
        organizationId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        val normalizedLocationId = requiredValue(locationId, "locationId", 255)
        val normalizedTerminalCode = requiredValue(terminalCode, "terminalCode", 50)
        val normalizedDeviceName = requiredValue(deviceName, "deviceName", 255)
        val normalizedIdempotencyKey = requiredValue(idempotencyKey, "Idempotency-Key", 255)
        val normalizedActorSubject = requiredValue(actorSubject, "actorSubject", 255)
        val normalizedOrganizationId = requiredValue(organizationId, "organizationId", 255)
        validateScope(normalizedLocationId, authorizedLocationIds)

        val requestFingerprint = requestFingerprint(
            "createPosTerminal",
            normalizedLocationId,
            normalizedTerminalCode,
            normalizedDeviceName
        )

        return inTransaction { connection ->
            claimPosCommand(
                connection = connection,
                organizationId = normalizedOrganizationId,
                actorSubject = normalizedActorSubject,
                operationId = "createPosTerminal",
                targetId = normalizedLocationId,
                idempotencyKey = normalizedIdempotencyKey,
                requestFingerprint = requestFingerprint
            ).flatMap { command ->
                if (command.responsePayload != null) {
                    Single.just(command.responsePayload)
                } else {
                    val terminalId = UUID.randomUUID().toString()
                    connection.preparedQuery(
                        """
                        INSERT INTO pos_terminal (
                            terminal_id,
                            location_id,
                            terminal_code,
                            device_name,
                            is_active,
                            created_at,
                            updated_at
                        )
                        VALUES ($1, $2, $3, $4, true, NOW(), NOW())
                        RETURNING terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
                        """.trimIndent()
                    ).rxExecute(
                        Tuple.tuple()
                            .addString(terminalId)
                            .addString(normalizedLocationId)
                            .addString(normalizedTerminalCode)
                            .addString(normalizedDeviceName)
                    ).flatMap { result ->
                        if (result.size() == 0) {
                            Single.error<JsonObject>(IllegalStateException("POS terminal creation returned no row"))
                        } else {
                            val response = mapTerminalRow(result.first())
                            storePosCommand(
                                connection = connection,
                                organizationId = normalizedOrganizationId,
                                actorSubject = normalizedActorSubject,
                                operationId = "createPosTerminal",
                                targetId = normalizedLocationId,
                                idempotencyKey = normalizedIdempotencyKey,
                                requestFingerprint = requestFingerprint,
                                responseStatus = 201,
                                responsePayload = response
                            ).map { response }
                        }
                    }
                }
            }
        }.onErrorResumeNext { error ->
            when {
                isForeignKeyViolation(error) -> Single.error(Exception(ErrorCodes.fromStatus(404)))
                isUniqueViolation(error) -> Single.error(PosOperationsConflict("Terminal code already exists"))
                else -> Single.error(error)
            }
        }
    }

    fun updatePosTerminal(
        terminalId: String,
        terminalCode: String?,
        deviceName: String?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        val normalizedTerminalId = requiredValue(terminalId, "terminalId", 255)
        val normalizedTerminalCode = optionalValue(terminalCode, "terminalCode", 50)
        val normalizedDeviceName = optionalValue(deviceName, "deviceName", 255)
        if (normalizedTerminalCode == null && normalizedDeviceName == null) {
            throw PosOperationsValidation("terminalCode or deviceName is required")
        }
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        return inTransaction { connection ->
            selectScopedTerminalForUpdate(connection, normalizedTerminalId, authorizedLocationIds)
                .flatMap {
                    connection.preparedQuery(
                        """
                        UPDATE pos_terminal
                        SET terminal_code = COALESCE($2, terminal_code),
                            device_name = COALESCE($3, device_name),
                            updated_at = NOW()
                        WHERE terminal_id = $1
                        RETURNING terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
                        """.trimIndent()
                    ).rxExecute(
                        Tuple.tuple()
                            .addString(normalizedTerminalId)
                            .addValue(normalizedTerminalCode)
                            .addValue(normalizedDeviceName)
                    ).flatMap { result ->
                        if (result.size() == 0) {
                            Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                        } else {
                            Single.just(mapTerminalRow(result.first()))
                        }
                    }
                }
        }.onErrorResumeNext { error ->
            if (isUniqueViolation(error)) {
                Single.error(PosOperationsConflict("Terminal code already exists"))
            } else {
                Single.error(error)
            }
        }
    }

    fun deactivatePosTerminal(
        terminalId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        val normalizedTerminalId = requiredValue(terminalId, "terminalId", 255)
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        return inTransaction { connection ->
            selectScopedTerminalForUpdate(connection, normalizedTerminalId, authorizedLocationIds)
                .flatMap { terminalRow ->
                    connection.preparedQuery(
                        "SELECT 1 FROM pos_shift WHERE terminal_id = $1 AND status = 'OPEN' LIMIT 1"
                    ).rxExecute(Tuple.of(normalizedTerminalId)).flatMap { openShiftResult ->
                        when {
                            openShiftResult.size() > 0 ->
                                Single.error<JsonObject>(PosOperationsConflict("Terminal has an open shift"))
                            !terminalRow.getBoolean("is_active") ->
                                Single.just(mapTerminalRow(terminalRow))
                            else -> connection.preparedQuery(
                                """
                                UPDATE pos_terminal
                                SET is_active = false, updated_at = NOW()
                                WHERE terminal_id = $1
                                RETURNING terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
                                """.trimIndent()
                            ).rxExecute(Tuple.of(normalizedTerminalId)).flatMap { result ->
                                if (result.size() == 0) {
                                    Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                                } else {
                                    Single.just(mapTerminalRow(result.first()))
                                }
                            }
                        }
                    }
                }
        }
    }

    fun openPosShift(
        terminalId: String,
        openingBalance: String,
        currency: String,
        idempotencyKey: String,
        actorSubject: String,
        organizationId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = Single.defer {
        val normalizedTerminalId = requiredValue(terminalId, "terminalId", 255)
        val normalizedOpeningBalance = parseMoney(openingBalance, "openingBalance")
        val normalizedCurrency = requiredCurrency(currency)
        val normalizedIdempotencyKey = requiredValue(idempotencyKey, "Idempotency-Key", 128)
        val normalizedActorSubject = requiredValue(actorSubject, "actorSubject", 255)
        val normalizedOrganizationId = requiredValue(organizationId, "organizationId", 255)
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val requestFingerprint = requestFingerprint(
            "openPosShift",
            normalizedTerminalId,
            normalizedOpeningBalance.toPlainString(),
            normalizedCurrency
        )

        inTransaction { connection ->
            selectScopedTerminalForUpdate(connection, normalizedTerminalId, authorizedLocationIds)
                .flatMap { terminalRow ->
                    if (!terminalRow.getBoolean("is_active")) {
                        Single.error<JsonObject>(PosOperationsConflict("Terminal is inactive"))
                    } else {
                        claimPosCommand(
                            connection = connection,
                            organizationId = normalizedOrganizationId,
                            actorSubject = normalizedActorSubject,
                            operationId = "openPosShift",
                            targetId = normalizedTerminalId,
                            idempotencyKey = normalizedIdempotencyKey,
                            requestFingerprint = requestFingerprint
                        ).flatMap { command ->
                            command.responsePayload?.let { Single.just(it) }
                                ?: connection.preparedQuery(
                                    "SELECT 1 FROM pos_shift WHERE terminal_id = $1 AND status = 'OPEN' LIMIT 1"
                                ).rxExecute(Tuple.of(normalizedTerminalId)).flatMap { openShiftResult ->
                                    if (openShiftResult.size() > 0) {
                                        Single.error<JsonObject>(PosOperationsConflict("Terminal already has an open shift"))
                                    } else {
                                        connection.preparedQuery(
                                            """
                                            SELECT COALESCE(MAX(shift_number), 0) + 1 AS next_shift_number
                                            FROM pos_shift
                                            WHERE terminal_id = $1
                                              AND shift_date = (NOW() AT TIME ZONE 'UTC')::date
                                            """.trimIndent()
                                        ).rxExecute(Tuple.of(normalizedTerminalId)).flatMap { numberResult ->
                                            val shiftNumber = numberResult.first().getInteger("next_shift_number")
                                            val shiftId = UUID.randomUUID().toString()
                                            connection.preparedQuery(
                                                """
                                                INSERT INTO pos_shift (
                                                    shift_id,
                                                    terminal_id,
                                                    operator_id,
                                                    shift_date,
                                                    shift_number,
                                                    opened_at,
                                                    closed_at,
                                                    opening_balance,
                                                    closing_balance,
                                                    status,
                                                    created_at,
                                                    currency,
                                                    expected_cash,
                                                    cash_variance,
                                                    closed_by,
                                                    is_reconcilable
                                                )
                                                VALUES (
                                                    $1,
                                                    $2,
                                                    $3,
                                                    (NOW() AT TIME ZONE 'UTC')::date,
                                                    $4,
                                                    (NOW() AT TIME ZONE 'UTC'),
                                                    NULL,
                                                    $5,
                                                    NULL,
                                                    'OPEN',
                                                    (NOW() AT TIME ZONE 'UTC'),
                                                    $6,
                                                    NULL,
                                                    NULL,
                                                    NULL,
                                                    true
                                                )
                                                RETURNING shift_id, terminal_id, operator_id, shift_date, shift_number,
                                                    opened_at, closed_at, opening_balance, closing_balance, status,
                                                    created_at, currency, expected_cash, cash_variance, closed_by
                                                """.trimIndent()
                                            ).rxExecute(
                                                Tuple.tuple()
                                                    .addString(shiftId)
                                                    .addString(normalizedTerminalId)
                                                    .addString(normalizedActorSubject)
                                                    .addInteger(shiftNumber)
                                                    .addValue(normalizedOpeningBalance)
                                                    .addString(normalizedCurrency)
                                            ).flatMap { result ->
                                                if (result.size() == 0) {
                                                    Single.error<JsonObject>(IllegalStateException("POS shift opening returned no row"))
                                                } else {
                                                    val response = mapShiftRow(result.first())
                                                    storePosCommand(
                                                        connection = connection,
                                                        organizationId = normalizedOrganizationId,
                                                        actorSubject = normalizedActorSubject,
                                                        operationId = "openPosShift",
                                                        targetId = normalizedTerminalId,
                                                        idempotencyKey = normalizedIdempotencyKey,
                                                        requestFingerprint = requestFingerprint,
                                                        responseStatus = 201,
                                                        responsePayload = response
                                                    ).map { response }
                                                }
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
        }.onErrorResumeNext { error ->
            if (isUniqueViolation(error)) {
                Single.error(PosOperationsConflict("Terminal already has an open shift"))
            } else {
                Single.error(error)
            }
        }
    }

    fun getCurrentPosShift(
        terminalId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = Single.defer {
        val normalizedTerminalId = requiredValue(terminalId, "terminalId", 255)
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val params = mutableListOf<Any?>(normalizedTerminalId)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, locationId ->
            params.add(locationId)
            "$${index + 2}"
        }
        val query = """
            SELECT s.shift_id, s.terminal_id, s.operator_id, s.shift_date, s.shift_number,
                s.opened_at, s.closed_at, s.opening_balance, s.closing_balance, s.status,
                s.created_at, s.currency, s.expected_cash, s.cash_variance, s.closed_by
            FROM pos_shift s
            JOIN pos_terminal t ON t.terminal_id = s.terminal_id
            WHERE s.terminal_id = $1
              AND t.location_id IN (${locationPlaceholders.joinToString(", ")})
              AND s.status = 'OPEN'
            ORDER BY s.shift_date DESC, s.shift_number DESC
            LIMIT 1
        """.trimIndent()

        pool.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(mapShiftRow(result.first()))
                }
            }
    }

    fun closePosShift(
        shiftId: String,
        closingBalance: String,
        idempotencyKey: String,
        actorSubject: String,
        organizationId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = Single.defer {
        val normalizedShiftId = requiredValue(shiftId, "shiftId", 255)
        val normalizedClosingBalance = parseMoney(closingBalance, "closingBalance")
        val normalizedIdempotencyKey = requiredValue(idempotencyKey, "Idempotency-Key", 128)
        val normalizedActorSubject = requiredValue(actorSubject, "actorSubject", 255)
        val normalizedOrganizationId = requiredValue(organizationId, "organizationId", 255)
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val requestFingerprint = requestFingerprint(
            "closePosShift",
            normalizedShiftId,
            normalizedClosingBalance.toPlainString()
        )

        inTransaction { connection ->
            selectScopedTerminalByShiftForUpdate(connection, normalizedShiftId, authorizedLocationIds)
                .flatMap {
                    selectPosShiftForUpdate(connection, normalizedShiftId)
                }
                .flatMap { shiftRow ->
                    if (shiftRow.getString("operator_id") != normalizedActorSubject) {
                        Single.error<JsonObject>(PosOperationsScopeViolation("POS shift owner mismatch"))
                    } else {
                        claimPosCommand(
                            connection = connection,
                            organizationId = normalizedOrganizationId,
                            actorSubject = normalizedActorSubject,
                            operationId = "closePosShift",
                            targetId = normalizedShiftId,
                            idempotencyKey = normalizedIdempotencyKey,
                            requestFingerprint = requestFingerprint
                        ).flatMap { command ->
                            command.responsePayload?.let { Single.just(it) }
                                ?: when {
                                    shiftRow.getBoolean("is_reconcilable") != true ->
                                        Single.error<JsonObject>(PosOperationsConflict("POS shift is not reconciliable"))
                                    shiftRow.getString("status") != "OPEN" ->
                                        Single.error<JsonObject>(PosOperationsConflict("POS shift is not open"))
                                    else -> shiftCashMovement(connection, normalizedShiftId).flatMap { cashMovement ->
                                        val expectedCash = shiftRow.getBigDecimal("opening_balance").add(cashMovement)
                                        if (expectedCash < BigDecimal.ZERO || expectedCash > BigDecimal("999999999999.99")) {
                                            Single.error<JsonObject>(PosOperationsConflict("Expected cash exceeds supported range"))
                                        } else {
                                            val cashVariance = normalizedClosingBalance.subtract(expectedCash)
                                            connection.preparedQuery(
                                                """
                                                UPDATE pos_shift
                                                SET closed_at = NOW(),
                                                    closing_balance = $2,
                                                    expected_cash = $3,
                                                    cash_variance = $4,
                                                    closed_by = $5,
                                                    status = 'CLOSED'
                                                WHERE shift_id = $1 AND status = 'OPEN'
                                                RETURNING shift_id, terminal_id, operator_id, shift_date, shift_number,
                                                    opened_at, closed_at, opening_balance, closing_balance, status,
                                                    created_at, currency, expected_cash, cash_variance, closed_by
                                                """.trimIndent()
                                            ).rxExecute(
                                                Tuple.tuple()
                                                    .addString(normalizedShiftId)
                                                    .addValue(normalizedClosingBalance)
                                                    .addValue(expectedCash)
                                                    .addValue(cashVariance)
                                                    .addString(normalizedActorSubject)
                                            ).flatMap { closeResult ->
                                                if (closeResult.size() == 0) {
                                                    Single.error<JsonObject>(PosOperationsConflict("POS shift is not open"))
                                                } else {
                                                    val response = mapShiftRow(closeResult.first())
                                                    storePosCommand(
                                                        connection = connection,
                                                        organizationId = normalizedOrganizationId,
                                                        actorSubject = normalizedActorSubject,
                                                        operationId = "closePosShift",
                                                        targetId = normalizedShiftId,
                                                        idempotencyKey = normalizedIdempotencyKey,
                                                        requestFingerprint = requestFingerprint,
                                                        responseStatus = 200,
                                                        responsePayload = response
                                                    ).map { response }
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

    fun getPosTerminal(
        terminalId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val params = mutableListOf<Any?>(terminalId)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, authorizedLocationId ->
            params.add(authorizedLocationId)
            "$${index + 2}"
        }
        val query = """
            SELECT terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
            FROM pos_terminal
            WHERE terminal_id = $1 AND location_id IN (${locationPlaceholders.joinToString(", ")})
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(mapTerminalRow(result.first()))
                }
            }
    }

    fun generatePosReceipt(
        salesOrderId: String,
        idempotencyKey: String,
        actorSubject: String,
        organizationId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = Single.defer {
        val orderId = requiredUuid(salesOrderId, "salesOrderId")
        val key = requiredValue(idempotencyKey, "Idempotency-Key", 128)
        val actor = requiredValue(actorSubject, "actorSubject", 255)
        val organization = requiredValue(organizationId, "organizationId", 255)
        val locations = authorizedLocationIds.toSet()
        validateScope(null, locations)
        val fingerprint = requestFingerprint("generatePosReceipt", orderId)
        inTransaction { connection ->
            scopedReceiptOrder(connection, orderId, locations, false).flatMap { initial ->
                val shiftId = initial.getString("shift_id")
                    ?: return@flatMap Single.error<JsonObject>(PosOperationsConflict("Receipt requires an attributed POS order"))
                // Match capture/close lock order, including the receipt's shift foreign key.
                selectScopedTerminalByShiftForUpdate(connection, shiftId, locations)
                    .flatMap { selectPosShiftForUpdate(connection, shiftId) }
                    .flatMap { scopedReceiptOrder(connection, orderId, locations, true) }
                    .flatMap { order ->
                        if (order.getString("shift_id") != shiftId) {
                            return@flatMap Single.error<JsonObject>(PosOperationsConflict("POS order attribution changed"))
                        }
                        claimPosCommand(connection, organization, actor, "generatePosReceipt", orderId, key, fingerprint)
                            .flatMap { command ->
                                command.responsePayload?.let { Single.just(it) }
                                    ?: insertIssuedReceipt(connection, order, actor).flatMap { receipt ->
                                        storePosCommand(connection, organization, actor, "generatePosReceipt", orderId,
                                            key, fingerprint, 201, receipt)
                                    }
                            }
                    }
            }
        }.onErrorResumeNext { error ->
            Single.error(if (isUniqueViolation(error)) PosOperationsConflict("Receipt already issued or receipt number conflict") else error)
        }
    }

    fun createPosReceiptRefund(
        receiptId: String, paymentId: String, amount: String, shiftId: String, reason: String,
        idempotencyKey: String, actorSubject: String, organizationId: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = Single.defer {
        val request = RefundCommand(
            requiredUuid(receiptId, "receiptId"), requiredUuid(paymentId, "paymentId"), parseMoney(amount, "amount"),
            requiredUuid(shiftId, "shiftId"), requiredValue(reason, "reason", 500),
            requiredValue(idempotencyKey, "Idempotency-Key", 128), requiredValue(actorSubject, "actorSubject", 255),
            requiredValue(organizationId, "organizationId", 255), authorizedLocationIds.toSet()
        )
        if (request.amount.signum() <= 0) throw PosOperationsValidation("amount must be positive")
        validateScope(null, request.locations)
        inTransaction { connection ->
            selectScopedTerminalByShiftForUpdate(connection, request.shiftId, request.locations).flatMap { terminal ->
                selectPosShiftForUpdate(connection, request.shiftId).flatMap { shift ->
                    if (shift.getString("operator_id") != request.actor) {
                        return@flatMap Single.error<JsonObject>(PosOperationsScopeViolation("POS shift owner mismatch"))
                    }
                    scopedRefundReceipt(connection, request.receiptId, request.locations, false).flatMap { initial ->
                        scopedReceiptOrder(connection, initial.getString("sales_order_id"), request.locations, true).flatMap { order ->
                            connection.preparedQuery("SELECT payment_id, sales_order_id, payment_method, amount, status FROM payment WHERE payment_id = $1 FOR NO KEY UPDATE")
                                .rxExecute(Tuple.of(request.paymentId)).flatMap { payments ->
                                    if (payments.size() == 0 || payments.first().getString("sales_order_id") != order.getString("sales_order_id")) {
                                        return@flatMap Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                                    }
                                    scopedRefundReceipt(connection, request.receiptId, request.locations, true).flatMap { receipt ->
                                        if (receipt.getString("sales_order_id") != order.getString("sales_order_id")) {
                                            return@flatMap Single.error<JsonObject>(PosOperationsConflict("Receipt order changed"))
                                        }
                                        if (terminal.getString("location_id") != order.getString("location_id") ||
                                            shift.getString("currency") != receipt.getString("currency") ||
                                            order.getString("currency") != receipt.getString("currency") ||
                                            order.getString("status") != "FULFILLED" || order.getString("sales_channel") != "POS") {
                                            return@flatMap Single.error<JsonObject>(PosOperationsConflict("Refund shift location or currency mismatch"))
                                        }
                                        val fingerprint = requestFingerprint(request.paymentId, request.amount.toPlainString(), request.shiftId, request.reason)
                                        claimPosCommand(connection, request.organization, request.actor, "createPosReceiptRefund", request.receiptId, request.key, fingerprint)
                                            .flatMap { command ->
                                                command.responsePayload?.let { Single.just(it) }
                                                    ?: refundLockedReceipt(connection, request, shift, receipt, payments.first()).flatMap { response ->
                                                        storePosCommand(connection, request.organization, request.actor, "createPosReceiptRefund",
                                                            request.receiptId, request.key, fingerprint, 201, response)
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

    fun getPosReceiptByNumber(
        receiptNumber: String,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        if (receiptNumber.length !in 1..50 || receiptNumber.isBlank()) {
            throw PosOperationsValidation("receiptNumber must be between 1 and 50 characters")
        }
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val params = mutableListOf<Any?>(receiptNumber)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, locationId ->
            params.add(locationId)
            "$${index + 2}"
        }
        val query = receiptRowsQuery(
            whereClause = "r.receipt_number = $1 AND o.location_id IN (${locationPlaceholders.joinToString(", ")})"
        )

        return pool.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(mapReceiptRow(result.first()))
                }
            }
    }

    fun listPosReceiptsBySalesOrder(
        salesOrderId: String,
        page: Int,
        size: Int,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        val normalizedSalesOrderId = requiredUuid(salesOrderId, "salesOrderId")
        if (page < 0) {
            throw PosOperationsValidation("page must be greater than or equal to 0")
        }
        if (size !in 1..100) {
            throw PosOperationsValidation("size must be between 1 and 100")
        }
        validateScope(locationId = null, authorizedLocationIds = authorizedLocationIds)

        val params = mutableListOf<Any?>(normalizedSalesOrderId)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, locationId ->
            params.add(locationId)
            "$${index + 2}"
        }
        val whereClause = "o.sales_order_id = $1 AND o.location_id IN (${locationPlaceholders.joinToString(", ")})"
        val countQuery = """
            SELECT COUNT(r.receipt_id) AS total
            FROM sales_order AS o
            LEFT JOIN receipt AS r ON r.sales_order_id = o.sales_order_id
            WHERE $whereClause
            GROUP BY o.sales_order_id
        """.trimIndent()

        return pool.preparedQuery(countQuery)
            .rxExecute(Tuple.from(params))
            .flatMap { countResult ->
                if (countResult.size() == 0) {
                    Single.error<JsonObject>(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    val total = countResult.first().getLong("total")
                    val dataParams = params.toMutableList().apply {
                        add(size)
                        add(page.toLong() * size)
                    }
                    val dataQuery = receiptRowsQuery(
                        whereClause = whereClause,
                        orderByClause = "ORDER BY r.receipt_date ASC, r.receipt_id ASC",
                        paginationClause = "LIMIT $${params.size + 1} OFFSET $${params.size + 2}"
                    )
                    pool.preparedQuery(dataQuery)
                        .rxExecute(Tuple.from(dataParams))
                        .map { dataResult ->
                            JsonObject()
                                .put("data", dataResult.map(::mapReceiptRow))
                                .put(
                                    "pagination",
                                    JsonObject()
                                        .put("page", page)
                                        .put("size", size)
                                        .put("totalElements", total)
                                        .put("totalPages", if (total == 0L) 0L else (total + size - 1) / size)
                                )
                        }
                }
            }
    }

    private fun selectScopedTerminalForUpdate(
        connection: SqlConnection,
        terminalId: String,
        authorizedLocationIds: Set<String>
    ): Single<Row> {
        val params = mutableListOf<Any?>(terminalId)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, locationId ->
            params.add(locationId)
            "$${index + 2}"
        }
        val query = """
            SELECT terminal_id, location_id, terminal_code, device_name, is_active, created_at, updated_at
            FROM pos_terminal
            WHERE terminal_id = $1 AND location_id IN (${locationPlaceholders.joinToString(", ")})
            FOR UPDATE
        """.trimIndent()

        return connection.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error<Row>(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(result.first())
                }
            }
    }

    private fun selectScopedTerminalByShiftForUpdate(
        connection: SqlConnection,
        shiftId: String,
        authorizedLocationIds: Set<String>
    ): Single<Row> {
        val params = mutableListOf<Any?>(shiftId)
        val locationPlaceholders = authorizedLocationIds.toList().sorted().mapIndexed { index, locationId ->
            params.add(locationId)
            "$${index + 2}"
        }
        val query = """
            SELECT t.terminal_id, t.location_id
            FROM pos_terminal t
            JOIN pos_shift s ON s.terminal_id = t.terminal_id
            WHERE s.shift_id = $1 AND t.location_id IN (${locationPlaceholders.joinToString(", ")})
            FOR UPDATE OF t
        """.trimIndent()

        return connection.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error<Row>(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(result.first())
                }
            }
    }

    private fun selectPosShiftForUpdate(connection: SqlConnection, shiftId: String): Single<Row> = connection.preparedQuery(
        """
        SELECT shift_id, operator_id, opening_balance, status, is_reconcilable, currency
        FROM pos_shift
        WHERE shift_id = $1
        FOR UPDATE
        """.trimIndent()
    ).rxExecute(Tuple.of(shiftId)).flatMap { result ->
        if (result.size() == 0) {
            Single.error<Row>(Exception(ErrorCodes.fromStatus(404)))
        } else {
            Single.just(result.first())
        }
    }

    private fun requiredUuid(value: String, name: String): String {
        val normalized = value.trim()
        val parsed = try {
            UUID.fromString(normalized)
        } catch (_: IllegalArgumentException) {
            throw PosOperationsValidation("$name must be a valid UUID")
        }
        if (normalized.length != 36 || parsed.toString() != normalized.lowercase()) {
            throw PosOperationsValidation("$name must be a valid UUID")
        }
        return parsed.toString()
    }

    private fun requiredValue(value: String, name: String, maximumLength: Int): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw PosOperationsValidation("$name is required")
        }
        if (normalized.length > maximumLength) {
            throw PosOperationsValidation("$name must be at most $maximumLength characters")
        }
        return normalized
    }

    private fun optionalValue(value: String?, name: String, maximumLength: Int): String? =
        value?.let { requiredValue(it, name, maximumLength) }

    private fun parseMoney(value: String, name: String): BigDecimal {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw PosOperationsValidation("$name is required")
        }

        val parsed = try {
            BigDecimal(normalized)
        } catch (_: NumberFormatException) {
            throw PosOperationsValidation("$name must be a decimal amount")
        }
        if (parsed.signum() < 0 || parsed > BigDecimal("999999999999.99")) {
            throw PosOperationsValidation("$name must be between 0 and 999999999999.99")
        }
        if (parsed.scale() > 2) {
            throw PosOperationsValidation("$name must have at most two decimal places")
        }
        return parsed.setScale(2, RoundingMode.UNNECESSARY)
    }

    private fun requiredCurrency(value: String): String {
        val normalized = value.trim()
        if (normalized.length != 3 || normalized.any { it !in 'A'..'Z' }) {
            throw PosOperationsValidation("currency must be three uppercase letters")
        }
        return normalized
    }

    private fun requestFingerprint(vararg values: String): String {
        val canonicalValue = values.joinToString(0x1f.toChar().toString())
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalValue.toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private fun validateScope(locationId: String?, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            throw PosOperationsScopeViolation("Authorized location scope must not be empty")
        }
        if (!locationId.isNullOrBlank() && locationId !in authorizedLocationIds) {
            throw PosOperationsScopeViolation("Requested location is outside authorized scope")
        }
    }

    private data class PosCommandLedgerState(
        val responsePayload: JsonObject?
    )

    private fun claimPosCommand(
        connection: SqlConnection,
        organizationId: String,
        actorSubject: String,
        operationId: String,
        targetId: String,
        idempotencyKey: String,
        requestFingerprint: String
    ): Single<PosCommandLedgerState> {
        val scopeTuple = Tuple.of(organizationId, actorSubject, operationId, targetId, idempotencyKey)
        val selectQuery = """
            SELECT request_fingerprint, response_payload
            FROM pos_command_ledger
            WHERE organization_id = $1
              AND actor_subject = $2
              AND operation_id = $3
              AND target_id = $4
              AND idempotency_key = $5
            FOR UPDATE
        """.trimIndent()
        val insertQuery = """
            INSERT INTO pos_command_ledger (
                command_id,
                organization_id,
                actor_subject,
                operation_id,
                target_id,
                idempotency_key,
                request_fingerprint
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (organization_id, actor_subject, operation_id, target_id, idempotency_key)
            DO NOTHING
            RETURNING command_id
        """.trimIndent()

        return connection.preparedQuery(selectQuery)
            .rxExecute(scopeTuple)
            .flatMap { existingResult ->
                if (existingResult.size() > 0) {
                    Single.just(readPosCommandState(existingResult.first(), requestFingerprint))
                } else {
                    connection.preparedQuery(insertQuery)
                        .rxExecute(
                            Tuple.tuple()
                                .addString(UUID.randomUUID().toString())
                                .addString(organizationId)
                                .addString(actorSubject)
                                .addString(operationId)
                                .addString(targetId)
                                .addString(idempotencyKey)
                                .addString(requestFingerprint)
                        )
                        .flatMap { insertedResult ->
                            if (insertedResult.size() > 0) {
                                Single.just(PosCommandLedgerState(null))
                            } else {
                                connection.preparedQuery(selectQuery)
                                    .rxExecute(scopeTuple)
                                    .flatMap { retryResult ->
                                        if (retryResult.size() == 0) {
                                            Single.error<PosCommandLedgerState>(
                                                IllegalStateException("Failed to claim POS command key")
                                            )
                                        } else {
                                            Single.just(readPosCommandState(retryResult.first(), requestFingerprint))
                                        }
                                    }
                            }
                        }
                }
            }
    }

    private fun readPosCommandState(row: Row, expectedFingerprint: String): PosCommandLedgerState {
        if (row.getString("request_fingerprint") != expectedFingerprint) {
            throw PosOperationsConflict("Idempotency key conflict")
        }
        return PosCommandLedgerState(
            row.getJsonObject("response_payload")
                ?: throw PosOperationsConflict("Idempotency key conflict")
        )
    }

    private fun storePosCommand(
        connection: SqlConnection,
        organizationId: String,
        actorSubject: String,
        operationId: String,
        targetId: String,
        idempotencyKey: String,
        requestFingerprint: String,
        responseStatus: Int,
        responsePayload: JsonObject
    ): Single<JsonObject> {
        val query = """
            UPDATE pos_command_ledger
            SET response_status = $6, response_payload = $7, updated_at = NOW()
            WHERE organization_id = $1
              AND actor_subject = $2
              AND operation_id = $3
              AND target_id = $4
              AND idempotency_key = $5
              AND request_fingerprint = $8
        """.trimIndent()

        return connection.preparedQuery(query)
            .rxExecute(
                Tuple.tuple()
                    .addString(organizationId)
                    .addString(actorSubject)
                    .addString(operationId)
                    .addString(targetId)
                    .addString(idempotencyKey)
                    .addInteger(responseStatus)
                    .addValue(responsePayload)
                    .addString(requestFingerprint)
            )
            .flatMap { result ->
                if (result.rowCount() == 0) {
                    Single.error<JsonObject>(PosOperationsConflict("Idempotency key conflict"))
                } else {
                    Single.just(responsePayload)
                }
            }
    }

    private data class RefundCommand(
        val receiptId: String, val paymentId: String, val amount: BigDecimal, val shiftId: String, val reason: String,
        val key: String, val actor: String, val organization: String, val locations: Set<String>
    )

    private fun scopedRefundReceipt(connection: SqlConnection, receiptId: String, locations: Set<String>, lock: Boolean): Single<Row> {
        val placeholders = locations.indices.joinToString(", ") { "$${it + 2}" }
        return connection.preparedQuery("""
            SELECT r.receipt_id, r.sales_order_id, r.currency, r.total_amount, r.is_api_issued, r.receipt_data
            FROM receipt r JOIN sales_order o ON o.sales_order_id = r.sales_order_id
            WHERE r.receipt_id = $1 AND o.location_id IN ($placeholders)
            ${if (lock) "FOR NO KEY UPDATE OF r" else ""}
        """.trimIndent()).rxExecute(Tuple.from(listOf(receiptId) + locations.sorted())).flatMap { rows ->
            if (rows.size() == 0) Single.error<Row>(Exception(ErrorCodes.fromStatus(404))) else Single.just(rows.first())
        }
    }

    private fun shiftCashMovement(connection: SqlConnection, shiftId: String): Single<BigDecimal> = connection.preparedQuery("""
        SELECT COALESCE((SELECT SUM(p.amount) FROM payment p JOIN pos_payment_context c ON c.payment_id = p.payment_id
            WHERE c.shift_id = $1 AND p.payment_method = 'CASH' AND p.status IN ('CAPTURED', 'REFUNDED')), 0)
            - COALESCE((SELECT SUM(r.amount) FROM pos_receipt_refund r JOIN payment p ON p.payment_id = r.payment_id
                WHERE r.refund_shift_id = $1 AND p.payment_method = 'CASH'), 0) AS cash_movement
    """.trimIndent()).rxExecute(Tuple.of(shiftId)).map { it.first().getBigDecimal("cash_movement") }

    private fun refundLockedReceipt(
        connection: SqlConnection, request: RefundCommand, shift: Row, receipt: Row, payment: Row
    ): Single<JsonObject> {
        if (shift.getString("status") != "OPEN" || shift.getBoolean("is_reconcilable") != true) {
            return Single.error(PosOperationsConflict("POS shift is not open or reconciliable"))
        }
        val captured = receipt.getJsonObject("receipt_data")?.getJsonArray("payments")
            ?.filterIsInstance<JsonObject>()?.singleOrNull { it.getString("paymentId") == request.paymentId }
        val capturedAmount = captured?.getValue("amount")?.toString()?.toBigDecimalOrNull()
        if (receipt.getBoolean("is_api_issued") != true || captured == null || capturedAmount == null ||
            capturedAmount.compareTo(payment.getBigDecimal("amount")) != 0 ||
            captured.getString("paymentMethod") != payment.getString("payment_method") ||
            payment.getString("status") !in setOf("CAPTURED", "REFUNDED")) {
            return Single.error(PosOperationsConflict("Refund requires an API-issued receipt and its original captured payment"))
        }
        return connection.preparedQuery("""
            SELECT COALESCE((SELECT SUM(amount) FROM pos_receipt_refund WHERE payment_id = $1), 0) AS payment_refunded,
                COALESCE((SELECT SUM(amount) FROM pos_receipt_refund WHERE receipt_id = $2), 0) AS receipt_refunded
        """.trimIndent()).rxExecute(Tuple.of(request.paymentId, request.receiptId)).flatMap { totals ->
            val refunded = totals.first().getBigDecimal("payment_refunded")
            if (request.amount > payment.getBigDecimal("amount").subtract(refunded) ||
                request.amount > receipt.getBigDecimal("total_amount").subtract(totals.first().getBigDecimal("receipt_refunded"))) {
                return@flatMap Single.error<JsonObject>(PosOperationsConflict("Refund exceeds remaining payment or receipt value"))
            }
            val available = if (payment.getString("payment_method") == "CASH") {
                shiftCashMovement(connection, request.shiftId).map { it.add(shift.getBigDecimal("opening_balance")) >= request.amount }
            } else Single.just(true)
            available.flatMap { sufficient ->
                if (!sufficient) Single.error<JsonObject>(PosOperationsConflict("Insufficient available drawer cash"))
                else persistReceiptRefund(connection, request, receipt.getString("currency"),
                    refunded.add(request.amount).compareTo(payment.getBigDecimal("amount")) == 0)
            }
        }
    }

    private fun persistReceiptRefund(connection: SqlConnection, request: RefundCommand, currency: String, fullyRefunded: Boolean): Single<JsonObject> =
        connection.preparedQuery("""
            INSERT INTO pos_receipt_refund (refund_id, receipt_id, payment_id, refund_shift_id, actor_subject, reason, amount, currency)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
            RETURNING json_build_object('refundId', refund_id, 'receiptId', receipt_id, 'paymentId', payment_id,
                'refundShiftId', refund_shift_id, 'actorSubject', actor_subject, 'reason', reason, 'amount', amount,
                'currency', currency, 'createdAt', created_at) AS refund
        """.trimIndent()).rxExecute(Tuple.tuple().addString(UUID.randomUUID().toString()).addString(request.receiptId)
            .addString(request.paymentId).addString(request.shiftId).addString(request.actor).addString(request.reason)
            .addValue(request.amount).addString(currency)).flatMap { inserted ->
                val update = if (fullyRefunded) connection.preparedQuery("UPDATE payment SET status = 'REFUNDED', updated_at = NOW() WHERE payment_id = $1")
                    .rxExecute(Tuple.of(request.paymentId)).map { Unit } else Single.just(Unit)
                update.flatMap {
                    connection.preparedQuery(receiptRowsQuery("r.receipt_id = $1")).rxExecute(Tuple.of(request.receiptId)).map { rows ->
                        val receipt = mapReceiptRow(rows.first())
                        val summary = JsonObject()
                        listOf("refundedAmount", "netAmount", "adjustmentStatus", "adjustments").forEach { summary.put(it, receipt.getValue(it)) }
                        JsonObject().put("refund", inserted.first().getJsonObject("refund")).put("receiptAdjustment", summary)
                    }
                }
            }

    private fun scopedReceiptOrder(
        connection: SqlConnection,
        orderId: String,
        locations: Set<String>,
        lock: Boolean
    ): Single<Row> {
        val params = listOf(orderId) + locations.sorted()
        val placeholders = locations.indices.map { "$${it + 2}" }.joinToString(", ")
        val query = """
            SELECT o.sales_order_id, o.order_number, o.location_id, o.status, o.sales_channel,
                o.currency, o.total_amount, c.shift_id, c.draft_operator_id,
                s.currency AS shift_currency, s.is_reconcilable,
                json_build_object('shiftId', s.shift_id, 'operatorId', s.operator_id,
                    'status', s.status, 'openedAt', s.opened_at, 'closedAt', s.closed_at,
                    'currency', s.currency, 'terminalId', s.terminal_id) AS shift_snapshot
            FROM sales_order o
            LEFT JOIN pos_order_context c ON c.sales_order_id = o.sales_order_id
            LEFT JOIN pos_shift s ON s.shift_id = c.shift_id
            WHERE o.sales_order_id = $1 AND o.location_id IN ($placeholders)
            ${if (lock) "FOR NO KEY UPDATE OF o" else ""}
        """.trimIndent()
        return connection.preparedQuery(query).rxExecute(Tuple.from(params)).flatMap { rows ->
            if (rows.size() == 0) Single.error<Row>(Exception(ErrorCodes.fromStatus(404))) else Single.just(rows.first())
        }
    }

    private fun insertIssuedReceipt(connection: SqlConnection, order: Row, actor: String): Single<JsonObject> {
        val orderId = order.getString("sales_order_id")
        if (order.getString("status") != "FULFILLED" || order.getString("sales_channel") != "POS" ||
            order.getBoolean("is_reconcilable") != true || order.getString("currency") != order.getString("shift_currency")) {
            return Single.error(PosOperationsConflict("Receipt requires a fulfilled attributed POS order with trusted currency"))
        }
        return connection.preparedQuery("SELECT 1 FROM receipt WHERE sales_order_id = $1 AND is_api_issued")
            .rxExecute(Tuple.of(orderId)).flatMap { existing ->
                if (existing.size() != 0) return@flatMap Single.error<JsonObject>(PosOperationsConflict("Receipt already issued"))
                connection.preparedQuery(
                    """
                    SELECT COUNT(*) AS line_count, COALESCE(SUM(quantity_ordered), 0) AS total_items,
                        COALESCE(SUM(line_total), 0) AS subtotal,
                        BOOL_AND(status = 'FULFILLED' AND quantity_ordered > 0
                            AND quantity_fulfilled = quantity_ordered
                            AND line_total = ROUND(quantity_ordered * unit_price, 2)) AS consistent,
                        json_agg(json_build_object('lineId', line_id, 'productId', product_id, 'sku', sku,
                            'quantity', quantity_ordered, 'unitPrice', unit_price, 'lineTotal', line_total)
                            ORDER BY created_at, line_id) AS lines
                    FROM sales_order_line WHERE sales_order_id = $1
                    """.trimIndent()
                ).rxExecute(Tuple.of(orderId)).flatMap { lines ->
                    val summary = lines.first()
                    val total = order.getBigDecimal("total_amount")
                    val items = summary.getBigDecimal("total_items")
                    if (summary.getLong("line_count") == 0L || summary.getBoolean("consistent") != true ||
                        summary.getBigDecimal("subtotal").compareTo(total) != 0 ||
                        items > BigDecimal("999999999.999") || total < BigDecimal.ZERO) {
                        return@flatMap Single.error<JsonObject>(PosOperationsConflict("Receipt order lines or totals are inconsistent"))
                    }
                    connection.preparedQuery(
                        """
                        SELECT COALESCE(SUM(p.amount), 0) AS captured,
                            COALESCE(BOOL_AND(c.shift_id = $2 AND c.capture_operator_id IS NOT NULL
                                AND p.amount > 0), true) AS attributed,
                            COALESCE(json_agg(json_build_object('paymentId', p.payment_id,
                                'paymentMethod', p.payment_method, 'amount', p.amount, 'status', p.status,
                                'transactionRef', p.transaction_ref, 'shiftId', c.shift_id,
                                'captureOperatorId', c.capture_operator_id) ORDER BY p.created_at, p.payment_id), '[]'::json) AS payments
                        FROM payment p LEFT JOIN pos_payment_context c ON c.payment_id = p.payment_id
                        WHERE p.sales_order_id = $1 AND p.status = 'CAPTURED'
                        """.trimIndent()
                    ).rxExecute(Tuple.of(orderId, order.getString("shift_id"))).flatMap { payments ->
                        val paymentSummary = payments.first()
                        if (paymentSummary.getBoolean("attributed") != true || paymentSummary.getBigDecimal("captured").compareTo(total) != 0) {
                            return@flatMap Single.error<JsonObject>(PosOperationsConflict("Receipt requires exact attributed payment settlement"))
                        }
                        persistIssuedReceipt(connection, order, actor, summary, paymentSummary)
                    }
                }
            }
    }

    private fun persistIssuedReceipt(
        connection: SqlConnection, order: Row, actor: String, lines: Row, payments: Row
    ): Single<JsonObject> = connection.query(
        """
        SELECT nextval('pos_receipt_number_seq') AS sequence,
            to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYYMMDD') AS date_part,
            CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS issued_at
        """.trimIndent()
    ).rxExecute().flatMap { allocation ->
        val number = allocation.first()
        val receiptId = UUID.randomUUID().toString()
        val snapshot = JsonObject()
            .put("orderNumber", order.getString("order_number"))
            .put("salesOrderId", order.getString("sales_order_id"))
            .put("locationId", order.getString("location_id"))
            .put("currency", order.getString("currency"))
            .put("draftOperatorId", order.getString("draft_operator_id"))
            .put("issuerSubject", actor)
            .put("issuedAt", number.getLocalDateTime("issued_at").toString() + "Z")
            .put("shift", order.getJsonObject("shift_snapshot"))
            .put("lines", lines.getJsonArray("lines"))
            .put("payments", payments.getJsonArray("payments"))
            .put("subtotal", order.getBigDecimal("total_amount"))
            .put("taxAmount", BigDecimal.ZERO)
            .put("totalAmount", order.getBigDecimal("total_amount"))
        connection.preparedQuery(
            """
            INSERT INTO receipt (receipt_id, sales_order_id, shift_id, receipt_number, receipt_date,
                total_items, subtotal, tax_amount, total_amount, receipt_data, currency, issued_by, is_api_issued)
            VALUES ($1, $2, $3, $4, $5, $6, $7, 0, $7, $8, $9, $10, true)
            """.trimIndent()
        ).rxExecute(Tuple.tuple()
            .addString(receiptId).addString(order.getString("sales_order_id")).addString(order.getString("shift_id"))
            .addString("RCPT-${number.getString("date_part")}-${number.getLong("sequence")}")
            .addLocalDateTime(number.getLocalDateTime("issued_at")).addValue(lines.getBigDecimal("total_items"))
            .addValue(order.getBigDecimal("total_amount")).addJsonObject(snapshot)
            .addString(order.getString("currency")).addString(actor)
        ).flatMap {
            connection.preparedQuery(receiptRowsQuery("r.receipt_id = $1"))
                .rxExecute(Tuple.of(receiptId)).map { mapReceiptRow(it.first()) }
        }
    }

    private fun receiptRowsQuery(
        whereClause: String,
        orderByClause: String = "",
        paginationClause: String = ""
    ): String {
        val suffix = listOf(orderByClause, paginationClause)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return """
            SELECT
                r.receipt_id,
                r.sales_order_id,
                r.shift_id,
                r.currency,
                r.receipt_number,
                r.receipt_date,
                r.total_items,
                r.subtotal,
                r.tax_amount,
                r.total_amount,
                r.receipt_data,
                r.created_at,
                refund_summary.refunded_amount,
                (r.total_amount - refund_summary.refunded_amount)::numeric(14, 2) AS net_amount,
                CASE
                    WHEN refund_summary.refunded_amount = 0 THEN 'ISSUED'
                    WHEN refund_summary.refunded_amount >= r.total_amount THEN 'REFUNDED'
                    ELSE 'PARTIALLY_REFUNDED'
                END AS adjustment_status,
                refund_summary.adjustments
            FROM receipt AS r
            JOIN sales_order AS o ON o.sales_order_id = r.sales_order_id
            LEFT JOIN LATERAL (
                SELECT
                    COALESCE(SUM(adjustment.amount), 0)::numeric(14, 2) AS refunded_amount,
                    COALESCE(
                        json_agg(
                            json_build_object(
                                'refundId', adjustment.refund_id,
                                'receiptId', adjustment.receipt_id,
                                'paymentId', adjustment.payment_id,
                                'refundShiftId', adjustment.refund_shift_id,
                                'actorSubject', adjustment.actor_subject,
                                'reason', adjustment.reason,
                                'amount', adjustment.amount,
                                'currency', adjustment.currency,
                                'createdAt', adjustment.created_at
                            ) ORDER BY adjustment.created_at, adjustment.refund_id
                        ),
                        '[]'::json
                    ) AS adjustments
                FROM pos_receipt_refund AS adjustment
                WHERE adjustment.receipt_id = r.receipt_id
            ) AS refund_summary ON TRUE
            WHERE $whereClause
            $suffix
        """.trimIndent()
    }

    private fun mapReceiptRow(row: Row): JsonObject = JsonObject()
        .put("receiptId", row.getString("receipt_id"))
        .put("salesOrderId", row.getString("sales_order_id"))
        .put("shiftId", row.getString("shift_id"))
        .put("currency", row.getString("currency"))
        .put("receiptNumber", row.getString("receipt_number"))
        .put("receiptDate", row.getLocalDateTime("receipt_date")?.toString())
        .put("totalItems", row.getBigDecimal("total_items"))
        .put("subtotal", row.getBigDecimal("subtotal"))
        .put("taxAmount", row.getBigDecimal("tax_amount"))
        .put("totalAmount", row.getBigDecimal("total_amount"))
        .put("refundedAmount", row.getBigDecimal("refunded_amount"))
        .put("netAmount", row.getBigDecimal("net_amount"))
        .put("adjustmentStatus", row.getString("adjustment_status"))
        .put("adjustments", row.getJsonArray("adjustments") ?: JsonArray())
        .put("receiptData", row.getJsonObject("receipt_data"))
        .put("createdAt", row.getLocalDateTime("created_at")?.toString())

    private fun mapShiftRow(row: Row): JsonObject = JsonObject()
        .put("shiftId", row.getString("shift_id"))
        .put("terminalId", row.getString("terminal_id"))
        .put("operatorId", row.getString("operator_id"))
        .put("shiftDate", row.getLocalDate("shift_date")?.toString())
        .put("shiftNumber", row.getInteger("shift_number"))
        .put("openedAt", row.getLocalDateTime("opened_at")?.toString())
        .put("closedAt", row.getLocalDateTime("closed_at")?.toString())
        .put("openingBalance", row.getBigDecimal("opening_balance"))
        .put("closingBalance", row.getBigDecimal("closing_balance"))
        .put("status", row.getString("status"))
        .put("createdAt", row.getLocalDateTime("created_at")?.toString())
        .put("currency", row.getString("currency"))
        .put("expectedCash", row.getBigDecimal("expected_cash"))
        .put("cashVariance", row.getBigDecimal("cash_variance"))
        .put("closedBy", row.getString("closed_by"))

    private fun mapTerminalRow(row: Row): JsonObject = JsonObject()
        .put("terminalId", row.getString("terminal_id"))
        .put("locationId", row.getString("location_id"))
        .put("terminalCode", row.getString("terminal_code"))
        .put("deviceName", row.getString("device_name"))
        .put("isActive", row.getBoolean("is_active"))
        .put("createdAt", row.getLocalDateTime("created_at").toString())
        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
}

class PosOperationsScopeViolation(message: String) : IllegalArgumentException(message)

class PosOperationsValidation(message: String) : IllegalArgumentException(message)

class PosOperationsConflict(message: String) : IllegalStateException(message)
