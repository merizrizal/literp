package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.*

class ProductRepository(pool: Pool) : BaseRepository(pool, ProductRepository::class.java) {

    fun listProducts(page: Int, size: Int, sort: String): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val rawField = parts.getOrNull(0)?.trim() ?: "sku"
        val rawOrder = parts.getOrNull(1)?.trim()?.uppercase() ?: "ASC"

        val sortField = when (rawField.lowercase()) {
            "sku" -> "sku"
            "name" -> "name"
            "producttype", "product_type" -> "product_type"
            "baseuom", "base_uom" -> "base_uom"
            "createdat", "created_at" -> "created_at"
            "updatedat", "updated_at" -> "updated_at"
            else -> "sku"
        }

        val sortOrder = if (rawOrder == "ASC" || rawOrder == "DESC") rawOrder else "ASC"

        val query = "SELECT COUNT(*) as total FROM product WHERE active = true"
        val dataQuery = """
            SELECT product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
            FROM product
            WHERE active = true
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        var total = 0

        return pool.preparedQuery(query)
            .rxExecute()
            .flatMap { countResult ->
                total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery)
                    .rxExecute()
            }
            .map { result ->
                val data = result.map { row ->
                    val metadata = JsonObject(row.getString("metadata"))
                    JsonObject()
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("productType", row.getString("product_type"))
                        .put("baseUom", row.getString("base_uom"))
                        .put("active", row.getBoolean("active"))
                        .put("metadata", metadata)
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

    fun createProduct(
        sku: String,
        name: String,
        productType: String,
        baseUom: String,
        metadata: JsonObject?
    ): Single<JsonObject> {
        val productId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO product (product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, true, $6, NOW(), NOW())
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(
                productId,
                sku,
                name,
                productType,
                baseUom,
                metadata?.encode()
            ))
            .map {
                JsonObject()
                    .put("productId", productId)
                    .put("sku", sku)
                    .put("name", name)
                    .put("productType", productType)
                    .put("baseUom", baseUom)
                    .put("active", true)
                    .put("metadata", metadata ?: JsonObject())
            }
    }

    fun getProduct(productId: String): Single<JsonObject> {
        val query = """
            SELECT product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
            FROM product
            WHERE product_id = $1 AND active = true
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Product not found"))
                } else {
                    val row = result.first()
                    val metadata = JsonObject(row.getString("metadata"))
                    Single.just(JsonObject()
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("productType", row.getString("product_type"))
                        .put("baseUom", row.getString("base_uom"))
                        .put("active", row.getBoolean("active"))
                        .put("metadata", metadata)
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun updateProduct(
        productId: String,
        name: String,
        productType: String,
        metadata: JsonObject?
    ): Single<JsonObject> {
        val query = """
            UPDATE product
            SET name = $1, product_type = $2, metadata = $3, updated_at = NOW()
            WHERE product_id = $4 AND active = true
            RETURNING product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(name, productType, metadata?.encode(), productId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Product not found"))
                } else {
                    val row = result.first()
                    val metadata = JsonObject(row.getString("metadata"))
                    Single.just(JsonObject()
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("productType", row.getString("product_type"))
                        .put("baseUom", row.getString("base_uom"))
                        .put("active", row.getBoolean("active"))
                        .put("metadata", metadata)
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun deleteProduct(productId: String): Single<Unit> {
        val query = """
            UPDATE product
            SET active = false, updated_at = NOW()
            WHERE product_id = $1
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId))
            .flatMapCompletable { Single.just(it).ignoreElement() }
            .toSingle { }
    }

    fun checkSkuExists(sku: String): Single<Boolean> {
        val query = "SELECT COUNT(*) as cnt FROM product WHERE sku = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(sku))
            .map { result ->
                result.first().getInteger("cnt") > 0
            }
    }
}
