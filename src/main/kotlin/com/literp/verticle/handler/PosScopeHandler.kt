package com.literp.verticle.handler

import com.literp.security.AuthorizationDecision
import com.literp.security.ResourceScopeRequirement
import io.vertx.core.Handler
import io.vertx.rxjava3.ext.web.RoutingContext

const val POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY = "literp.pos.authorizedLocationIds"

class PosScopeHandler : BaseHandler(PosScopeHandler::class.java) {

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
            ResourceScopeRequirement.POS_AUTHORIZED_LOCATION_SET -> authorizeTerminalList(context, principal.locationIds)
            ResourceScopeRequirement.POS_TERMINAL_LOCATION -> authorizeTerminalRead(context, principal.locationIds)
            else -> respondForbidden(context)
        }
    }

    private fun authorizeTerminalList(context: RoutingContext, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            respondForbidden(context)
            return
        }

        val requestedLocationId = context.queryParam("locationId").firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (requestedLocationId != null && requestedLocationId !in authorizedLocationIds) {
            respondForbidden(context)
            return
        }

        continueWithAuthorizedLocations(context, authorizedLocationIds)
    }

    private fun authorizeTerminalRead(context: RoutingContext, authorizedLocationIds: Set<String>) {
        if (authorizedLocationIds.isEmpty()) {
            respondForbidden(context)
            return
        }

        continueWithAuthorizedLocations(context, authorizedLocationIds)
    }

    private fun continueWithAuthorizedLocations(context: RoutingContext, authorizedLocationIds: Set<String>) {
        context.put(POS_AUTHORIZED_LOCATION_IDS_CONTEXT_KEY, authorizedLocationIds.toSet())
        context.next()
    }

    private fun respondUnauthenticated(context: RoutingContext) {
        context.response().putHeader("WWW-Authenticate", "Bearer")
        putErrorResponse(context, 401, "Authentication required", SecurityFailureCodes.UNAUTHENTICATED)
    }

    private fun respondForbidden(context: RoutingContext) {
        putErrorResponse(context, 403, "Forbidden", SecurityFailureCodes.FORBIDDEN)
    }
}
