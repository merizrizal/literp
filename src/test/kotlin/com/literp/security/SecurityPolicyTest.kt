package com.literp.security

import java.time.Instant
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SecurityPolicyTest {
    private val principal = AuthenticatedPrincipal(
        subject = "subject-1",
        issuer = "https://issuer.example/realm/literp",
        organizationId = "organization-1",
        capabilities = setOf("master-data.read"),
        locationIds = setOf("9b122b54-41f9-4e7a-aef6-26b1f196fe94"),
        principalKind = PrincipalKind.HUMAN,
        operator = false,
        expiresAt = Instant.parse("2030-01-01T00:00:00Z")
    )

    @Test
    fun denyAllPolicyRejectsKnownAndUnknownOperations() {
        val policy = DenyAllSecurityPolicy()

        assertDenied(policy.require(principal, "listProducts"), "listProducts")
        assertDenied(policy.require(principal, "unregisteredOperation"), "unregisteredOperation")
    }

    @Test
    fun explicitPolicyCoversExactlyApprovedBusinessOperations() {
        val expected = mapOf(
            "listUnitOfMeasures" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "createUnitOfMeasure" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "getUnitOfMeasure" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "updateUnitOfMeasure" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "deleteUnitOfMeasure" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "listProducts" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "createProduct" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "getProduct" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "updateProduct" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "deleteProduct" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "listProductVariants" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "createProductVariant" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "getProductVariant" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "updateProductVariant" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "deleteProductVariant" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "listLocations" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "createLocation" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "getLocation" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "updateLocation" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "deleteLocation" to ("master-data.write" to ResourceScopeRequirement.NONE),
            "getLocationByCode" to ("master-data.read" to ResourceScopeRequirement.NONE),
            "listSalesOrders" to ("order.read" to ResourceScopeRequirement.AUTHORIZED_LOCATION_SET),
            "createSalesOrderDraft" to ("order.write" to ResourceScopeRequirement.AUTHORIZED_LOCATION),
            "getSalesOrder" to ("order.read" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "addSalesOrderLine" to ("order.write" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "confirmSalesOrder" to ("order.confirm" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "capturePayment" to ("payment.capture" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "fulfillSalesOrder" to ("order.fulfill" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "cancelSalesOrder" to ("order.cancel" to ResourceScopeRequirement.SALES_ORDER_LOCATION),
            "getCurrentStock" to ("inventory.read" to ResourceScopeRequirement.AUTHORIZED_LOCATION),
            "getAvailableStock" to ("inventory.read" to ResourceScopeRequirement.AUTHORIZED_LOCATION)
        )

        assertEquals(31, expected.size)
        assertEquals(expected.keys, ExplicitSecurityPolicy.businessOperationIds)

        expected.forEach { (operationId, expectedRule) ->
            val decision = ExplicitSecurityPolicy("organization-1").require(
                principal.copy(capabilities = setOf(expectedRule.first)),
                operationId
            )
            if (expectedRule.second == ResourceScopeRequirement.NONE) {
                val allowed = assertAllowed(decision, operationId)
                assertEquals(expectedRule.first, allowed.capability)
                assertEquals(PrincipalEligibility.AUTHENTICATED, allowed.principalEligibility)
            } else {
                val required = assertRequiresResourceScope(decision, operationId)
                assertEquals(expectedRule.first, required.capability)
                assertEquals(expectedRule.second, required.resourceScope)
                assertEquals(PrincipalEligibility.AUTHENTICATED, required.principalEligibility)
            }
        }
    }

    @Test
    fun explicitPolicyRejectsUnknownOperationsAndAuthorizationMismatches() {
        val policy = ExplicitSecurityPolicy("organization-1")

        assertDenied(
            policy.require(principal, "unregisteredOperation"),
            "unregisteredOperation",
            AuthorizationDenyReason.NO_EXPLICIT_POLICY
        )
        assertDenied(
            policy.require(principal.copy(organizationId = "organization-2"), "listProducts"),
            "listProducts",
            AuthorizationDenyReason.WRONG_ORGANIZATION
        )
        assertDenied(
            policy.require(principal.copy(capabilities = emptySet()), "listProducts"),
            "listProducts",
            AuthorizationDenyReason.MISSING_CAPABILITY
        )
        assertDenied(
            policy.require(principal.copy(capabilities = setOf("*")), "listProducts"),
            "listProducts",
            AuthorizationDenyReason.MISSING_CAPABILITY
        )
    }

    @Test
    fun explicitPolicyDefinesPublicUtilityExceptions() {
        val policy = ExplicitSecurityPolicy("organization-1")
        val expectedUtilities = setOf(
            UtilityOperationIds.ROOT,
            UtilityOperationIds.HEALTH_LIVE,
            UtilityOperationIds.METRICS,
            UtilityOperationIds.HEALTH_READY,
            UtilityOperationIds.HEALTH_DB
        )

        assertEquals(expectedUtilities, ExplicitSecurityPolicy.utilityOperationIds)

        listOf(UtilityOperationIds.ROOT, UtilityOperationIds.HEALTH_LIVE).forEach { operationId ->
            val allowed = assertAllowed(
                policy.require(principal.copy(organizationId = "organization-2", capabilities = emptySet()), operationId),
                operationId
            )
            assertEquals(null, allowed.capability)
            assertEquals(PrincipalEligibility.PUBLIC, allowed.principalEligibility)
        }
    }

    @Test
    fun operationalUtilitiesRequireCapabilityAndOperatorOrServiceEligibility() {
        val policy = ExplicitSecurityPolicy("organization-1")
        val capability = setOf("operations.read")

        assertDenied(
            policy.require(principal.copy(capabilities = capability), UtilityOperationIds.METRICS),
            UtilityOperationIds.METRICS,
            AuthorizationDenyReason.PRINCIPAL_NOT_ELIGIBLE
        )
        assertAllowed(
            policy.require(principal.copy(capabilities = capability, operator = true), UtilityOperationIds.METRICS),
            UtilityOperationIds.METRICS
        )
        assertAllowed(
            policy.require(
                principal.copy(
                    capabilities = capability,
                    principalKind = PrincipalKind.SERVICE,
                    operator = false
                ),
                UtilityOperationIds.HEALTH_READY
            ),
            UtilityOperationIds.HEALTH_READY
        )
        assertDenied(
            policy.require(
                principal.copy(
                    capabilities = emptySet(),
                    principalKind = PrincipalKind.SERVICE
                ),
                UtilityOperationIds.HEALTH_DB
            ),
            UtilityOperationIds.HEALTH_DB,
            AuthorizationDenyReason.MISSING_CAPABILITY
        )
        assertDenied(
            policy.require(
                principal.copy(
                    organizationId = "organization-2",
                    capabilities = capability,
                    operator = true
                ),
                UtilityOperationIds.METRICS
            ),
            UtilityOperationIds.METRICS,
            AuthorizationDenyReason.WRONG_ORGANIZATION
        )
    }

    @Test
    fun denyAllCredentialVerifierNeverCreatesAPrincipal() {
        val failure = assertFailsWith<ExecutionException> {
            DenyAllCredentialVerifier()
                .verify("not-a-token")
                .toCompletionStage()
                .toCompletableFuture()
                .get()
        }

        val cause = assertIs<CredentialVerificationUnavailable>(failure.cause)
        assertEquals(CredentialFailureReason.VERIFICATION_UNAVAILABLE, cause.reason)
    }

    private fun assertAllowed(
        decision: AuthorizationDecision,
        operationId: String
    ): AuthorizationDecision.Allowed {
        val allowed = assertIs<AuthorizationDecision.Allowed>(decision)
        assertEquals(operationId, allowed.operationId)
        return allowed
    }

    private fun assertRequiresResourceScope(
        decision: AuthorizationDecision,
        operationId: String
    ): AuthorizationDecision.RequiresResourceScope {
        val required = assertIs<AuthorizationDecision.RequiresResourceScope>(decision)
        assertEquals(operationId, required.operationId)
        return required
    }

    private fun assertDenied(
        decision: AuthorizationDecision,
        operationId: String,
        reason: AuthorizationDenyReason = AuthorizationDenyReason.NO_EXPLICIT_POLICY
    ) {
        val denied = assertIs<AuthorizationDecision.Denied>(decision)
        assertEquals(operationId, denied.operationId)
        assertEquals(reason, denied.reason)
    }
}
