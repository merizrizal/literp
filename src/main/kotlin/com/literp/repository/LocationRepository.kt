package com.literp.repository

import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.sqlclient.Pool
import io.vertx.rxjava3.sqlclient.Tuple
import java.util.*

class LocationRepository(pool: Pool) : BaseRepository(pool, LocationRepository::class.java) {

    fun listLocations(
        page: Int,
        size: Int,
        sort: String,
        code: String?,
        name: String?,
        locationType: String?,
        activeOnly: Boolean
    ): Single<JsonObject> {
        val offset = page * size
        val parts = sort.split(",")
        val sortField = parts.getOrNull(0) ?: "code"
        val sortOrder = parts.getOrNull(1)?.uppercase() ?: "ASC"

        var whereClause = "WHERE true"
        var params = mutableListOf<Any?>()

        if (!code.isNullOrEmpty()) {
            whereClause += " AND code ILIKE $${params.size + 1}"
            params.add("%$code%")
        }

        if (!name.isNullOrEmpty()) {
            whereClause += " AND name ILIKE $${params.size + 1}"
            params.add("%$name%")
        }

        if (!locationType.isNullOrEmpty()) {
            whereClause += " AND location_type = $${params.size + 1}"
            params.add(locationType)
        }

        if (activeOnly) {
            whereClause += " AND is_active = true"
        }

        val countQuery = "SELECT COUNT(*) as total FROM location $whereClause"
        val dataQuery = """
            SELECT location_id, code, name, location_type, is_active, address, created_at, updated_at
            FROM location
            $whereClause
            ORDER BY $sortField $sortOrder
            LIMIT $size OFFSET $offset
        """.trimIndent()

        var total = 0

        return pool.preparedQuery(countQuery)
            .rxExecute(Tuple.from(params))
            .flatMap { countResult ->
                total = countResult.first().getInteger("total")
                pool.preparedQuery(dataQuery)
                    .rxExecute(Tuple.from(params))
            }
            .map { result ->
                val data = result.map { row ->
                    val address = row.getJsonObject("address") ?: JsonObject()

                    JsonObject()
                        .put("locationId", row.getString("location_id"))
                        .put("code", row.getString("code"))
                        .put("name", row.getString("name"))
                        .put("locationType", row.getString("location_type"))
                        .put("isActive", row.getBoolean("is_active"))
                        .put("address", address)
                        .put("createdAt", row.getLocalDateTime("created_at").toString())
                        .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                }

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

    fun createLocation(code: String, name: String, locationType: String, address: JsonObject?): Single<JsonObject> {
        val locationId = UUID.randomUUID().toString()
        val query = """
            INSERT INTO location (location_id, code, name, location_type, is_active, address, created_at, updated_at)
            VALUES ($1, $2, $3, $4, true, $5, NOW(), NOW())
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(locationId, code, name, locationType, address?.encode()))
            .map {
                JsonObject()
                    .put("locationId", locationId)
                    .put("code", code)
                    .put("name", name)
                    .put("locationType", locationType)
                    .put("isActive", true)
                    .put("address", address ?: JsonObject())
            }
    }

    fun getLocation(locationId: String): Single<JsonObject> {
        val query = """
            SELECT location_id, code, name, location_type, is_active, address, created_at, updated_at
            FROM location
            WHERE location_id = $1
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(locationId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Location not found"))
                } else {
                    val row = result.first()
                    val address = JsonObject(row.getString("address"))
                    Single.just(
                        JsonObject()
                            .put("locationId", row.getString("location_id"))
                            .put("code", row.getString("code"))
                            .put("name", row.getString("name"))
                            .put("locationType", row.getString("location_type"))
                            .put("isActive", row.getBoolean("is_active"))
                            .put("address", address)
                            .put("createdAt", row.getLocalDateTime("created_at").toString())
                            .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun getLocationByCode(code: String): Single<JsonObject> {
        val query = """
            SELECT location_id, code, name, location_type, is_active, address, created_at, updated_at
            FROM location
            WHERE code = $1
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(code))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Location not found"))
                } else {
                    val row = result.first()
                    val address = JsonObject(row.getString("address"))
                    Single.just(
                        JsonObject()
                            .put("locationId", row.getString("location_id"))
                            .put("code", row.getString("code"))
                            .put("name", row.getString("name"))
                            .put("locationType", row.getString("location_type"))
                            .put("isActive", row.getBoolean("is_active"))
                            .put("address", address)
                            .put("createdAt", row.getLocalDateTime("created_at").toString())
                            .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun updateLocation(
        locationId: String,
        name: String,
        locationType: String,
        address: JsonObject?
    ): Single<JsonObject> {
        val query = """
            UPDATE location
            SET name = $1, location_type = $2, address = $3, updated_at = NOW()
            WHERE location_id = $4
            RETURNING location_id, code, name, location_type, is_active, address, created_at, updated_at
        """.trimIndent()

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(name, locationType, address?.encode(), locationId))
            .flatMap { result ->
                if (result.size() == 0) {
                    Single.error(Exception("Location not found"))
                } else {
                    val row = result.first()
                    val address = JsonObject(row.getString("address"))
                    Single.just(
                        JsonObject()
                            .put("locationId", row.getString("location_id"))
                            .put("code", row.getString("code"))
                            .put("name", row.getString("name"))
                            .put("locationType", row.getString("location_type"))
                            .put("isActive", row.getBoolean("is_active"))
                            .put("address", address)
                            .put("createdAt", row.getLocalDateTime("created_at").toString())
                            .put("updatedAt", row.getLocalDateTime("updated_at").toString())
                    )
                }
            }
    }

    fun deleteLocation(locationId: String): Single<Unit> {
        val query = "DELETE FROM location WHERE location_id = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(locationId))
            .flatMapCompletable { Single.just(it).ignoreElement() }
            .toSingle { }
    }

    fun checkCodeExists(code: String): Single<Boolean> {
        val query = "SELECT COUNT(*) as cnt FROM location WHERE code = $1"

        return pool.preparedQuery(query)
            .rxExecute(Tuple.of(code))
            .map { result ->
                result.first().getInteger("cnt") > 0
            }
    }
}
