package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.*

class ProductVariantRepository(private val pool: Pool) {

    fun listProductVariants(productId: String, page: Int, size: Int, sort: String): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val sortField = parts.getOrNull(0) ?: "sku"
        val sortOrder = parts.getOrNull(1)?.uppercase() ?: "ASC"

        val query = "SELECT COUNT(*) as total FROM product_variant WHERE product_id = $1 AND active = true"
        val dataQuery = """
            SELECT variant_id, product_id, sku, name, attributes, active, created_at, updated_at
            FROM product_variant
            WHERE product_id = $1 AND active = true
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        var total = 0

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId))
            .flatMap { countResult ->
                total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery)
                    .rxExecute(Tuple.of(productId))
            }
            .map { result ->
                val data = result.map { row ->
                    val attributes = row.getJsonObject("attributes") ?: JsonObject()
                    JsonObject()
                        .put("variantId", row.getString("variant_id"))
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("attributes", attributes)
                        .put("active", row.getBoolean("active"))
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                }

                JsonObject()
                    .put("data", data)
                    .put("pagination", JsonObject()
                        .put("page", page)
                        .put("size", size)
                        .put("totalElements", total)
                        .put("totalPages", (total + size - 1) / size)
                    )
            }
    }

    fun createProductVariant(
        productId: String,
        sku: String,
        name: String,
        attributes: JsonObject?
    ): Single<JsonObject> {
        val variantId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO product_variant (variant_id, product_id, sku, name, attributes, active, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, true, NOW(), NOW())
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(
                variantId,
                productId,
                sku,
                name,
                attributes?.encode()
            ))
            .map {
                JsonObject()
                    .put("variantId", variantId)
                    .put("productId", productId)
                    .put("sku", sku)
                    .put("name", name)
                    .put("attributes", attributes ?: JsonObject())
                    .put("active", true)
            }
    }

    fun getProductVariant(productId: String, variantId: String): Single<JsonObject> {
        val query = """
            SELECT variant_id, product_id, sku, name, attributes, active, created_at, updated_at
            FROM product_variant
            WHERE variant_id = $1 AND product_id = $2 AND active = true
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(variantId, productId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Product variant not found"))
                } else {
                    val row = result.first()
                    val attributes = row.getJsonObject("attributes") ?: JsonObject()
                    Single.just(JsonObject()
                        .put("variantId", row.getString("variant_id"))
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("attributes", attributes)
                        .put("active", row.getBoolean("active"))
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun updateProductVariant(
        variantId: String,
        name: String,
        attributes: JsonObject?
    ): Single<JsonObject> {
        val query = """
            UPDATE product_variant
            SET name = $1, attributes = $2, updated_at = NOW()
            WHERE variant_id = $3 AND active = true
            RETURNING variant_id, product_id, sku, name, attributes, active, created_at, updated_at
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(name, attributes?.encode(), variantId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Product variant not found"))
                } else {
                    val row = result.first()
                    val attrs = row.getJsonObject("attributes") ?: JsonObject()
                    Single.just(JsonObject()
                        .put("variantId", row.getString("variant_id"))
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("attributes", attrs)
                        .put("active", row.getBoolean("active"))
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun deleteProductVariant(variantId: String): Single<Unit> {
        val query = """
            UPDATE product_variant
            SET active = false, updated_at = NOW()
            WHERE variant_id = $1
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(variantId))
            .flatMapCompletable { Single.just(it).ignoreElement() }
            .toSingle { }
    }

    fun checkSkuExists(sku: String): Single<Boolean> {
        val query = "SELECT COUNT(*) as cnt FROM product_variant WHERE sku = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(sku))
            .map { result ->
                result.first().getInteger("cnt") > 0
            }
    }
}
