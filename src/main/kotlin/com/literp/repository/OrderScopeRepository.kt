package com.literp.repository

import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Row
import io.vertx.rxjava3.sqlclient.Tuple

class OrderScopeRepository(pool: Pool) : BaseRepository(pool, OrderScopeRepository::class.java) {

    fun findLocation(orderId: String): Maybe<String> {
        val query = "SELECT location_id FROM sales_order WHERE sales_order_id = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(orderId))
            .flatMapMaybe { result ->
                if (result.size() == 0) {
                    Maybe.empty()
                } else {
                    Maybe.just(result.first().getString("location_id"))
                }
            }
    }

    fun listAuthorizedOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        if (authorizedLocationIds.isEmpty()) {
            return Single.error(OrderScopeViolation("Authorized location scope must not be empty"))
        }

        if (!locationId.isNullOrBlank() && locationId !in authorizedLocationIds) {
            return Single.error(OrderScopeViolation("Requested location is outside authorized scope"))
        }

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

        val sortedLocationIds = authorizedLocationIds.toList().sorted()
        val params = mutableListOf<Any?>()
        val locationPlaceholders = sortedLocationIds.mapIndexed { index, location ->
            params.add(location)
            "$${index + 1}"
        }
        var whereClause = "WHERE location_id IN (${locationPlaceholders.joinToString(", ")})"

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
}

class OrderScopeViolation(message: String) : IllegalArgumentException(message)
