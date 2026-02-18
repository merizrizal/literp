package com.literp.verticle.handler

import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.ext.web.RoutingContext

open class BaseHandler {
    protected fun putResponse(context: RoutingContext, statusCode: Int, response: JsonObject) {
        context.response().statusCode = statusCode
        context.response().putHeader("Content-Type", "application/json")
        context.response().end(response.encode())
    }

    protected fun putErrorResponse(context: RoutingContext, statusCode: Int, message: String) {
        val errorResponse = JsonObject().put("error", message).put("status", statusCode)
        putResponse(context, statusCode, errorResponse)
    }
}
