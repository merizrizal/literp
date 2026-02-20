package com.literp.service.impl

import com.literp.repository.LocationRepository
import com.literp.service.LocationService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject

class LocationServiceImpl(
    private val repository: LocationRepository
) : LocationService {

    override fun listLocations(
        page: Int,
        size: Int,
        sort: String,
        code: String?,
        name: String?,
        locationType: String?,
        activeOnly: Boolean
    ): Future<JsonObject> {
        return repository.listLocations(page, size, sort, code, name, locationType, activeOnly).toVertxFuture()
    }

    override fun createLocation(code: String, name: String, locationType: String, address: JsonObject?): Future<JsonObject> {
        return repository.createLocation(code, name, locationType, address).toVertxFuture()
    }

    override fun getLocation(locationId: String): Future<JsonObject> {
        return repository.getLocation(locationId).toVertxFuture()
    }

    override fun getLocationByCode(code: String): Future<JsonObject> {
        return repository.getLocationByCode(code).toVertxFuture()
    }

    override fun updateLocation(
        locationId: String,
        name: String,
        locationType: String,
        address: JsonObject?
    ): Future<JsonObject> {
        return repository.updateLocation(locationId, name, locationType, address).toVertxFuture()
    }

    override fun deleteLocation(locationId: String): Future<Void> {
        return repository.deleteLocation(locationId).toVertxVoidFuture()
    }

    override fun checkCodeExists(code: String): Future<Boolean> {
        return repository.checkCodeExists(code).toVertxFuture()
    }
}
