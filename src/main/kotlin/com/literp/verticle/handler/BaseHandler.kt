package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import io.vertx.core.internal.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.ext.web.RoutingContext
import java.util.*

open class BaseHandler(clazz: Class<*>) {

    protected val logger = LoggerFactory.getLogger(clazz)

    protected fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        context.response().statusCode = statusCode
        context.response().putHeader("Content-Type", "application/json")
        context.response().end(response.encode())
    }

    protected fun putSuccessResponse(context: RoutingContext, statusCode: Int, data: JsonObject) {
        val id = UUID.randomUUID().toString()
        val method = context.request()?.method()?.name() ?: "-"
        val path = context.request()?.path() ?: "-"
        val requestId = context.request()?.getHeader("X-Request-ID") ?: "-"
        logger.info("Handling id=$id status=$statusCode method=$method path=$path requestId=$requestId")

        putResponse(context, statusCode, JsonObject().put("data", data))
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
        val requestId = context.request()?.getHeader("X-Request-ID") ?: "-"
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
        val requestId = context.request()?.getHeader("X-Request-ID") ?: "-"
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
                || message.contains("cannot cancel", ignoreCase = true)
                || message.contains("only be captured", ignoreCase = true)
                || message.contains("only confirmed", ignoreCase = true)
                || message.contains("draft", ignoreCase = true)
                || message.contains("insufficient captured payment", ignoreCase = true)
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
}
