package com.literp.repository

import com.literp.common.ErrorCodes
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Row
import io.vertx.rxjava3.sqlclient.SqlConnection
import io.vertx.rxjava3.sqlclient.Tuple
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
