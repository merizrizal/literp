package com.literp.verticle.handler

import com.literp.common.ErrorCodes
import com.literp.security.AuthenticatedPrincipal
import com.literp.security.AuthorizationDecision
import com.literp.security.CredentialVerifier
import com.literp.security.SecurityPolicy
import io.vertx.core.Handler
import io.vertx.rxjava3.ext.web.RoutingContext
import io.vertx.ext.web.RoutingContext as CoreRoutingContext
import io.vertx.ext.web.handler.JWTAuthHandler as CoreJWTAuthHandler
import io.vertx.rxjava3.ext.web.handler.JWTAuthHandler as RxJWTAuthHandler

const val AUTHENTICATED_PRINCIPAL_CONTEXT_KEY = "literp.authenticatedPrincipal"
const val AUTHORIZATION_DECISION_CONTEXT_KEY = "literp.authorizationDecision"

internal object SecurityFailureCodes {
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val FORBIDDEN = "FORBIDDEN"
}

fun RoutingContext.authenticatedPrincipal(): AuthenticatedPrincipal? =
    get(AUTHENTICATED_PRINCIPAL_CONTEXT_KEY)

/** Extracts the verified subject for command attribution; it never reads caller-supplied actor fields. */
class AuthenticatedActorAdapter {
    fun fulfillmentActor(principal: AuthenticatedPrincipal): String = principal.subject

    fun fulfillmentActor(context: RoutingContext): String =
        fulfillmentActor(
            context.authenticatedPrincipal()
                ?: throw IllegalStateException("Authenticated principal is required")
        )
}

/** Bridges the OpenAPI bearer scheme to the existing fail-closed security handler. */
class OpenApiBearerAuthenticationHandler(
    private val delegate: SecurityHandler
) : CoreJWTAuthHandler {
    override fun handle(context: CoreRoutingContext) {
        val rxContext = RoutingContext.newInstance(context)
        if (rxContext.authenticatedPrincipal() == null) {
            delegate.authenticate(rxContext)
        } else {
            context.next()
        }
    }

    override fun scopeDelimiter(delimiter: String): CoreJWTAuthHandler = this

    override fun withScope(scope: String): CoreJWTAuthHandler = this

    override fun withScopes(scopes: List<String>): CoreJWTAuthHandler = this

    fun asRxHandler(): RxJWTAuthHandler = RxJWTAuthHandler.newInstance(this)
}

class SecurityHandler(
    private val credentialVerifier: CredentialVerifier,
    private val securityPolicy: SecurityPolicy
) : BaseHandler(SecurityHandler::class.java) {

    fun authenticate(context: RoutingContext) {
        val authorizationHeaders = context.request()?.headers()?.getAll(AUTHORIZATION_HEADER).orEmpty()
        val credential = authorizationHeaders.singleOrNull()?.let(::parseBearerCredential)

        if (credential == null) {
            respondUnauthenticated(context)
            return
        }

        try {
            credentialVerifier.verify(credential)
                .onSuccess { principal ->
                    context.put(AUTHENTICATED_PRINCIPAL_CONTEXT_KEY, principal)
                    context.next()
                }
                .onFailure { respondUnauthenticated(context) }
        } catch (_: Throwable) {
            respondUnauthenticated(context)
        }
    }

    fun authorizeOperation(operationId: String): Handler<RoutingContext> {
        require(operationId.isNotBlank()) { "Operation ID must not be blank" }
        return Handler { context -> authorizeOperation(context, operationId) }
    }

    fun authorizeOperation(context: RoutingContext, operationId: String) {
        val principal = context.authenticatedPrincipal()
        if (principal == null) {
            respondUnauthenticated(context)
            return
        }

        val decision = try {
            securityPolicy.require(principal, operationId)
        } catch (_: Throwable) {
            putErrorResponse(
                context,
                500,
                "Authorization is unavailable",
                ErrorCodes.INTERNAL_ERROR
            )
            return
        }

        when (decision) {
            is AuthorizationDecision.Allowed -> context.next()
            is AuthorizationDecision.RequiresResourceScope -> {
                context.put(AUTHORIZATION_DECISION_CONTEXT_KEY, decision)
                context.next()
            }
            is AuthorizationDecision.Denied -> respondForbidden(context)
        }
    }

    private fun parseBearerCredential(header: String): String? {
        val match = BEARER_PATTERN.matchEntire(header.trim()) ?: return null
        return match.groupValues[1]
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

    internal fun respondForbidden(context: RoutingContext) {
        putErrorResponse(context, 403, "Forbidden", SecurityFailureCodes.FORBIDDEN)
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        val BEARER_PATTERN = Regex("^Bearer[ \\t]+([^ \\t]+)$", RegexOption.IGNORE_CASE)
    }
}
