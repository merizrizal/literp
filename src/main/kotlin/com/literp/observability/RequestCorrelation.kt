package com.literp.observability

import io.vertx.rxjava3.ext.web.RoutingContext
import java.util.UUID

object RequestCorrelation {
    const val HEADER = "X-Request-ID"
    const val CONTEXT_KEY = "requestId"
    private val validId = Regex("[A-Za-z0-9._:-]{1,128}")

    fun normalize(value: String?): String = value?.takeIf { validId.matches(it) } ?: UUID.randomUUID().toString()

    fun id(context: RoutingContext): String {
        val id = normalize(context.get<String>(CONTEXT_KEY) ?: context.request().getHeader(HEADER))
        context.put(CONTEXT_KEY, id)
        return id
    }
}
