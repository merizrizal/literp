package com.literp.verticle

import com.literp.verticle.handler.AuthenticatedActorAdapter
import com.literp.verticle.handler.authenticatedPrincipal
import com.literp.security.AuthenticatedPrincipal
import com.literp.security.CredentialVerifier
import com.literp.security.InvalidCredential
import com.literp.security.PrincipalKind
import com.literp.security.ExplicitSecurityPolicy
import com.literp.test.HttpTestSupport
import com.literp.test.HttpTestSupport.Companion.assertErrorEnvelope
import com.literp.verticle.handler.OrderScopeGateway
import com.literp.verticle.handler.OrderScopeHandler
import com.literp.verticle.handler.SecurityHandler
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.rxjava3.core.http.HttpServer
import io.vertx.rxjava3.core.Vertx as RxVertx
import io.vertx.rxjava3.ext.web.Router
import io.vertx.rxjava3.ext.web.handler.BodyHandler
import java.time.Instant
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHandlerTest {
    private lateinit var coreVertx: Vertx
    private lateinit var rxVertx: RxVertx
    private lateinit var server: HttpServer
    private lateinit var http: HttpTestSupport
    private lateinit var gateway: RecordingOrderScopeGateway
    private lateinit var verifier: StubCredentialVerifier
    private var continuationCount = 0

    @BeforeAll
    fun setUp() {
        coreVertx = Vertx.vertx()
        rxVertx = RxVertx.newInstance(coreVertx)
        gateway = RecordingOrderScopeGateway()
        verifier = StubCredentialVerifier(
            mapOf(
                "read" to principal("verified-subject", setOf("order.read"), setOf("location-a", "location-b")),
                "location-a" to principal("location-subject", setOf("order.read"), setOf("location-a")),
                "location-b" to principal("outside-location-subject", setOf("order.read"), setOf("location-b")),
                "fulfill" to principal("verified-fulfiller", setOf("order.fulfill"), setOf("location-a")),
                "wrong-org" to principal("wrong-org-subject", setOf("order.read"), setOf("location-a"), "other-org"),
                "no-capability" to principal("no-capability-subject", emptySet(), setOf("location-a")),
                "empty-locations" to principal("empty-location-subject", setOf("order.read"), emptySet())
            )
        )

        server = rxVertx.createHttpServer()
            .requestHandler(createRouter())
            .rxListen(0, "127.0.0.1")
            .blockingGet()
        http = HttpTestSupport("http://127.0.0.1:${server.actualPort()}")
    }

    @BeforeEach
    fun resetState() {
        continuationCount = 0
        gateway.reset()
    }

    @AfterAll
    fun tearDown() {
        if (::server.isInitialized) {
            server.rxClose().blockingAwait()
        }
        if (::coreVertx.isInitialized) {
            coreVertx.close().toCompletionStage().toCompletableFuture().get()
        }
    }

    @Test
    fun rejectsMissingAndInvalidCredentialsBeforeContinuation() {
        val missing = http.request("GET", "/catalog")
        assertEquals(401, missing.status)
        assertErrorEnvelope(requireNotNull(missing.json), 401, "UNAUTHENTICATED")
        assertEquals("Bearer", missing.header("WWW-Authenticate"))

        val invalid = http.request(
            "GET",
            "/catalog",
            headers = authorization("not-valid")
        )
        assertEquals(401, invalid.status)
        assertErrorEnvelope(requireNotNull(invalid.json), 401, "UNAUTHENTICATED")
        assertEquals(0, continuationCount)
    }

    @Test
    fun deniesWrongOrganizationAndMissingCapabilityBeforeContinuation() {
        val wrongOrganization = http.request(
            "GET",
            "/catalog",
            headers = authorization("wrong-org")
        )
        assertEquals(403, wrongOrganization.status)
        assertErrorEnvelope(requireNotNull(wrongOrganization.json), 403, "FORBIDDEN")

        val missingCapability = http.request(
            "GET",
            "/catalog",
            headers = authorization("no-capability")
        )
        assertEquals(403, missingCapability.status)
        assertErrorEnvelope(requireNotNull(missingCapability.json), 403, "FORBIDDEN")
        assertEquals(0, continuationCount)
    }

    @Test
    fun resourceDecisionRequiresScopeCheckAndHidesMissingAndOutsideOrders() {
        val outside = http.request(
            "GET",
            "/orders/order-1",
            headers = authorization("location-b")
        )
        assertEquals(404, outside.status)
        assertErrorEnvelope(requireNotNull(outside.json), 404, "RESOURCE_NOT_FOUND")

        val missing = http.request(
            "GET",
            "/orders/missing-order",
            headers = authorization("read")
        )
        assertEquals(404, missing.status)
        assertErrorEnvelope(requireNotNull(missing.json), 404, "RESOURCE_NOT_FOUND")
        assertEquals(0, continuationCount)
        assertEquals(listOf("order-1", "missing-order"), gateway.findCalls)
    }

    @Test
    fun authorizedResourceContinuesOnlyAfterPersistedScopeCheck() {
        val result = http.request(
            "GET",
            "/orders/order-1",
            headers = authorization("read")
        )

        assertEquals(200, result.status)
        val data = requireNotNull(result.json).getJsonObject("data")
        assertEquals("verified-subject", data.getString("subject"))
        assertEquals(1, continuationCount)
    }

    @Test
    fun listUsesScopedRepositoryWithAllGrantedLocationsAndRequestedNarrowing() {
        val result = http.request(
            "GET",
            "/orders?page=1&size=10&sort=orderNumber,asc&status=draft&salesChannel=pos&locationId=location-b",
            headers = authorization("read")
        )

        assertEquals(200, result.status)
        assertNotNull(result.json?.getJsonArray("data"))
        assertEquals(0, continuationCount)
        assertEquals(1, gateway.listCalls.size)
        val request = gateway.listCalls.single()
        assertEquals(1, request.page)
        assertEquals(10, request.size)
        assertEquals("orderNumber,asc", request.sort)
        assertEquals("draft", request.status)
        assertEquals("pos", request.salesChannel)
        assertEquals("location-b", request.locationId)
        assertEquals(setOf("location-a", "location-b"), request.authorizedLocationIds)
    }

    @Test
    fun emptyLocationGrantsCannotReachScopedListing() {
        val result = http.request(
            "GET",
            "/orders",
            headers = authorization("empty-locations")
        )

        assertEquals(403, result.status)
        assertErrorEnvelope(requireNotNull(result.json), 403, "FORBIDDEN")
        assertTrue(gateway.listCalls.isEmpty())
        assertEquals(0, continuationCount)
    }

    @Test
    fun requestedLocationMustBeGrantedAfterCapabilityAuthorization() {
        val result = http.request(
            "GET",
            "/stock?productId=product-1&locationId=location-b",
            headers = authorization("location-a")
        )

        assertEquals(403, result.status)
        assertErrorEnvelope(requireNotNull(result.json), 403, "FORBIDDEN")
        assertEquals(0, continuationCount)
    }

    @Test
    fun fulfillmentActorComesFromVerifiedSubjectNotRequestBody() {
        val result = http.request(
            "POST",
            "/fulfill/order-1",
            JsonObject().put("createdBy", "spoofed-body-actor").put("notes", "ship now"),
            authorization("fulfill")
        )

        assertEquals(200, result.status)
        val data = requireNotNull(result.json).getJsonObject("data")
        assertEquals("verified-fulfiller", data.getString("actor"))
        assertFalse(data.getString("actor") == "spoofed-body-actor")
        assertEquals(1, continuationCount)
    }

    private fun createRouter(): Router {
        val securityHandler = SecurityHandler(verifier, ExplicitSecurityPolicy("literp-org"))
        val scopeHandler = OrderScopeHandler(gateway)
        val actorAdapter = AuthenticatedActorAdapter()

        return Router.router(rxVertx).apply {
            route().handler(BodyHandler.create())
            route().handler(securityHandler::authenticate)

            get("/catalog")
                .handler(securityHandler.authorizeOperation("listProducts"))
                .handler { context ->
                    continuationCount++
                    context.response()
                        .setStatusCode(200)
                        .end(JsonObject().put("data", JsonObject().put("subject", context.authenticatedPrincipal()?.subject)).encode())
                }

            get("/orders")
                .handler(securityHandler.authorizeOperation("listSalesOrders"))
                .handler(scopeHandler::authorize)

            get("/orders/:salesOrderId")
                .handler(securityHandler.authorizeOperation("getSalesOrder"))
                .handler(scopeHandler::authorize)
                .handler { context ->
                    continuationCount++
                    context.response()
                        .setStatusCode(200)
                        .end(JsonObject().put("data", JsonObject().put("subject", context.authenticatedPrincipal()?.subject)).encode())
                }

            get("/stock")
                .handler(securityHandler.authorizeOperation("getCurrentStock"))
                .handler(scopeHandler::authorize)
                .handler {
                    continuationCount++
                    it.response().setStatusCode(200).end(JsonObject().put("data", JsonObject()).encode())
                }

            post("/fulfill/:salesOrderId")
                .handler(securityHandler.authorizeOperation("fulfillSalesOrder"))
                .handler(scopeHandler::authorize)
                .handler { context ->
                    continuationCount++
                    context.response()
                        .setStatusCode(200)
                        .end(
                            JsonObject()
                                .put("data", JsonObject().put("actor", actorAdapter.fulfillmentActor(context)))
                                .encode()
                        )
                }
        }
    }

    private fun authorization(token: String): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")

    companion object {
        private fun principal(
            subject: String,
            capabilities: Set<String>,
            locationIds: Set<String>,
            organizationId: String = "literp-org"
        ): AuthenticatedPrincipal = AuthenticatedPrincipal(
            subject = subject,
            issuer = "https://issuer.example",
            organizationId = organizationId,
            capabilities = capabilities,
            locationIds = locationIds,
            principalKind = PrincipalKind.HUMAN,
            operator = false,
            expiresAt = Instant.now().plusSeconds(300)
        )
    }
}

private class StubCredentialVerifier(
    private val principals: Map<String, AuthenticatedPrincipal>
) : CredentialVerifier {
    override fun verify(rawBearerCredential: String): Future<AuthenticatedPrincipal> =
        principals[rawBearerCredential]?.let { Future.succeededFuture(it) }
            ?: Future.failedFuture(InvalidCredential())
}

private class RecordingOrderScopeGateway : OrderScopeGateway {
    data class ListRequest(
        val page: Int,
        val size: Int,
        val sort: String,
        val status: String?,
        val salesChannel: String?,
        val locationId: String?,
        val authorizedLocationIds: Set<String>
    )

    val findCalls = mutableListOf<String>()
    val listCalls = mutableListOf<ListRequest>()
    private var locations: Map<String, String> = emptyMap()

    fun reset() {
        findCalls.clear()
        listCalls.clear()
        locations = mapOf("order-1" to "location-a")
    }

    override fun findLocation(orderId: String): Maybe<String> {
        findCalls += orderId
        return locations[orderId]?.let { Maybe.just(it) } ?: Maybe.empty()
    }

    override fun listAuthorizedOrders(
        page: Int,
        size: Int,
        sort: String,
        status: String?,
        salesChannel: String?,
        locationId: String?,
        authorizedLocationIds: Set<String>
    ): Single<JsonObject> {
        listCalls += ListRequest(page, size, sort, status, salesChannel, locationId, authorizedLocationIds)
        return Single.just(
            JsonObject()
                .put("data", emptyList<JsonObject>())
                .put(
                    "pagination",
                    JsonObject()
                        .put("page", page)
                        .put("size", size)
                        .put("totalElements", 0)
                        .put("totalPages", 0)
                )
        )
    }
}
