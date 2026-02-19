package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.*

class UnitOfMeasureRepository(pool: Pool) : BaseRepository(pool, UnitOfMeasureRepository::class.java) {

    fun listUnitOfMeasures(page: Int, size: Int, sort: String): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val sortField = parts.getOrNull(0) ?: "code"
        val sortOrder = parts.getOrNull(1)?.uppercase() ?: "ASC"

        val query = "SELECT COUNT(*) as total FROM unit_of_measure WHERE true"
        val dataQuery = """
            SELECT uom_id, code, name, base_unit, created_at, updated_at
            FROM unit_of_measure
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
                    JsonObject()
                        .put("uomId", row.getString("uom_id"))
                        .put("code", row.getString("code"))
                        .put("name", row.getString("name"))
                        .put("baseUnit", row.getString("base_unit"))
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

    fun createUnitOfMeasure(code: String, name: String, baseUnit: String?): Single<JsonObject> {
        val uomId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO unit_of_measure (uom_id, code, name, base_unit, created_at, updated_at)
            VALUES ($1, $2, $3, $4, NOW(), NOW())
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(uomId, code, name, baseUnit))
            .map {
                JsonObject()
                    .put("uomId", uomId)
                    .put("code", code)
                    .put("name", name)
                    .put("baseUnit", baseUnit)
            }
    }

    fun getUnitOfMeasure(uomId: String): Single<JsonObject> {
        val query = """
            SELECT uom_id, code, name, base_unit, created_at, updated_at
            FROM unit_of_measure
            WHERE uom_id = $1
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(uomId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Unit of measure not found"))
                } else {
                    val row = result.first()
                    Single.just(JsonObject()
                        .put("uomId", row.getString("uom_id"))
                        .put("code", row.getString("code"))
                        .put("name", row.getString("name"))
                        .put("baseUnit", row.getString("base_unit"))
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun updateUnitOfMeasure(uomId: String, name: String, baseUnit: String?): Single<JsonObject> {
        val query = """
            UPDATE unit_of_measure
            SET name = $1, base_unit = $2, updated_at = NOW()
            WHERE uom_id = $3
            RETURNING uom_id, code, name, base_unit, created_at, updated_at
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(name, baseUnit, uomId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Unit of measure not found"))
                } else {
                    val row = result.first()
                    Single.just(JsonObject()
                        .put("uomId", row.getString("uom_id"))
                        .put("code", row.getString("code"))
                        .put("name", row.getString("name"))
                        .put("baseUnit", row.getString("base_unit"))
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun deleteUnitOfMeasure(uomId: String): Single<Unit> {
        val query = "DELETE FROM unit_of_measure WHERE uom_id = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(uomId))
            .flatMapCompletable { Single.just(it).ignoreElement() }
            .toSingle { }
    }

    fun checkCodeExists(code: String): Single<Boolean> {
        val query = "SELECT COUNT(*) as cnt FROM unit_of_measure WHERE code = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(code))
            .map { result ->
                result.first().getInteger("cnt") > 0
            }
    }
}
