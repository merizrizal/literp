package com.literp.verticle.handler

import com.literp.service.LocationService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder


class LocationHandler(private val locationService: LocationService) : BaseHandler(LocationHandler::class.java) {

    fun listLocations(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "code,asc"
        val code = context.queryParam("code").firstOrNull()
        val name = context.queryParam("name").firstOrNull()
        val locationType = context.queryParam("locationType").firstOrNull()
        val activeOnly = context.queryParam("activeOnly").firstOrNull()?.toBoolean() ?: true

        locationService.listLocations(page, size, sort, code, name, locationType, activeOnly)
            .onSuccess { result -> putResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list locations: ${error.message}") }
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

        locationService.checkCodeExists(code)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception("Location code already exists"))
                } else {
                    locationService.createLocation(code, name, locationType, address)
                }
            }
            .onSuccess { result -> putResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                if (error.message?.contains("already exists") == true) {
                    putErrorResponse(context, 409, error.message ?: "Conflict")
                } else {
                    putErrorResponse(context, 500, "Failed to create location: ${error.message}")
                }
            }
    }

    fun getLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationService.getLocation(locationId)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Location not found") }
    }

    fun getLocationByCode(context: RoutingContext) {
        val code = context.pathParam("code")

        locationService.getLocationByCode(code)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Location not found") }
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

        locationService.updateLocation(locationId, name, locationType, address)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Location not found") }
    }

    fun deleteLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationService.deleteLocation(locationId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to delete location: ${error.message}") }
    }

}
