package com.literp.verticle.handler

import com.literp.security.AuthenticatedPrincipal
import com.literp.service.pos.PosOperationsService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder

class PosOperationsHandler(
    private val posOperationsService: PosOperationsService
) : BaseHandler(PosOperationsHandler::class.java) {

    fun listPosTerminals(context: RoutingContext) {
        val query = parseListQuery(context, "terminalCode,asc", TERMINAL_SORT_FIELDS) ?: return
        val locationId = context.queryParam("locationId").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val isActive = when (context.queryParam("isActive").firstOrNull()?.trim()) {
            null -> null
            "true" -> true
            "false" -> false
            else -> {
                putErrorResponse(context, 400, "isActive must be true or false")
                return
            }
        }
        val authorizedLocationIds = authorizedLocationIds(context) ?: return

        posOperationsService.listPosTerminals(
            query.page,
            query.size,
            query.sort,
            locationId,
            isActive,
            JsonArray(authorizedLocationIds.toList())
        ).onSuccess { result ->
            putSuccessEnvelopeResponse(context, 200, result)
        }.onFailure { error ->
            putMappedErrorResponse(
                context = context,
                error = error,
                internalErrorMessage = "Failed to list POS terminals"
            )
        }
    }

    fun createPosTerminal(context: RoutingContext) {
        val body = requestBody(context)
        if (hasUnsupportedFields(context, body, CREATE_TERMINAL_FIELDS)) return

        val locationId = bodyString(body, "locationId")
        val terminalCode = bodyString(body, "terminalCode")
        val deviceName = bodyString(body, "deviceName")
        val idempotencyKey = context.request().getHeader("Idempotency-Key")?.trim()

        if (locationId.isNullOrBlank() || terminalCode.isNullOrBlank() || deviceName.isNullOrBlank()) {
            putErrorResponse(context, 400, "locationId, terminalCode, and deviceName are required")
            return
        }
        if (idempotencyKey.isNullOrBlank()) {
            putErrorResponse(context, 400, "Idempotency-Key is required")
            return
        }

        val authorizedLocationIds = authorizedLocationIds(context) ?: return
        val principal = authenticatedPrincipal(context) ?: return

        posOperationsService.createPosTerminal(
            locationId,
            terminalCode,
            deviceName,
            idempotencyKey,
            principal.subject,
            principal.organizationId,
            JsonArray(authorizedLocationIds.toList())
        ).onSuccess { result ->
            putSuccessResponse(context, 201, result)
        }.onFailure { error ->
            putMappedErrorResponse(
                context = context,
                error = error,
                internalErrorMessage = "Failed to create POS terminal",
                notFoundMessage = "POS location not found"
            )
        }
    }

    fun getPosTerminal(context: RoutingContext) {
        val terminalId = context.pathParam("terminalId")?.trim()
        if (terminalId.isNullOrBlank()) {
            putErrorResponse(context, 400, "terminalId is required")
            return
        }
        val authorizedLocationIds = authorizedLocationIds(context) ?: return

        posOperationsService.getPosTerminal(terminalId, JsonArray(authorizedLocationIds.toList()))
            .onSuccess { result ->
                putSuccessResponse(context, 200, result)
            }.onFailure { error ->
                putMappedErrorResponse(
                    context = context,
                    error = error,
                    internalErrorMessage = "Failed to get POS terminal",
                    notFoundMessage = "POS terminal not found"
                )
            }
    }

    fun updatePosTerminal(context: RoutingContext) {
        val terminalId = context.pathParam("terminalId")?.trim()
        if (terminalId.isNullOrBlank()) {
            putErrorResponse(context, 400, "terminalId is required")
            return
        }

        val body = requestBody(context)
        if (hasUnsupportedFields(context, body, UPDATE_TERMINAL_FIELDS)) return
        val terminalCode = bodyString(body, "terminalCode")
        val deviceName = bodyString(body, "deviceName")
        if (body == null || body.fieldNames().isEmpty()) {
            putErrorResponse(context, 400, "terminalCode or deviceName is required")
            return
        }
        if ((body.containsKey("terminalCode") && terminalCode.isNullOrBlank()) ||
            (body.containsKey("deviceName") && deviceName.isNullOrBlank())
        ) {
            putErrorResponse(context, 400, "terminalCode and deviceName must be nonblank strings")
            return
        }

        val authorizedLocationIds = authorizedLocationIds(context) ?: return
        posOperationsService.updatePosTerminal(
            terminalId,
            terminalCode,
            deviceName,
            JsonArray(authorizedLocationIds.toList())
        ).onSuccess { result ->
            putSuccessResponse(context, 200, result)
        }.onFailure { error ->
            putMappedErrorResponse(
                context = context,
                error = error,
                internalErrorMessage = "Failed to update POS terminal",
                notFoundMessage = "POS terminal not found"
            )
        }
    }

    fun deactivatePosTerminal(context: RoutingContext) {
        val terminalId = context.pathParam("terminalId")?.trim()
        if (terminalId.isNullOrBlank()) {
            putErrorResponse(context, 400, "terminalId is required")
            return
        }

        val authorizedLocationIds = authorizedLocationIds(context) ?: return
        posOperationsService.deactivatePosTerminal(
            terminalId,
            JsonArray(authorizedLocationIds.toList())
        ).onSuccess { result ->
            putSuccessResponse(context, 200, result)
        }.onFailure { error ->
            putMappedErrorResponse(
                context = context,
                error = error,
                internalErrorMessage = "Failed to deactivate POS terminal",
                notFoundMessage = "POS terminal not found"
            )
        }
    }

    fun openPosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun getCurrentPosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun closePosShift(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun getPosReceiptByNumber(context: RoutingContext) {
        respondNotImplemented(context)
    }

    fun listPosReceiptsBySalesOrder(context: RoutingContext) {
        respondNotImplemented(context)
    }

    private fun requestBody(context: RoutingContext): JsonObject? {
        val validatedRequest = context.get<ValidatedRequest>(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        return validatedRequest?.body?.jsonObject
            ?: runCatching { context.body().asJsonObject() }.getOrNull()
    }

    private fun bodyString(body: JsonObject?, fieldName: String): String? =
        body?.getValue(fieldName) as? String

    private fun hasUnsupportedFields(
        context: RoutingContext,
        body: JsonObject?,
        allowedFields: Set<String>
    ): Boolean {
        val hasUnsupportedField = body?.fieldNames()?.any { it !in allowedFields } == true
        if (hasUnsupportedField) {
            putErrorResponse(context, 400, "Unsupported POS terminal field")
            return true
        }
        return false
    }

    private fun authorizedLocationIds(context: RoutingContext): Set<String>? {
        val authorizedLocationIds = context.get<Set<String>>(POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY)
        if (authorizedLocationIds.isNullOrEmpty()) {
            putErrorResponse(context, 403, "Forbidden", SecurityFailureCodes.FORBIDDEN)
            return null
        }
        return authorizedLocationIds
    }

    private fun authenticatedPrincipal(context: RoutingContext): AuthenticatedPrincipal? {
        val principal = context.authenticatedPrincipal()
        if (principal == null) {
            context.response().putHeader("WWW-Authenticate", "Bearer")
            putErrorResponse(context, 401, "Authentication required", SecurityFailureCodes.UNAUTHENTICATED)
            return null
        }
        return principal
    }

    private fun respondNotImplemented(context: RoutingContext) {
        putErrorResponse(
            context = context,
            statusCode = 501,
            message = "POS operation is not implemented",
            errorCode = NOT_IMPLEMENTED_ERROR_CODE
        )
    }

    private companion object {
        const val NOT_IMPLEMENTED_ERROR_CODE = "NOT_IMPLEMENTED"
        val TERMINAL_SORT_FIELDS = setOf("terminalCode", "createdAt")
        val CREATE_TERMINAL_FIELDS = setOf("locationId", "terminalCode", "deviceName")
        val UPDATE_TERMINAL_FIELDS = setOf("terminalCode", "deviceName")
    }
}
