package com.literp.verticle.handler

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

    /**
     * Send a structured error response and log the event for observability.
     * Generates an `errorId` which is included in the response and in the log entry
     * so downstream systems (tracing/monitoring) can correlate incidents.
     */
    protected fun putErrorResponse(context: RoutingContext, statusCode: Int, message: String?) {
        val errorId = UUID.randomUUID().toString()
        val path = context.request()?.path() ?: "-"
        val requestId = context.request()?.getHeader("X-Request-ID") ?: "-"
        logger.warn("Handling errorId=$errorId status=$statusCode path=$path requestId=$requestId message=${message ?: "-"}")

        val errorResponse = JsonObject()
            .put("error", message)
            .put("status", statusCode)
            .put("errorId", errorId)

        putResponse(context, statusCode, errorResponse)
    }

    /**
     * Log throwable (with stack) and return an opaque error id to the client.
     * Use this overload when an exception is available to record full context.
     */
    protected fun putErrorResponse(context: RoutingContext, statusCode: Int, throwable: Throwable) {
        val errorId = UUID.randomUUID().toString()
        val path = context.request()?.path() ?: "-"
        val requestId = context.request()?.getHeader("X-Request-ID") ?: "-"
        logger.error("Unhandled exception errorId=$errorId status=$statusCode path=$path requestId=$requestId", throwable)

        val message = throwable.message ?: "Internal server error"
        val errorResponse = JsonObject()
            .put("error", message)
            .put("status", statusCode)
            .put("errorId", errorId)

        putResponse(context, statusCode, errorResponse)
    }
}
