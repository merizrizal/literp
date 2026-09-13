package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import com.literp.repository.OrderScopeRepository
import com.literp.repository.OrderScopeViolation
import com.literp.security.AuthorizationDecision
import com.literp.security.ResourceScopeRequirement
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.openapi.validation.ValidatedRequest
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.rxjava3.ext.web.openapi.router.RouterBuilder
import java.util.concurrent.TimeoutException

interface OrderScopeGateway {
    fun findLocation(orderId: String): Maybe<String>

    fun listAuthorizedOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject>
}

class OrderScopeHandler(
    private val scopeGateway: OrderScopeGateway,
    private val actorAdapter: AuthenticatedActorAdapter = AuthenticatedActorAdapter()
) : BaseHandler(OrderScopeHandler::class.java) {

    constructor(
        scopeRepository: OrderScopeRepository,
        actorAdapter: AuthenticatedActorAdapter = AuthenticatedActorAdapter()
    ) : this(RepositoryOrderScopeGateway(scopeRepository), actorAdapter)

    fun authorize(operationId: String): Handler<RoutingContext> {
        require(operationId.isNotBlank()) { "Operation ID must not be blank" }
        return Handler { context -> authorize(context, operationId) }
    }

    fun authorize(context: RoutingContext) {
        val decision = context.get<AuthorizationDecision>(AUTHORIZATION_DECISION_CONTEXT_KEY)
        authorize(context, decision)
    }

    fun authorize(context: RoutingContext, operationId: String) {
        val decision = context.get<AuthorizationDecision>(AUTHORIZATION_DECISION_CONTEXT_KEY)
        if (decision == null || decision.operationId != operationId) {
            respondForbidden(context)
            return
        }
        authorize(context, decision)
    }

    fun fulfillmentActor(context: RoutingContext): String = actorAdapter.fulfillmentActor(context)

    private fun authorize(context: RoutingContext, decision: AuthorizationDecision?) {
        val principal = context.authenticatedPrincipal()
        if (principal == null) {
            respondUnauthenticated(context)
            return
        }

        val resourceDecision = decision as? AuthorizationDecision.RequiresResourceScope
        if (resourceDecision == null || resourceDecision.capability !in principal.capabilities) {
            respondForbidden(context)
            return
        }

        when (resourceDecision.resourceScope) {
            ResourceScopeRequirement.AUTHORIZED_LOCATION ->
                authorizeLocation(context, principal.locationIds)
            ResourceScopeRequirement.AUTHORIZED_LOCATION_SET ->
                listAuthorizedOrders(context, principal.locationIds)
            ResourceScopeRequirement.SALES_ORDER_LOCATION ->
                authorizeSalesOrder(context, principal.locationIds)
            ResourceScopeRequirement.NONE -> respondForbidden(context)
        }
    }

    private fun authorizeLocation(context: RoutingContext, authorizedLocationIds: Set<String>) {
        val locationId = requestedLocationId(context)
        if (locationId.isNullOrBlank()) {
            putErrorResponse(context, 400, "locationId is required")
            return
        }
        if (locationId !in authorizedLocationIds) {
            respondForbidden(context)
            return
        }
        context.next()
    }

    private fun listAuthorizedOrders(context: RoutingContext, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            respondForbidden(context)
            return
        }

        val locationId = context.queryParam("locationId").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (locationId != null && locationId !in authorizedLocationIds) {
            respondForbidden(context)
            return
        }

        val page = parseIntQueryParam(context, "page", 0, 0, null) ?: return
        val size = parseIntQueryParam(context, "size", 20, 1, 100) ?: return
        val sort = context.queryParam("sort").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: "orderDate,desc"
        val status = context.queryParam("status").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val salesChannel = context.queryParam("salesChannel").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

        scopeGateway.listAuthorizedOrders(
            page = page,
            size = size,
            sort = sort,
            status = status,
            salesChannel = salesChannel,
            locationId = locationId,
            authorizedLocationIds = authorizedLocationIds.toSet()
        ).subscribe(
            { response -> putSuccessEnvelopeResponse(context, 200, response) },
            { error -> respondScopeFailure(context, error) }
        )
    }

    private fun authorizeSalesOrder(context: RoutingContext, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            respondForbidden(context)
            return
        }

        val orderId = context.pathParam("salesOrderId")?.trim().orEmpty()
        if (orderId.isBlank()) {
            putErrorResponse(context, 400, "salesOrderId is required")
            return
        }

        scopeGateway.findLocation(orderId).subscribe(
            { locationId ->
                if (locationId.isNotBlank() && locationId in authorizedLocationIds) {
                    context.next()
                } else {
                    respondNotFound(context)
                }
            },
            { error -> respondScopeFailure(context, error) },
            { respondNotFound(context) }
        )
    }

    private fun requestedLocationId(context: RoutingContext): String? {
        return when (context.get<AuthorizationDecision>(AUTHORIZATION_DECISION_CONTEXT_KEY)?.operationId) {
            "createSalesOrderDraft" -> requestBody(context)?.getString("locationId")?.trim()
            "getCurrentStock", "getAvailableStock" ->
                context.queryParam("locationId").firstOrNull()?.trim()
            else -> context.queryParam("locationId").firstOrNull()?.trim()
        }
    }

    private fun requestBody(context: RoutingContext): JsonObject? {
        val validatedRequest = context.get<ValidatedRequest>(RouterBuilder.KEY_META_DATA_VALIDATED_REQUEST)
        return validatedRequest?.body?.jsonObject
            ?: runCatching { context.body().asJsonObject() }.getOrNull()
    }

    private fun parseIntQueryParam(
        context: RoutingContext,
        name: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int?
    ): Int? {
        val rawValue = context.queryParam(name).firstOrNull()?.trim() ?: return defaultValue
        val parsed = rawValue.toIntOrNull()
        if (parsed == null) {
            putErrorResponse(context, 400, "$name must be an integer")
            return null
        }
        if (parsed < minimum || (maximum != null && parsed > maximum)) {
            val upperBound = maximum?.let { " and less than or equal to $it" }.orEmpty()
            putErrorResponse(context, 400, "$name must be greater than or equal to $minimum$upperBound")
            return null
        }
        return parsed
    }

    private fun respondScopeFailure(context: RoutingContext, error: Throwable) {
        when {
            error is OrderScopeViolation -> respondForbidden(context)
            error is TimeoutException || error.cause is TimeoutException ->
                putErrorResponse(context, 503, "Database unavailable", ErrorCodes.DB_TIMEOUT)
            else -> putErrorResponse(context, 500, "Unable to authorize request", ErrorCodes.INTERNAL_ERROR)
        }
    }

    private fun respondNotFound(context: RoutingContext) {
        putErrorResponse(context, 404, "Sales order not found", ErrorCodes.RESOURCE_NOT_FOUND)
    }

    private fun respondUnauthenticated(context: RoutingContext) {
        context.response().putHeader("WWW-Authenticate", "Bearer")
        putErrorResponse(
            context,
            401,
            "Authentication required",
            SecurityFailureCodes.UNAUTHENTICATED
        )
    }

    private fun respondForbidden(context: RoutingContext) {
        putErrorResponse(context, 403, "Forbidden", SecurityFailureCodes.FORBIDDEN)
    }
}

private class RepositoryOrderScopeGateway(
    private val repository: OrderScopeRepository
) : OrderScopeGateway {
    override fun findLocation(orderId: String): Maybe<String> = repository.findLocation(orderId)

    override fun listAuthorizedOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> = repository.listAuthorizedOrders(
        page,
        size,
        sort,
        status,
        salesChannel,
        locationId,
        authorizedLocationIds
    )
}
