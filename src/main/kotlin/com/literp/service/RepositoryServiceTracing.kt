package com.literp.service

import com.literp.observability.RequestCorrelation
import com.literp.repository.BaseRepository
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.json.JsonObject
import io.vertx.serviceproxy.ServiceBinder
import io.vertx.serviceproxy.ServiceProxyBuilder

object RepositoryServiceTracing {
    fun <T> proxy(vertx: Vertx, type: Class<T>, address: String, requestId: String): T =
        ServiceProxyBuilder(vertx).setAddress(address)
            .setOptions(DeliveryOptions().addHeader(RequestCorrelation.HEADER, RequestCorrelation.normalize(requestId)))
            .build(type)

    fun <T> register(vertx: Vertx, type: Class<T>, address: String, service: T, repository: BaseRepository) {
        ServiceBinder(vertx).setAddress(address).addInterceptor { _, _, message ->
            val requestId = RequestCorrelation.normalize(message.headers().get(RequestCorrelation.HEADER))
            val operation = message.headers().get("action")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,80}")) } ?: "unknown"
            val complete = repository.beginExecution(requestId, operation)
            // Each wrapper/closure belongs to exactly one message, including its asynchronous reply.
            val traced = object : Message<JsonObject> by message {
                override fun reply(body: Any?) = reply(body, DeliveryOptions())
                override fun reply(body: Any?, options: DeliveryOptions) {
                    complete(body !is ReplyException)
                    message.reply(body, options)
                }
                override fun fail(failureCode: Int, failureMessage: String?) {
                    complete(false)
                    message.fail(failureCode, failureMessage)
                }
            }
            Future.succeededFuture(traced)
        }.register(type, service)
    }
}
