package com.literp.repository

import com.literp.common.ErrorCodes
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Row
import io.vertx.rxjava3.sqlclient.Tuple

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

    private fun validateScope(locationId: String?, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            throw PosOperationsScopeViolation("Authorized location scope must not be empty")
        }
        if (!locationId.isNullOrBlank() && locationId !in authorizedLocationIds) {
            throw PosOperationsScopeViolation("Requested location is outside authorized scope")
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
