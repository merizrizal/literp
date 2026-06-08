package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import com.literp.service.master.LocationService
import io.vertx.core.Future
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class LocationHandler(private val locationService: LocationService) : BaseHandler(LocationHandler::class.java) {

    fun listLocations(context: RoutingContext) {
        val query = parseListQuery(context, "code,asc", SORT_FIELDS) ?: return
        val code = context.queryParam("code").firstOrNull()
        val name = context.queryParam("name").firstOrNull()
        val locationType = context.queryParam("locationType").firstOrNull()
        val activeOnly = parseBooleanQueryParam(context, "activeOnly", true) ?: return

        locationService.listLocations(query.page, query.size, query.sort, code, name, locationType, activeOnly)
            .onSuccess { result -> putSuccessEnvelopeResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list locations: ${error.message}", error) }
    }

    fun createLocation(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val code = body?.getString("code")
        val name = body?.getString("name")
        val locationType = body?.getString("locationType")
        val isActive = body?.getBoolean("isActive") ?: true
        val address = body?.getJsonObject("address")

        if (code.isNullOrEmpty() || name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "code, name, and locationType are required")
            return
        }

        locationService.checkCodeExists(code)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception(ErrorCodes.fromStatus(409)))
                } else {
                    locationService.createLocation(code, name, locationType, isActive, address)
                }
            }
            .onSuccess { result -> putSuccessResponse(context, 201, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to create location",
                    notFoundMessage = "Location not found",
                    conflictMessage = "Location code already exists"
                )
            }
    }

    fun getLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationService.getLocation(locationId)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get location",
                    notFoundMessage = "Location not found"
                )
            }
    }

    fun getLocationByCode(context: RoutingContext) {
        val code = context.pathParam("code")

        locationService.getLocationByCode(code)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get location by code",
                    notFoundMessage = "Location by code not found"
                )
            }
    }

    fun updateLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val name = body?.getString("name")
        val locationType = body?.getString("locationType")
        val isActive = body?.getBoolean("isActive")
        val address = body?.getJsonObject("address")

        if (name.isNullOrEmpty() || locationType.isNullOrEmpty()) {
            putErrorResponse(context, 400, "name and locationType are required")
            return
        }

        locationService.updateLocation(locationId, name, locationType, isActive, address)
            .onSuccess { result -> putSuccessResponse(context, 200, result) }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to update location",
                    notFoundMessage = "Location not found"
                )
            }
    }

    fun deleteLocation(context: RoutingContext) {
        val locationId = context.pathParam("locationId")

        locationService.deleteLocation(locationId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to delete location",
                    notFoundMessage = "Location not found",
                    conflictMessage = "Location is referenced and cannot be deleted"
                )
            }
    }

    private companion object {
        private val SORT_FIELDS = setOf(
            "code",
            "name",
            "locationType",
            "location_type",
            "isActive",
            "is_active",
            "createdAt",
            "created_at",
            "updatedAt",
            "updated_at"
        )
    }
}
