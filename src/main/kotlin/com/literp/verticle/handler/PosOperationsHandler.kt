package com.literp.verticle.handler

import com.literp.service.pos.PosOperationsService
import io.vertx.core.json.JsonArray
import io.vertx.rxjava3.ext.web.RoutingContext

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
        respondNotImplemented(context)
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
        respondNotImplemented(context)
    }

    fun deactivatePosTerminal(context: RoutingContext) {
        respondNotImplemented(context)
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

    private fun authorizedLocationIds(context: RoutingContext): Set<String>? {
        val authorizedLocationIds = context.get<Set<String>>(POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY)
        if (authorizedLocationIds.isNullOrEmpty()) {
            putErrorResponse(context, 403, "Forbidden", SecurityFailureCodes.FORBIDDEN)
            return null
        }
        return authorizedLocationIds
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
    }
}
