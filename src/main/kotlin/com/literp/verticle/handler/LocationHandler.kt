package com.literp.verticle.handler

import com.literp.repository.LocationRepository
import io.reactivex.rxjava3.core.Single
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder


class LocationHandler(private val locationRepository: LocationRepository) : BaseHandler(LocationHandler::class.java) {

    fun listLocations(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "code,asc"
        val code = context.queryParam("code").firstOrNull()
        val name = context.queryParam("name").firstOrNull()
        val locationType = context.queryParam("locationType").firstOrNull()
        val activeOnly = context.queryParam("activeOnly").firstOrNull()?.toBoolean() ?: true

        locationRepository.listLocations(page, size, sort, code, name, locationType, activeOnly)
            .subscribe(
                { result -> putResponse(context, 200, result) },
                { error -> putErrorResponse(context, 500, "Failed to list locations: ${error.message}") }
            )
    }

    fun createLocation(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val code = body.getString("code")
        val name = body.getString("name")
        val locationType = body.getString("locationType")
        val address = body.getJsonObject("address")

        if (code.isNullOrEmpty() || name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "code, name, and locationType are required")
            return
        }

        locationRepository.checkCodeExists(code)
            .flatMap { exists ->
                if (exists) Single.error(Exception("Location code already exists"))
                else locationRepository.createLocation(code, name, locationType, address)
            }
            .subscribe(
                { result -> putResponse(context, 201, JsonObject().put("data", result)) },
                { error ->
                    if (error.message?.contains("already exists") == true) {
                        putErrorResponse(context, 409, error.message ?: "Conflict")
                    } else {
                        putErrorResponse(context, 500, "Failed to create location: ${error.message}")
                    }
                }
            )
    }

    fun getLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationRepository.getLocation(locationId)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    fun getLocationByCode(context: RoutingContext) {
        val code = context.pathParam("code")

        locationRepository.getLocationByCode(code)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    fun updateLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val name = body.getString("name")
        val locationType = body.getString("locationType")
        val address = body.getJsonObject("address")

        if (name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and locationType are required")
            return
        }

        locationRepository.updateLocation(locationId, name, locationType, address)
            .subscribe(
                { result -> putResponse(context, 200, JsonObject().put("data", result)) },
                { _ -> putErrorResponse(context, 404, "Location not found") }
            )
    }

    fun deleteLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationRepository.deleteLocation(locationId)
            .subscribe(
                { context.response().setStatusCode(204).end() },
                { error -> putErrorResponse(context, 500, "Failed to delete location: ${error.message}") }
            )
    }

}
