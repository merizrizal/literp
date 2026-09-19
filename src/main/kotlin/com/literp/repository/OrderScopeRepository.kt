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
            "ordernumber", "order_number" -> "sales_order.order_number"
            "orderdate", "order_date" -> "sales_order.order_date"
            "saleschannel", "sales_channel" -> "sales_order.sales_channel"
            "status" -> "sales_order.status"
            "totalamount", "total_amount" -> "sales_order.total_amount"
            "createdat", "created_at" -> "sales_order.created_at"
            "updatedat", "updated_at" -> "sales_order.updated_at"
            else -> "sales_order.order_date"
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
            SELECT sales_order.sales_order_id, sales_order.order_number, sales_order.order_date,
                sales_order.sales_channel, sales_order.customer_id, sales_order.location_id,
                sales_order.status, sales_order.total_amount, sales_order.currency, sales_order.notes,
                sales_order.created_at, sales_order.updated_at,
                pos_order_context.shift_id AS pos_shift_id,
                pos_order_context.draft_operator_id AS pos_draft_operator_id
            FROM sales_order
            LEFT JOIN pos_order_context
                ON pos_order_context.sales_order_id = sales_order.sales_order_id
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
        val posContext = row.getString("pos_shift_id")?.let { shiftId ->
            JsonObject()
                .put("shiftId", shiftId)
                .put("draftOperatorId", row.getString("pos_draft_operator_id"))
        }
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
            .put("posContext", posContext)
    }
}

class OrderScopeViolation(message: String) : IllegalArgumentException(message)
