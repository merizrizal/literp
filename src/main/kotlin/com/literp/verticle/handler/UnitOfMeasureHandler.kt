package com.literp.verticle.handler

import com.literp.service.UnitOfMeasureService
import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class UnitOfMeasureHandler(private val uomService: UnitOfMeasureService) : BaseHandler(UnitOfMeasureHandler::class.java) {

    fun listUnitOfMeasures(context: RoutingContext) {
        val page = context.queryParam("page").firstOrNull()?.toIntOrNull() ?: 0
        val size = context.queryParam("size").firstOrNull()?.toIntOrNull() ?: 20
        val sort = context.queryParam("sort").firstOrNull() ?: "code,asc"

        uomService.listUnitOfMeasures(page, size, sort)
            .onSuccess { result -> putResponse(context, 200, result) }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to list UOM: ${error.message}") }
    }

    fun createUnitOfMeasure(context: RoutingContext) {
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val code = body.getString("code")
        val name = body.getString("name")
        val baseUnit = body.getString("baseUnit")

        if (code.isNullOrEmpty() || name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "Code and name are required")
            return
        }

        uomService.checkCodeExists(code)
            .compose { exists ->
                if (exists) {
                    Future.failedFuture(Exception("UOM code already exists"))
                } else {
                    uomService.createUnitOfMeasure(code, name, baseUnit)
                }
            }
            .onSuccess { result -> putResponse(context, 201, JsonObject().put("data", result)) }
            .onFailure { error ->
                if (error.message?.contains("already exists") == true) {
                    putErrorResponse(context, 409, error.message ?: "Conflict")
                } else {
                    putErrorResponse(context, 500, "Failed to create UOM: ${error.message}")
                }
            }
    }

    fun getUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")

        uomService.getUnitOfMeasure(uomId)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Unit of measure not found") }
    }

    fun updateUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")
        val validatedRequest: ValidatedRequest = context.get(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        val body = validatedRequest.body.jsonObject
        val name = body.getString("name")
        val baseUnit = body.getString("baseUnit")

        if (name.isNullOrEmpty()) {
            putErrorResponse(context, 400, "Name is required")
            return
        }

        uomService.updateUnitOfMeasure(uomId, name, baseUnit)
            .onSuccess { result -> putResponse(context, 200, JsonObject().put("data", result)) }
            .onFailure { _ -> putErrorResponse(context, 404, "Unit of measure not found") }
    }

    fun deleteUnitOfMeasure(context: RoutingContext) {
        val uomId = context.pathParam("uomId")

        uomService.deleteUnitOfMeasure(uomId)
            .onSuccess { context.response().setStatusCode(204).end() }
            .onFailure { error -> putErrorResponse(context, 500, "Failed to delete UOM: ${error.message}") }
    }

}
