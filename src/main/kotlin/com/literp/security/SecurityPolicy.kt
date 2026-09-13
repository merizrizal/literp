package com.literp.security

import io.vertx.core.Future
import java.time.Instant

data class AuthenticatedPrincipal(
    val subject: String,
    val issuer: String,
    val organizationId: String,
    val capabilities: Set<String>,
    val locationIds: Set<String>,
    val principalKind: PrincipalKind,
    val operator: Boolean,
    val expiresAt: Instant
)

enum class PrincipalKind {
    HUMAN,
    SERVICE
}

interface CredentialVerifier {
    fun verify(rawBearerCredential: String): Future<AuthenticatedPrincipal>
}

enum class CredentialFailureReason {
    VERIFICATION_UNAVAILABLE,
    INVALID_CREDENTIAL
}

sealed class CredentialFailure(
    val reason: CredentialFailureReason,
    message: String
) : RuntimeException(message)

class CredentialVerificationUnavailable : CredentialFailure(
    CredentialFailureReason.VERIFICATION_UNAVAILABLE,
    "Credential verification is not implemented"
)

class DenyAllCredentialVerifier : CredentialVerifier {
    override fun verify(rawBearerCredential: String): Future<AuthenticatedPrincipal> =
        Future.failedFuture(CredentialVerificationUnavailable())
}

sealed interface AuthorizationDecision {
    val operationId: String

    data class Allowed(
        override val operationId: String,
        val capability: String?,
        val principalEligibility: PrincipalEligibility
    ) : AuthorizationDecision

    data class RequiresResourceScope(
        override val operationId: String,
        val capability: String,
        val resourceScope: ResourceScopeRequirement,
        val principalEligibility: PrincipalEligibility
    ) : AuthorizationDecision {
        init {
            require(resourceScope != ResourceScopeRequirement.NONE) { "A resource scope requirement must be explicit" }
        }
    }

    data class Denied(
        override val operationId: String,
        val reason: AuthorizationDenyReason
    ) : AuthorizationDecision
}

enum class AuthorizationDenyReason {
    NO_EXPLICIT_POLICY,
    WRONG_ORGANIZATION,
    MISSING_CAPABILITY,
    PRINCIPAL_NOT_ELIGIBLE
}

enum class ResourceScopeRequirement {
    NONE,
    AUTHORIZED_LOCATION,
    AUTHORIZED_LOCATION_SET,
    SALES_ORDER_LOCATION
}

enum class PrincipalEligibility {
    AUTHENTICATED,
    PUBLIC,
    OPERATOR_OR_SERVICE
}

interface SecurityPolicy {
    fun require(principal: AuthenticatedPrincipal, operationId: String): AuthorizationDecision
}

class DenyAllSecurityPolicy : SecurityPolicy {
    override fun require(principal: AuthenticatedPrincipal, operationId: String): AuthorizationDecision =
        AuthorizationDecision.Denied(operationId, AuthorizationDenyReason.NO_EXPLICIT_POLICY)
}

class ExplicitSecurityPolicy(
    private val expectedOrganizationId: String
) : SecurityPolicy {
    init {
        require(expectedOrganizationId.isNotBlank()) { "Expected organization ID must not be blank" }
    }

    override fun require(principal: AuthenticatedPrincipal, operationId: String): AuthorizationDecision {
        val rule = EXPLICIT_POLICY_RULES[operationId]
            ?: return AuthorizationDecision.Denied(operationId, AuthorizationDenyReason.NO_EXPLICIT_POLICY)

        if (rule.principalEligibility != PrincipalEligibility.PUBLIC &&
            principal.organizationId != expectedOrganizationId
        ) {
            return AuthorizationDecision.Denied(operationId, AuthorizationDenyReason.WRONG_ORGANIZATION)
        }

        if (!isEligible(principal, rule.principalEligibility)) {
            return AuthorizationDecision.Denied(operationId, AuthorizationDenyReason.PRINCIPAL_NOT_ELIGIBLE)
        }

        if (rule.capability != null && rule.capability !in principal.capabilities) {
            return AuthorizationDecision.Denied(operationId, AuthorizationDenyReason.MISSING_CAPABILITY)
        }

        return if (rule.resourceScope == ResourceScopeRequirement.NONE) {
            AuthorizationDecision.Allowed(
                operationId = operationId,
                capability = rule.capability,
                principalEligibility = rule.principalEligibility
            )
        } else {
            AuthorizationDecision.RequiresResourceScope(
                operationId = operationId,
                capability = requireNotNull(rule.capability),
                resourceScope = rule.resourceScope,
                principalEligibility = rule.principalEligibility
            )
        }
    }

    private fun isEligible(
        principal: AuthenticatedPrincipal,
        eligibility: PrincipalEligibility
    ): Boolean = when (eligibility) {
        PrincipalEligibility.AUTHENTICATED,
        PrincipalEligibility.PUBLIC -> true
        PrincipalEligibility.OPERATOR_OR_SERVICE ->
            principal.operator || principal.principalKind == PrincipalKind.SERVICE
    }

    companion object {
        val operationIds: Set<String> = EXPLICIT_POLICY_RULES.keys.toSet()
        val utilityOperationIds: Set<String> = UtilityOperationIds.all
        val businessOperationIds: Set<String> = operationIds - utilityOperationIds
    }
}

object UtilityOperationIds {
    const val ROOT = "/"
    const val HEALTH_LIVE = "/health/live"
    const val METRICS = "/metrics"
    const val HEALTH_READY = "/health/ready"
    const val HEALTH_DB = "/health/db"

    val all: Set<String> = setOf(ROOT, HEALTH_LIVE, METRICS, HEALTH_READY, HEALTH_DB)
}

private data class PolicyRule(
    val capability: String?,
    val resourceScope: ResourceScopeRequirement = ResourceScopeRequirement.NONE,
    val principalEligibility: PrincipalEligibility = PrincipalEligibility.AUTHENTICATED
)

private val EXPLICIT_POLICY_RULES: Map<String, PolicyRule> = linkedMapOf(
    "listUnitOfMeasures" to PolicyRule("master-data.read"),
    "createUnitOfMeasure" to PolicyRule("master-data.write"),
    "getUnitOfMeasure" to PolicyRule("master-data.read"),
    "updateUnitOfMeasure" to PolicyRule("master-data.write"),
    "deleteUnitOfMeasure" to PolicyRule("master-data.write"),
    "listProducts" to PolicyRule("master-data.read"),
    "createProduct" to PolicyRule("master-data.write"),
    "getProduct" to PolicyRule("master-data.read"),
    "updateProduct" to PolicyRule("master-data.write"),
    "deleteProduct" to PolicyRule("master-data.write"),
    "listProductVariants" to PolicyRule("master-data.read"),
    "createProductVariant" to PolicyRule("master-data.write"),
    "getProductVariant" to PolicyRule("master-data.read"),
    "updateProductVariant" to PolicyRule("master-data.write"),
    "deleteProductVariant" to PolicyRule("master-data.write"),
    "listLocations" to PolicyRule("master-data.read"),
    "createLocation" to PolicyRule("master-data.write"),
    "getLocation" to PolicyRule("master-data.read"),
    "updateLocation" to PolicyRule("master-data.write"),
    "deleteLocation" to PolicyRule("master-data.write"),
    "getLocationByCode" to PolicyRule("master-data.read"),
    "listSalesOrders" to PolicyRule(
        capability = "order.read",
        resourceScope = ResourceScopeRequirement.AUTHORIZED_LOCATION_SET
    ),
    "createSalesOrderDraft" to PolicyRule(
        capability = "order.write",
        resourceScope = ResourceScopeRequirement.AUTHORIZED_LOCATION
    ),
    "getSalesOrder" to PolicyRule(
        capability = "order.read",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "addSalesOrderLine" to PolicyRule(
        capability = "order.write",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "confirmSalesOrder" to PolicyRule(
        capability = "order.confirm",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "capturePayment" to PolicyRule(
        capability = "payment.capture",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "fulfillSalesOrder" to PolicyRule(
        capability = "order.fulfill",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "cancelSalesOrder" to PolicyRule(
        capability = "order.cancel",
        resourceScope = ResourceScopeRequirement.SALES_ORDER_LOCATION
    ),
    "getCurrentStock" to PolicyRule(
        capability = "inventory.read",
        resourceScope = ResourceScopeRequirement.AUTHORIZED_LOCATION
    ),
    "getAvailableStock" to PolicyRule(
        capability = "inventory.read",
        resourceScope = ResourceScopeRequirement.AUTHORIZED_LOCATION
    ),
    UtilityOperationIds.ROOT to PolicyRule(
        capability = null,
        principalEligibility = PrincipalEligibility.PUBLIC
    ),
    UtilityOperationIds.HEALTH_LIVE to PolicyRule(
        capability = null,
        principalEligibility = PrincipalEligibility.PUBLIC
    ),
    UtilityOperationIds.METRICS to PolicyRule(
        capability = "operations.read",
        principalEligibility = PrincipalEligibility.OPERATOR_OR_SERVICE
    ),
    UtilityOperationIds.HEALTH_READY to PolicyRule(
        capability = "operations.read",
        principalEligibility = PrincipalEligibility.OPERATOR_OR_SERVICE
    ),
    UtilityOperationIds.HEALTH_DB to PolicyRule(
        capability = "operations.read",
        principalEligibility = PrincipalEligibility.OPERATOR_OR_SERVICE
    )
)
