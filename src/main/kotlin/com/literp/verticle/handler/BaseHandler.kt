package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.ext.web.RoutingContext
import java.util.UUID

open class BaseHandler(clazz: Class<*>) {

    protected val logger = LoggerFactory.getLogger(clazz)

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-ID"
        const val REQUEST_ID_CONTEXT_KEY = "requestId"
    }

    protected data class ListQueryParams(val page: Int, val size: Int, val sort: String)

    protected fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        val requestId = resolveRequestId(context)
        context.response().statusCode = statusCode
        context.response().putHeader("Content-Type", "application/json")
        context.response().putHeader(REQUEST_ID_HEADER, requestId)
        context.response().end(response.encode())
    }

    private fun resolveRequestId(context: RoutingContext): String {
        val existingRequestId = context.get<String>(REQUEST_ID_CONTEXT_KEY)
        if (!existingRequestId.isNullOrBlank()) {
            return existingRequestId
        }

        val requestId = context.request()?.getHeader(REQUEST_ID_HEADER)?.trim().orEmpty().ifBlank {
            UUID.randomUUID().toString()
        }
        context.put(REQUEST_ID_CONTEXT_KEY, requestId)
        return requestId
    }

    protected fun putSuccessResponse(context: RoutingContext, statusCode: Int, data: JsonObject) {
        logSuccess(context, statusCode)
        putResponse(context, statusCode, JsonObject().put("data", data))
    }

    protected fun putSuccessEnvelopeResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        logSuccess(context, statusCode)
        putResponse(context, statusCode, response)
    }

    private fun logSuccess(context: RoutingContext, statusCode: Int) {
        val id = UUID.randomUUID().toString()
        val method = context.request()?.method()?.name() ?: "-"
        val path = context.request()?.path() ?: "-"
        val requestId = resolveRequestId(context)
        logger.info("Handling id=$id status=$statusCode method=$method path=$path requestId=$requestId")
    }

    protected fun putErrorResponse(
        context: RoutingContext,
        statusCode: Int,
        message: String?,
        errorCode: String = ErrorCodes.fromStatus(statusCode)
    ) {
        val errorId = UUID.randomUUID().toString()
        val method = context.request()?.method()?.name() ?: "-"
        val path = context.request()?.path() ?: "-"
        val requestId = resolveRequestId(context)
        logger.warn("Handling errorId=$errorId errorCode=$errorCode status=$statusCode method=$method path=$path requestId=$requestId message=${message ?: "-"}")

        val errorResponse = JsonObject()
            .put("error", message)
            .put("errorCode", errorCode)
            .put("status", statusCode)
            .put("errorId", errorId)

        putResponse(context, statusCode, errorResponse)
    }

    protected fun putErrorResponse(
        context: RoutingContext,
        statusCode: Int,
        message: String?,
        throwable: Throwable,
        errorCode: String = ErrorCodes.fromStatus(statusCode)
    ) {
        val errorId = UUID.randomUUID().toString()
        val method = context.request()?.method()?.name() ?: "-"
        val path = context.request()?.path() ?: "-"
        val requestId = resolveRequestId(context)
        logger.error("Unhandled exception errorId=$errorId errorCode=$errorCode status=$statusCode method=$method path=$path requestId=$requestId message=${message ?: "-"}", throwable)

        val resolvedMessage = message ?: throwable.message ?: "Internal server error"
        val errorResponse = JsonObject()
            .put("error", resolvedMessage)
            .put("errorCode", errorCode)
            .put("status", statusCode)
            .put("errorId", errorId)

        putResponse(context, statusCode, errorResponse)
    }

    protected fun isNotFoundError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.equals(ErrorCodes.fromStatus(404), ignoreCase = true)
                || message.equals(ErrorCodes.RESOURCE_NOT_FOUND, ignoreCase = true)
                || message.contains("not found", ignoreCase = true)
    }

    protected fun isConflictError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.equals(ErrorCodes.fromStatus(409), ignoreCase = true)
                || message.equals(ErrorCodes.CONFLICT, ignoreCase = true)
                || message.contains("already exists", ignoreCase = true)
                || message.contains("conflict", ignoreCase = true)
                || message.contains("referenced", ignoreCase = true)
                || message.contains("cannot cancel", ignoreCase = true)
                || message.contains("only be captured", ignoreCase = true)
                || message.contains("only confirmed", ignoreCase = true)
                || message.contains("draft", ignoreCase = true)
                || message.contains("insufficient captured payment", ignoreCase = true)
                || message.contains("insufficient available stock", ignoreCase = true)
    }

    protected fun isValidationError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.equals(ErrorCodes.fromStatus(400), ignoreCase = true)
                || message.equals(ErrorCodes.VALIDATION_ERROR, ignoreCase = true)
                || message.contains("required", ignoreCase = true)
                || message.contains("must be", ignoreCase = true)
                || message.contains("without lines", ignoreCase = true)
                || message.contains("no fulfillable", ignoreCase = true)
    }

    protected fun putMappedErrorResponse(
        context: RoutingContext,
        error: Throwable,
        internalErrorMessage: String,
        notFoundMessage: String = "Not found",
        validationMessage: String? = null,
        conflictMessage: String? = null
    ) {
        when {
            isNotFoundError(error.message) -> putErrorResponse(context, 404, notFoundMessage)
            isValidationError(error.message) -> putErrorResponse(context, 400, validationMessage ?: error.message ?: "Bad request")
            isConflictError(error.message) -> putErrorResponse(context, 409, conflictMessage ?: error.message ?: "Conflict")
            else -> putErrorResponse(context, 500, "$internalErrorMessage: ${error.message}", error)
        }
    }

    protected fun parseListQuery(
        context: RoutingContext,
        defaultSort: String,
        allowedSortFields: Set<String>
    ): ListQueryParams? {
        val page = parseBoundedIntQueryParam(context, "page", 0, 0, null) ?: return null
        val size = parseBoundedIntQueryParam(context, "size", 20, 1, 100) ?: return null
        val sort = parseSortQueryParam(context, defaultSort, allowedSortFields) ?: return null

        return ListQueryParams(page, size, sort)
    }

    protected fun parseBooleanQueryParam(
        context: RoutingContext,
        name: String,
        defaultValue: Boolean
    ): Boolean? {
        val rawValue = context.queryParam(name).firstOrNull()?.trim() ?: return defaultValue
        return when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> {
                putErrorResponse(context, 400, "$name must be true or false")
                null
            }
        }
    }

    private fun parseBoundedIntQueryParam(
        context: RoutingContext,
        name: String,
        defaultValue: Int,
        min: Int,
        max: Int?
    ): Int? {
        val rawValue = context.queryParam(name).firstOrNull()?.trim() ?: return defaultValue
        val parsed = rawValue.toIntOrNull()

        if (parsed == null) {
            putErrorResponse(context, 400, "$name must be an integer")
            return null
        }

        if (parsed < min) {
            putErrorResponse(context, 400, "$name must be greater than or equal to $min")
            return null
        }

        if (max != null && parsed > max) {
            putErrorResponse(context, 400, "$name must be less than or equal to $max")
            return null
        }

        return parsed
    }

    private fun parseSortQueryParam(
        context: RoutingContext,
        defaultSort: String,
        allowedSortFields: Set<String>
    ): String? {
        val rawSort = context.queryParam("sort").firstOrNull()?.trim() ?: defaultSort
        val parts = rawSort.split(",")

        if (parts.size != 2) {
            putErrorResponse(context, 400, "sort must use the format field,asc or field,desc")
            return null
        }

        val sortField = parts[0].trim()
        val sortDirection = parts.getOrNull(1)?.trim()?.lowercase() ?: "asc"
        val allowedFields = allowedSortFields.map { it.lowercase() }.toSet()

        if (sortField.isBlank() || sortField.lowercase() !in allowedFields) {
            putErrorResponse(context, 400, "sort field must be one of: ${allowedSortFields.sorted().joinToString(", ")}")
            return null
        }

        if (sortDirection != "asc" && sortDirection != "desc") {
            putErrorResponse(context, 400, "sort direction must be asc or desc")
            return null
        }

        return "$sortField,$sortDirection"
    }
}
