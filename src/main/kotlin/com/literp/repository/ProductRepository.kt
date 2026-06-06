package com.literp.repository

import com.literp.common.ErrorCodes
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.*

class ProductRepository(pool: Pool) : BaseRepository(pool, ProductRepository::class.java) {

    fun listProducts(
        page: Int,
        size: Int,
        sort: String,
        sku: String?,
        productType: String?,
        activeOnly: Boolean
    ): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val rawField = parts.getOrNull(0)?.trim() ?: "sku"
        val rawOrder = parts.getOrNull(1)?.trim()?.uppercase() ?: "ASC"

        val sortField = when (rawField.lowercase()) {
            "sku" -> "sku"
            "name" -> "name"
            "producttype", "product_type" -> "product_type"
            "baseuom", "base_uom" -> "base_uom"
            "active" -> "active"
            "createdat", "created_at" -> "created_at"
            "updatedat", "updated_at" -> "updated_at"
            else -> "sku"
        }

        val sortOrder = if (rawOrder == "ASC" || rawOrder == "DESC") rawOrder else "ASC"

        var whereClause = "WHERE true"
        val params = mutableListOf<Any?>()

        if (!sku.isNullOrEmpty()) {
            whereClause += " AND sku ILIKE $${params.size + 1}"
            params.add("%$sku%")
        }

        if (!productType.isNullOrEmpty()) {
            whereClause += " AND product_type = $${params.size + 1}"
            params.add(productType)
        }

        if (activeOnly) {
            whereClause += " AND active = true"
        }

        val query = "SELECT COUNT(*) as total FROM product $whereClause"
        val dataQuery = """
            SELECT product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
            FROM product
            $whereClause
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        var total = 0

        return pool.preparedQuery(query)
            .rxExecute(Tuple.from(params))
            .flatMap { countResult ->
                total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery)
                    .rxExecute(Tuple.from(params))
            }
            .map { result ->
                val data = result.map { row ->
                    val metadata = jsonObjectOrEmpty(row.getString("metadata"))
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
        active: Boolean,
        metadata: JsonObject?
    ): Single<JsonObject> {
        val productId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO product (product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6, $7, NOW(), NOW())
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(
                Tuple.tuple()
                    .addString(productId)
                    .addString(sku)
                    .addString(name)
                    .addString(productType)
                    .addString(baseUom)
                    .addBoolean(active)
                    .addValue(metadata?.encode())
            )
            .map {
                JsonObject()
                    .put("productId", productId)
                    .put("sku", sku)
                    .put("name", name)
                    .put("productType", productType)
                    .put("baseUom", baseUom)
                    .put("active", active)
                    .put("metadata", metadata ?: JsonObject())
            }
            .onErrorResumeNext { error ->
                if (isForeignKeyViolation(error)) {
                    Single.error(Exception("baseUom must be an existing unit of measure"))
                } else {
                    Single.error(error)
                }
            }
    }

    fun getProduct(productId: String, includeVariants: Boolean): Single<JsonObject> {
        val query = """
            SELECT product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
            FROM product
            WHERE product_id = $1 AND active = true
        """.trimIndent()

        val variantsQuery = """
            SELECT variant_id, product_id, sku, name, attributes, active, created_at, updated_at
            FROM product_variant
            WHERE product_id = $1 AND active = true
            ORDER BY sku ASC
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    val row = result.first()
                    val metadata = jsonObjectOrEmpty(row.getString("metadata"))
                    val product = JsonObject()
                        .put("productId", row.getString("product_id"))
                        .put("sku", row.getString("sku"))
                        .put("name", row.getString("name"))
                        .put("productType", row.getString("product_type"))
                        .put("baseUom", row.getString("base_uom"))
                        .put("active", row.getBoolean("active"))
                        .put("metadata", metadata)
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())

                    if (!includeVariants) {
                        Single.just(product)
                    } else {
                        pool.preparedQuery(variantsQuery)
                            .rxExecute(Tuple.of(productId))
                            .map { variantsResult ->
                                val variants = variantsResult.map { variantRow ->
                                    val attributes = jsonObjectOrEmpty(variantRow.getString("attributes"))
                                    JsonObject()
                                        .put("variantId", variantRow.getString("variant_id"))
                                        .put("productId", variantRow.getString("product_id"))
                                        .put("sku", variantRow.getString("sku"))
                                        .put("name", variantRow.getString("name"))
                                        .put("attributes", attributes)
                                        .put("active", variantRow.getBoolean("active"))
                                        .put("createdAt", variantRow.getLocalDateTime("created_at").toString())
                                        .put("updatedAt", variantRow.getLocalDateTime("updated_at").toString())
                                }

                                product.put("variants", variants)
                            }
                    }
                }
            }
    }

    fun updateProduct(
        productId: String,
        name: String,
        productType: String,
        baseUom: String?,
        active: Boolean?,
        metadata: JsonObject?
    ): Single<JsonObject> {
        val query = """
            UPDATE product
            SET name = $1, product_type = $2, base_uom = COALESCE($3, base_uom), active = COALESCE($4, active), metadata = $5, updated_at = NOW()
            WHERE product_id = $6 AND active = true
            RETURNING product_id, sku, name, product_type, base_uom, active, metadata, created_at, updated_at
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(name, productType, baseUom, active, metadata?.encode(), productId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    val row = result.first()
                    val metadata = jsonObjectOrEmpty(row.getString("metadata"))
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
            .onErrorResumeNext { error ->
                if (isForeignKeyViolation(error)) {
                    Single.error(Exception("baseUom must be an existing unit of measure"))
                } else {
                    Single.error(error)
                }
            }
    }

    fun deleteProduct(productId: String): Single<Unit> {
        val query = """
            UPDATE product
            SET active = false, updated_at = NOW()
            WHERE product_id = $1 AND active = true
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(productId))
            .flatMap { result ->
                if (result.rowCount() == 0) {
                    Single.error(Exception(ErrorCodes.fromStatus(404)))
                } else {
                    Single.just(Unit)
                }
            }
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
